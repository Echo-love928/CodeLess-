package com.hjq.CodeLess.service;

import com.hjq.CodeLess.exception.BusinessException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
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
    void failurePersistsFailedStateAndReleasesGeneration() {
        var service = new AppGenerationService();
        var states = new ArrayList<String>();
        assertThrows(IllegalStateException.class, () -> service.generate(1L,
                () -> Flux.error(new IllegalStateException("save failed")), states::add).blockLast());
        assertEquals(List.of("generating", "failed"), states);
        assertFalse(service.stop(1L));
    }
}
