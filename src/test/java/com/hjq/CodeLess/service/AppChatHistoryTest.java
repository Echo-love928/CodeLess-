package com.hjq.CodeLess.service;

import com.hjq.CodeLess.ai.tools.ToolManager;
import com.hjq.CodeLess.constant.AppConstant;
import com.hjq.CodeLess.core.AiCodeGeneratorFacade;
import com.hjq.CodeLess.core.builder.VueProjectBuilder;
import com.hjq.CodeLess.core.handler.JsonMessageStreamHandler;
import com.hjq.CodeLess.core.handler.SimpleTextStreamHandler;
import com.hjq.CodeLess.core.handler.StreamHandlerExecutor;
import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.model.entity.App;
import com.hjq.CodeLess.model.entity.User;
import com.hjq.CodeLess.model.enums.CodeGenTypeEnum;
import com.hjq.CodeLess.service.impl.AppServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppChatHistoryTest {
    private final AppServiceImpl service = spy(new AppServiceImpl());
    private final ChatHistoryService history = mock(ChatHistoryService.class);
    private final AiCodeGeneratorFacade generator = mock(AiCodeGeneratorFacade.class);
    private final AppCoverGeneratorService covers = mock(AppCoverGeneratorService.class);
    private final VueProjectBuilder vueBuilder = mock(VueProjectBuilder.class);
    private final AppGenerationService generation = new AppGenerationService();
    private final User owner = User.builder().id(2L).build();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "chatHistoryService", history);
        ReflectionTestUtils.setField(service, "aiCodeGeneratorFacade", generator);
        ReflectionTestUtils.setField(service, "streamHandlerExecutor",
                new StreamHandlerExecutor(new SimpleTextStreamHandler(),
                        new JsonMessageStreamHandler(vueBuilder, generation, mock(ToolManager.class))));
        ReflectionTestUtils.setField(service, "appCoverGeneratorService", covers);
        ReflectionTestUtils.setField(service, "appGenerationService", generation);
        doReturn(App.builder().id(1L).userId(2L).codeGenType("html").build()).when(service).getById(1L);
        doReturn(true).when(service).updateById(any(App.class));
        when(covers.generateCover(1L, "html")).thenReturn(Optional.empty());
    }

    @ParameterizedTest
    @EnumSource(value = CodeGenTypeEnum.class, names = {"HTML", "MULTI_FILE"})
    void savesUserThenCompleteAiResponseAndPreservesPlainTextStream(CodeGenTypeEnum codeGenType) {
        String type = codeGenType.getValue();
        doReturn(App.builder().id(1L).userId(2L).codeGenType(type).build()).when(service).getById(1L);
        when(covers.generateCover(1L, type)).thenReturn(Optional.empty());
        when(generator.generateAndSaveCodeStream("generate", codeGenType, 1L))
                .thenReturn(Flux.just("hello", " world"));
        var stream = service.chatToGenCode(1L, "generate", owner);
        verifyNoInteractions(history, generator);

        assertEquals(List.of("", "hello", " world"), stream.collectList().block(Duration.ofSeconds(5)));
        var order = inOrder(history, generator, covers);
        order.verify(history).addChatMessage(1L, "generate", "user", 2L);
        order.verify(generator).generateAndSaveCodeStream("generate", codeGenType, 1L);
        order.verify(history).addChatMessage(1L, "hello world", "ai", 2L);
        order.verify(covers).generateCover(1L, type);
        verifyNoMoreInteractions(history);
        verifyNoInteractions(vueBuilder);
    }

    @Test
    void forwardsVueToolEventsAndStartsBuildAfterSavingAiTextAndToolResults() throws Exception {
        doReturn(App.builder().id(1L).userId(2L).codeGenType("vue_project").build()).when(service).getById(1L);
        when(covers.generateCover(1L, "vue_project")).thenReturn(Optional.empty());
        var completion = Sinks.<String>empty();
        var build = new CompletableFuture<Boolean>();
        String projectPath = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_1").toString();
        when(vueBuilder.buildProjectAsync(projectPath)).thenReturn(build);
        var events = List.of(
                "{\"type\":\"ai_response\",\"data\":\"已生成\"}",
                "{\"type\":\"tool_request\",\"name\":\"writeFile\",\"display\":\"[选择工具] 写入文件\"}",
                "{\"type\":\"tool_executed\",\"result\":\"文件写入成功\","
                        + "\"display\":\"\\n\\n[工具调用] 写入文件 src/App.vue\\n\\n\"}",
                "{\"type\":\"ai_response\",\"data\":\"项目。\"}"
        );
        when(generator.generateAndSaveCodeStream("generate", CodeGenTypeEnum.VUE_PROJECT, 1L))
                .thenReturn(Flux.fromIterable(events).concatWith(completion.asMono()));

        var response = service.chatToGenCode(1L, "generate", owner).collectList().toFuture();
        verifyNoInteractions(vueBuilder);
        completion.emitEmpty(Sinks.EmitFailureHandler.FAIL_FAST);
        var received = response.get(5, TimeUnit.SECONDS);

        assertFalse(build.isDone());
        assertThrows(BusinessException.class, () -> generation.withIdleApp(1L, () -> true));
        build.complete(false);
        assertTrue(generation.withIdleApp(1L, () -> true));

        assertEquals("", received.get(0));
        assertEquals(events, received.subList(1, received.size()));
        var order = inOrder(history, vueBuilder);
        order.verify(history).addChatMessage(1L, "generate", "user", 2L);
        order.verify(history).addChatMessage(1L, "已生成\n\n[工具调用] 写入文件 src/App.vue\n\n项目。", "ai", 2L);
        order.verify(vueBuilder).buildProjectAsync(projectPath);
        verifyNoMoreInteractions(history);
    }

    @Test
    void doesNotBuildVueProjectWhenSourceSavingFails() {
        doReturn(App.builder().id(1L).userId(2L).codeGenType("vue_project").build()).when(service).getById(1L);
        var failure = new IllegalStateException("save failed");
        when(generator.generateAndSaveCodeStream("generate", CodeGenTypeEnum.VUE_PROJECT, 1L))
                .thenReturn(Flux.error(failure));

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.chatToGenCode(1L, "generate", owner).blockLast(Duration.ofSeconds(5))));
        verifyNoInteractions(vueBuilder);
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
        doReturn(App.builder().id(1L).userId(2L).codeGenType("vue_project").build()).when(service).getById(1L);
        var source = Sinks.many().unicast().<String>onBackpressureBuffer();
        when(generator.generateAndSaveCodeStream("generate", CodeGenTypeEnum.VUE_PROJECT, 1L)).thenReturn(source.asFlux());
        var error = new AtomicReference<Throwable>();
        var subscription = service.chatToGenCode(1L, "generate", owner).subscribe(ignored -> {}, error::set);
        source.tryEmitNext("{\"type\":\"ai_response\",\"data\":\"partial\"}");
        assertTrue(service.stopGeneration(1L, owner));
        assertInstanceOf(CancellationException.class, error.get());
        verify(history).addChatMessage(1L, "generate", "user", 2L);
        verifyNoMoreInteractions(history);
        verifyNoInteractions(covers);
        verifyNoInteractions(vueBuilder);
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

    @Test
    void vueGenerationSavesReplyAndStartsBuildAfterSubscriberDisconnects() {
        doReturn(App.builder().id(1L).userId(2L).codeGenType("vue_project").build()).when(service).getById(1L);
        when(covers.generateCover(1L, "vue_project")).thenReturn(Optional.empty());
        when(vueBuilder.buildProjectAsync(anyString())).thenReturn(CompletableFuture.completedFuture(true));
        var model = Sinks.many().unicast().<String>onBackpressureBuffer();
        when(generator.generateAndSaveCodeStream("generate", CodeGenTypeEnum.VUE_PROJECT, 1L))
                .thenReturn(model.asFlux());

        var connection = service.chatToGenCode(1L, "generate", owner).subscribe();
        model.emitNext("{\"type\":\"ai_response\",\"data\":\"计划。\"}", Sinks.EmitFailureHandler.FAIL_FAST);
        connection.dispose();

        // 模拟浏览器断开期间，大量工具参数分片仍在输出。
        for (int index = 0; index < 300; index++) {
            assertEquals(Sinks.EmitResult.OK, model.tryEmitNext("{\"type\":\"tool_request\"}"));
        }
        model.emitNext("{\"type\":\"ai_response\",\"data\":\"已完成。\"}", Sinks.EmitFailureHandler.FAIL_FAST);
        model.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST);

        verify(history).addChatMessage(1L, "计划。已完成。", "ai", 2L);
        verify(vueBuilder).buildProjectAsync(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_1").toString());
        verify(service, timeout(2000)).updateById(argThat(app -> "completed".equals(app.getGenerationStatus())));
    }

    @Test
    void successfulToolOnlyVueGenerationSavesCompletionForPageReload() {
        doReturn(App.builder().id(1L).userId(2L).codeGenType("vue_project").build()).when(service).getById(1L);
        when(covers.generateCover(1L, "vue_project")).thenReturn(Optional.empty());
        when(vueBuilder.buildProjectAsync(anyString())).thenReturn(CompletableFuture.completedFuture(true));
        when(generator.generateAndSaveCodeStream("generate", CodeGenTypeEnum.VUE_PROJECT, 1L))
                .thenReturn(Flux.just("{\"type\":\"tool_executed\"}"));

        service.chatToGenCode(1L, "generate", owner).blockLast(Duration.ofSeconds(5));

        verify(history).addChatMessage(1L, "网站代码已生成，构建完成后可刷新预览。", "ai", 2L);
    }
}
