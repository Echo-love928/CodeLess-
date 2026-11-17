package com.hjq.CodeLess.service;

import com.hjq.CodeLess.core.AiCodeGeneratorFacade;
import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.model.entity.App;
import com.hjq.CodeLess.model.entity.User;
import com.hjq.CodeLess.model.enums.CodeGenTypeEnum;
import com.hjq.CodeLess.service.impl.AppServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppChatHistoryTest {
    private final AppServiceImpl service = spy(new AppServiceImpl());
    private final ChatHistoryService history = mock(ChatHistoryService.class);
    private final AiCodeGeneratorFacade generator = mock(AiCodeGeneratorFacade.class);
    private final AppCoverGeneratorService covers = mock(AppCoverGeneratorService.class);
    private final AppGenerationService generation = new AppGenerationService();
    private final User owner = User.builder().id(2L).build();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "chatHistoryService", history);
        ReflectionTestUtils.setField(service, "aiCodeGeneratorFacade", generator);
        ReflectionTestUtils.setField(service, "appCoverGeneratorService", covers);
        ReflectionTestUtils.setField(service, "appGenerationService", generation);
        doReturn(App.builder().id(1L).userId(2L).codeGenType("html").build()).when(service).getById(1L);
        doReturn(true).when(service).updateById(any(App.class));
        when(covers.generateCover(1L, "html")).thenReturn(Optional.empty());
    }

    @Test
    void savesUserThenCompleteAiResponseAndPreservesStream() {
        when(generator.generateAndSaveCodeStream("generate", CodeGenTypeEnum.HTML, 1L))
                .thenReturn(Flux.just("hello", " world"));
        var stream = service.chatToGenCode(1L, "generate", owner);
        verifyNoInteractions(history, generator);

        assertEquals(List.of("", "hello", " world"), stream.collectList().block(Duration.ofSeconds(5)));
        var order = inOrder(history, generator, covers);
        order.verify(history).addChatMessage(1L, "generate", "user", 2L);
        order.verify(generator).generateAndSaveCodeStream("generate", CodeGenTypeEnum.HTML, 1L);
        order.verify(history).addChatMessage(1L, "hello world", "ai", 2L);
        order.verify(covers).generateCover(1L, "html");
        verifyNoMoreInteractions(history);
    }

    @Test
    void recordsStreamFailureInsteadOfPartialAiResponse() {
        var failure = new IllegalStateException("model failed");
        when(generator.generateAndSaveCodeStream("generate", CodeGenTypeEnum.HTML, 1L))
                .thenReturn(Flux.concat(Flux.just("partial"), Flux.error(failure)));

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.chatToGenCode(1L, "generate", owner).blockLast(Duration.ofSeconds(5))));
        verify(history).addChatMessage(1L, "generate", "user", 2L);
        verify(history).addChatMessage(1L, "AI回复失败: model failed", "ai", 2L);
        verifyNoMoreInteractions(history);
        verifyNoInteractions(covers);
    }

    @Test
    void recordsSynchronousGeneratorFailure() {
        when(generator.generateAndSaveCodeStream("generate", CodeGenTypeEnum.HTML, 1L))
                .thenThrow(new IllegalStateException("startup failed"));
        assertThrows(IllegalStateException.class,
                () -> service.chatToGenCode(1L, "generate", owner).blockLast(Duration.ofSeconds(5)));
        verify(history).addChatMessage(1L, "AI回复失败: startup failed", "ai", 2L);
    }

    @Test
    void doesNotSaveBlankAiResponse() {
        when(generator.generateAndSaveCodeStream("generate", CodeGenTypeEnum.HTML, 1L))
                .thenReturn(Flux.just(" ", "\n"));
        service.chatToGenCode(1L, "generate", owner).blockLast(Duration.ofSeconds(5));
        verify(history).addChatMessage(1L, "generate", "user", 2L);
        verifyNoMoreInteractions(history);
    }

    @Test
    void stoppingDoesNotSavePartialReplyOrReportAiFailure() {
        var source = Sinks.many().unicast().<String>onBackpressureBuffer();
        when(generator.generateAndSaveCodeStream("generate", CodeGenTypeEnum.HTML, 1L)).thenReturn(source.asFlux());
        var error = new AtomicReference<Throwable>();
        var subscription = service.chatToGenCode(1L, "generate", owner).subscribe(ignored -> {}, error::set);
        source.tryEmitNext("partial");
        assertTrue(service.stopGeneration(1L, owner));
        assertInstanceOf(CancellationException.class, error.get());
        verify(history).addChatMessage(1L, "generate", "user", 2L);
        verifyNoMoreInteractions(history);
        verifyNoInteractions(covers);
        subscription.dispose();
    }

    @Test
    void rejectedConcurrentRequestDoesNotCreateHistory() {
        when(generator.generateAndSaveCodeStream("generate", CodeGenTypeEnum.HTML, 1L)).thenReturn(Flux.never());
        var subscription = service.chatToGenCode(1L, "generate", owner).subscribe();
        try {
            assertThrows(BusinessException.class,
                    () -> service.chatToGenCode(1L, "generate again", owner).blockLast(Duration.ofSeconds(5)));
            verify(history).addChatMessage(1L, "generate", "user", 2L);
            verifyNoMoreInteractions(history);
        } finally {
            subscription.dispose();
        }
    }

    @Test
    void separateSubscriptionsDoNotAccumulatePreviousReply() {
        when(generator.generateAndSaveCodeStream("generate", CodeGenTypeEnum.HTML, 1L)).thenReturn(Flux.just("reply"));
        var stream = service.chatToGenCode(1L, "generate", owner);
        stream.blockLast(Duration.ofSeconds(5));
        stream.blockLast(Duration.ofSeconds(5));
        verify(history, times(2)).addChatMessage(1L, "generate", "user", 2L);
        verify(history, times(2)).addChatMessage(1L, "reply", "ai", 2L);
        verifyNoMoreInteractions(history);
    }
}
