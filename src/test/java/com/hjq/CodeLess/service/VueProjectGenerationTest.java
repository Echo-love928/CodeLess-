package com.hjq.CodeLess.service;

import cn.hutool.json.JSONUtil;
import com.hjq.CodeLess.ai.AiCodeGeneratorService;
import com.hjq.CodeLess.ai.AiCodeGeneratorServiceFactory;
import com.hjq.CodeLess.ai.tools.ToolManager;
import com.hjq.CodeLess.ai.tools.VueProjectWorkspace;
import com.hjq.CodeLess.core.AiCodeGeneratorFacade;
import com.hjq.CodeLess.core.builder.VueProjectBuilder;
import com.hjq.CodeLess.core.handler.JsonMessageStreamHandler;
import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.mapper.AppVersionRepository;
import com.hjq.CodeLess.model.entity.App;
import com.hjq.CodeLess.model.enums.CodeGenTypeEnum;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VueProjectGenerationTest {
    @Test
    void stalledVueToolStreamCancelsModelBeforeSavingVersion(@TempDir Path tempDir) {
        long appId = 1L;
        var type = CodeGenTypeEnum.VUE_PROJECT;
        var facade = new AiCodeGeneratorFacade();
        var factory = mock(AiCodeGeneratorServiceFactory.class);
        var versions = mock(AppVersionService.class);
        var builder = mock(VueProjectBuilder.class);
        var service = mock(AiCodeGeneratorService.class);
        var tokenStream = mock(TokenStream.class, RETURNS_SELF);
        var handle = mock(StreamingHandle.class);
        var callback = new AtomicReference<BiConsumer<PartialToolCall, PartialToolCallContext>>();
        when(tokenStream.onPartialToolCallWithContext(any())).thenAnswer(invocation -> {
            callback.set(invocation.getArgument(0));
            return tokenStream;
        });
        doAnswer(invocation -> {
            var fragment = PartialToolCall.builder().name("writeFile").partialArguments("{").build();
            callback.get().accept(fragment, new PartialToolCallContext(handle));
            return null;
        }).when(tokenStream).start();
        when(factory.createAiCodeGeneratorService(eq(appId), eq(type), any())).thenReturn(service);
        when(versions.generationPrompt(eq(appId), eq("prompt"), any(Path.class))).thenReturn("prompt");
        when(service.generateVueProjectCodeStream(appId, "prompt")).thenReturn(tokenStream);
        ReflectionTestUtils.setField(facade, "aiCodeGeneratorServiceFactory", factory);
        ReflectionTestUtils.setField(facade, "appVersionService", versions);
        ReflectionTestUtils.setField(facade, "appGenerationService", new AppGenerationService());
        ReflectionTestUtils.setField(facade, "vueProjectBuilder", builder);
        ReflectionTestUtils.setField(facade, "jsonMessageStreamHandler",
                new JsonMessageStreamHandler(mock(ToolManager.class)));
        ReflectionTestUtils.setField(facade, "appVersionStorage",
                new AppVersionStorage(tempDir.resolve("output"), tempDir.resolve("versions")));
        ReflectionTestUtils.setField(facade, "vueStreamIdleTimeout", Duration.ofMillis(150));

        var error = assertThrows(BusinessException.class, () -> facade
                .generateAndSaveCodeStream("prompt", type, appId).blockLast(Duration.ofSeconds(5)));

        assertEquals("Vue 生成超时，模型长时间没有输出，请重试", error.getMessage());
        verify(handle, timeout(2000)).cancel();
        verify(versions).generationPrompt(eq(appId), eq("prompt"), any(Path.class));
        verifyNoMoreInteractions(versions);
        verifyNoInteractions(builder);
    }

    @ParameterizedTest
    @CsvSource({"false, true", "true, true", "false, false"})
    void savesVueProjectThroughFileToolsAndWaitsForBuild(boolean recoverTruncatedCall, boolean buildSucceeds,
                                                         @TempDir Path tempDir) throws Exception {
        long appId = 1L;
        var type = CodeGenTypeEnum.VUE_PROJECT;
        String prompt = "创建一个 Vue 首页";
        String completion = "Vue 工程生成完毕";
        var expectedFiles = Map.of(
                "index.html", "<!doctype html><div id=\"app\"></div>",
                "package.json", "{\"name\":\"vue-test\",\"private\":true}",
                "src/App.vue", "<template><h1>你好，Vue</h1></template>"
        );
        var truncatedCall = ToolExecutionRequest.builder().id("truncated").name("writeFile")
                .arguments("{\"relativeFilePath\":\"src/App.vue\",\"content\":\"unfinished").build();
        var orphanedCall = ToolExecutionRequest.builder().id("previous-attempt").name("writeFile")
                .arguments(truncatedCall.arguments()).build();
        int toolRound = recoverTruncatedCall ? 2 : 1;
        List<ChatRequest> modelRequests = new ArrayList<>();
        var model = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                modelRequests.add(request);
                if (recoverTruncatedCall && modelRequests.size() == 1) {
                    handler.onCompleteResponse(ChatResponse.builder().finishReason(FinishReason.LENGTH)
                            .aiMessage(AiMessage.from(List.of(truncatedCall))).build());
                    return;
                }
                if (modelRequests.size() == toolRound) {
                    var tools = expectedFiles.entrySet().stream()
                            .map(file -> ToolExecutionRequest.builder()
                                    .id(file.getKey())
                                    .name("writeFile")
                                    .arguments(JSONUtil.toJsonStr(Map.of(
                                            "relativeFilePath", file.getKey(), "content", file.getValue())))
                                    .build())
                            .toList();
                    var toolContext = new PartialToolCallContext(mock(StreamingHandle.class));
                    for (int index = 0; index < tools.size(); index++) {
                        var tool = tools.get(index);
                        handler.onPartialToolCall(PartialToolCall.builder()
                                .index(index).id(tool.id()).name(tool.name()).partialArguments("{").build(), toolContext);
                        handler.onPartialToolCall(PartialToolCall.builder()
                                .index(index).id(tool.id()).name(tool.name()).partialArguments("\"").build(), toolContext);
                    }
                    handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(tools)).build());
                    return;
                }
                handler.onPartialResponse(new PartialResponse(completion),
                        new PartialResponseContext(mock(StreamingHandle.class)));
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(completion)).build());
            }
        };

        var redis = mock(RedisChatMemoryStore.class);
        List<ChatMessage> previousAttempt = recoverTruncatedCall ? List.of(AiMessage.from(List.of(orphanedCall))) : List.of();
        var memory = new AtomicReference<>(previousAttempt);
        when(redis.getMessages(appId)).thenAnswer(call -> new ArrayList<>(memory.get()));
        doAnswer(call -> {
            memory.set(List.copyOf(call.<List<ChatMessage>>getArgument(1)));
            return null;
        }).when(redis).updateMessages(eq(appId), anyList());
        var factory = new AiCodeGeneratorServiceFactory();
        ObjectFactory<StreamingChatModel> reasoningModelFactory = () -> model;
        ReflectionTestUtils.setField(factory, "reasoningStreamingChatModelFactory", reasoningModelFactory);
        ReflectionTestUtils.setField(factory, "redisChatMemoryStore", redis);
        ReflectionTestUtils.setField(factory, "chatHistoryService", mock(ChatHistoryService.class));

        Path outputRoot = tempDir.resolve("output");
        var storage = new AppVersionStorage(outputRoot, tempDir.resolve("versions"));
        var repository = mock(AppVersionRepository.class);
        var app = App.builder().id(appId).codeGenType(type.getValue()).version(0).build();
        when(repository.findApp(eq(appId), anyBoolean())).thenReturn(app);
        when(repository.nextVersion(appId)).thenReturn(1);
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var generation = new AppGenerationService();
        var versions = new AppVersionService(repository, storage, generation, transactions);
        var builder = mock(VueProjectBuilder.class);
        Path activeDirectory = outputRoot.resolve(type.getValue() + "_" + appId);
        var completed = new AtomicBoolean();
        when(builder.buildProject(activeDirectory.toString())).thenAnswer(call -> {
            assertFalse(completed.get());
            verify(repository).setCurrent(appId, 1, false);
            for (var file : expectedFiles.entrySet()) {
                assertEquals(file.getValue(), Files.readString(activeDirectory.resolve(file.getKey())), file.getKey());
            }
            if (buildSucceeds) {
                Files.createDirectory(activeDirectory.resolve("dist"));
                Files.writeString(activeDirectory.resolve("dist/index.html"), "preview");
            }
            return buildSucceeds;
        });
        var facade = new AiCodeGeneratorFacade();
        ReflectionTestUtils.setField(facade, "aiCodeGeneratorServiceFactory", factory);
        ReflectionTestUtils.setField(facade, "appVersionService", versions);
        ReflectionTestUtils.setField(facade, "appVersionStorage", storage);
        ReflectionTestUtils.setField(facade, "appGenerationService", generation);
        ReflectionTestUtils.setField(facade, "vueProjectBuilder", builder);

        List<String> messages = new ArrayList<>();
        try (var context = new AnnotationConfigApplicationContext("com.hjq.CodeLess.ai.tools")) {
            var toolManager = context.getBean(ToolManager.class);
            ReflectionTestUtils.setField(factory, "toolManager", toolManager);
            ReflectionTestUtils.setField(facade, "jsonMessageStreamHandler", new JsonMessageStreamHandler(toolManager));
            var stream = generation.generate(appId, () -> facade.generateAndSaveCodeStream(prompt, type, appId),
                            ignored -> {})
                    .doOnNext(messages::add)
                    .doOnComplete(() -> completed.set(true));
            if (buildSucceeds) {
                stream.blockLast(Duration.ofSeconds(5));
                assertTrue(completed.get());
                assertEquals("preview", Files.readString(activeDirectory.resolve("dist/index.html")));
            } else {
                var error = assertThrows(BusinessException.class, () -> stream.blockLast(Duration.ofSeconds(5)));
                assertEquals("Vue 项目构建失败，无法预览", error.getMessage());
                assertFalse(completed.get());
            }
        }
        verify(builder).buildProject(activeDirectory.toString());

        var events = messages.stream().map(JSONUtil::parseObj).toList();
        if (recoverTruncatedCall) {
            assertTrue(modelRequests.getFirst().messages().stream().noneMatch(
                    message -> message instanceof AiMessage ai && ai.toolExecutionRequests().contains(orphanedCall)));
            var retryResult = modelRequests.get(1).messages().stream()
                    .filter(ToolExecutionResultMessage.class::isInstance)
                    .map(ToolExecutionResultMessage.class::cast)
                    .filter(result -> result.id().equals(truncatedCall.id()))
                    .findFirst().orElseThrow();
            assertTrue(retryResult.text().contains("重新调用 writeFile"));
            assertEquals("tool_executed", events.getFirst().getStr("type"));
            assertEquals(retryResult.text(), events.getFirst().getStr("result"));
            events = events.subList(1, events.size());
        }
        int toolCount = expectedFiles.size();
        assertEquals(toolCount * 2 + 1, events.size());
        var requests = events.subList(0, toolCount);
        var requestedPaths = requests.stream().map(event -> event.getStr("id")).collect(Collectors.toSet());
        assertEquals(expectedFiles.keySet(), requestedPaths);
        for (var event : requests) {
            assertEquals("tool_request", event.getStr("type"));
            assertEquals("writeFile", event.getStr("name"));
            assertEquals("{", event.getStr("arguments"));
            assertEquals("[选择工具] 写入文件", event.getStr("display").strip());
        }
        for (var event : events.subList(toolCount, toolCount * 2)) {
            assertEquals("tool_executed", event.getStr("type"));
            assertEquals("writeFile", event.getStr("name"));
            assertEquals("文件写入成功: " + event.getStr("id"), event.getStr("result"));
            var arguments = JSONUtil.parseObj(event.getStr("arguments"));
            assertEquals(expectedFiles.get(event.getStr("id")), arguments.getStr("content"));
            assertTrue(event.getStr("display").contains("[工具调用] 写入文件 " + event.getStr("id")));
            assertTrue(event.getStr("display").contains(arguments.getStr("content")));
        }
        var response = events.get(toolCount * 2);
        assertEquals("ai_response", response.getStr("type"));
        assertEquals(completion, response.getStr("data"));
        assertEquals(toolRound + 1, modelRequests.size());
        assertTrue(modelRequests.get(0).toolSpecifications().stream()
                .anyMatch(tool -> tool.name().equals("writeFile")));
        var toolResults = modelRequests.get(toolRound).messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .filter(result -> !result.id().equals(truncatedCall.id()))
                .map(ToolExecutionResultMessage::text)
                .sorted()
                .toList();
        assertEquals(expectedFiles.keySet().stream().map(path -> "文件写入成功: " + path).sorted().toList(), toolResults);
        assertEquals(expectedFiles, storage.readFiles(appId, type.getValue(), 1));
        verify(repository).insert(appId, 1, null, prompt, "generated");
        verify(repository).setCurrent(appId, 1, false);
    }

    @Test
    void formatsToolResultsWithoutBreakingTheStreamOrOverflowingChatHistory(@TempDir Path tempDir) {
        try (var context = new AnnotationConfigApplicationContext("com.hjq.CodeLess.ai.tools")) {
            var handler = new JsonMessageStreamHandler(context.getBean(ToolManager.class));
            String fencedSource = "```md\n示例\n```";
            var chunks = Flux.just(
                    executedTool("missingTool", "{}", "unknown"),
                    executedTool("writeFile", "{", "参数缺失"),
                    executedTool("writeFile", "{}", "文件写入失败：路径无效"),
                    executedTool("writeFile", JSONUtil.toJsonStr(Map.of(
                            "relativeFilePath", "example.md", "content", fencedSource)), "文件写入成功: example.md"),
                    executedTool("writeFile", JSONUtil.toJsonStr(Map.of(
                            "relativeFilePath", "large.vue", "content", "中".repeat(24000))), "文件写入成功: large.vue"),
                    "{\"type\":\"ai_response\",\"data\":\"完成\"}"
            );
            var events = handler.formatToolEvents(chunks, new VueProjectWorkspace(tempDir)).map(JSONUtil::parseObj)
                    .collectList().block(Duration.ofSeconds(5));
            assertEquals(6, events.size());
            assertTrue(events.get(0).getStr("display").contains("未知工具"));
            assertTrue(events.get(1).getStr("display").contains("参数格式无效"));
            assertTrue(events.get(2).getStr("display").contains("未完成"));
            assertTrue(events.get(3).getStr("display").contains("````md\n" + fencedSource + "\n````"));
            assertTrue(events.get(4).getStr("display").contains("详情已省略"));
            assertTrue(events.get(4).getStr("display").getBytes(StandardCharsets.UTF_8).length < 256);
            assertEquals("完成", events.get(5).getStr("data"));
        }
    }

    private String executedTool(String name, String arguments, String result) {
        return JSONUtil.toJsonStr(Map.of("type", "tool_executed", "name", name, "arguments", arguments, "result", result));
    }
}
