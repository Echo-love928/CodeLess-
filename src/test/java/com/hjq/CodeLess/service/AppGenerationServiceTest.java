package com.hjq.CodeLess.service;

import com.hjq.CodeLess.exception.BusinessException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AppGenerationServiceTest {
    @Test
    void stopCancelsUpstreamAndDoesNotReportCompletion() {
        var service = new AppGenerationService();
        var states = new ArrayList<String>();
        var cancelled = new AtomicBoolean();
        var error = new AtomicReference<Throwable>();
        var stream = Sinks.many().unicast().<String>onBackpressureBuffer();
        var subscription = service.generate(1L, () -> stream.asFlux().doOnCancel(() -> cancelled.set(true)), states::add)
                .subscribe(ignored -> {}, error::set);
        assertTrue(service.stop(1L));
        assertTrue(cancelled.get());
        assertInstanceOf(CancellationException.class, error.get());
        assertEquals(List.of("generating", "stopped"), states);
        assertFalse(service.stop(1L));
        subscription.dispose();
    }

    @Test
    void rejectsConcurrentGenerationAndAllowsRetryAfterCancellation() {
        var service = new AppGenerationService();
        var states = new ArrayList<String>();
        var active = service.generate(1L, Flux::never, states::add).subscribe();
        assertThrows(BusinessException.class, () -> service.generate(1L, Flux::empty, states::add).blockLast());
        active.dispose();
        service.generate(1L, () -> Flux.just("done"), states::add).blockLast();
        assertEquals(List.of("generating", "stopped", "generating", "completed"), states);
    }

    @Test
    void stopKeepsAppLockedUntilVueBuildExits() throws InterruptedException {
        var service = new AppGenerationService();
        var model = Sinks.<String>empty();
        var buildStarted = new CountDownLatch(1);
        var releaseBuild = new CountDownLatch(1);
        var error = new AtomicReference<Throwable>();
        var stream = service.generate(1L, () -> model.asMono().flux()
                .concatWith(service.completeVueBuild(1L, () -> {
                    buildStarted.countDown();
                    try {
                        assertTrue(releaseBuild.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interruption) {
                        Thread.currentThread().interrupt();
                        fail(interruption);
                    }
                }).then(Mono.empty())), ignored -> {});
        var subscription = stream.subscribe(ignored -> {}, error::set);
        var completion = Thread.startVirtualThread(() -> model.emitEmpty(Sinks.EmitFailureHandler.FAIL_FAST));
        try {
            assertTrue(buildStarted.await(5, TimeUnit.SECONDS));
            assertTrue(service.stop(1L));
            assertInstanceOf(CancellationException.class, error.get());
            assertThrows(BusinessException.class, () -> service.withIdleApp(1L, () -> true));
        } finally {
            releaseBuild.countDown();
            completion.join(5000);
            subscription.dispose();
        }
        assertTrue(service.withIdleApp(1L, () -> true));
    }

    @Test
    void failurePersistsFailedStateAndReleasesGeneration() {
        var service = new AppGenerationService();
        var states = new ArrayList<String>();
        assertThrows(IllegalStateException.class, () -> service.generate(1L,
                () -> Flux.error(new IllegalStateException("save failed")), states::add).blockLast());
        assertEquals(List.of("generating", "failed"), states);
        assertFalse(service.stop(1L));
    }
}
