package com.hjq.CodeLess.service;

import cn.hutool.json.JSONUtil;
import com.hjq.CodeLess.ai.AiCodeGeneratorServiceFactory;
import com.hjq.CodeLess.core.AiCodeGeneratorFacade;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VueProjectGenerationTest {
    @Test
    void generatesVueProjectThroughFileToolsAndSavesVersion(@TempDir Path tempDir) throws Exception {
        long appId = 1L;
        var type = CodeGenTypeEnum.VUE_PROJECT;
        String prompt = "创建一个 Vue 首页";
        String completion = "Vue 工程生成完毕";
        var expectedFiles = Map.of(
                "index.html", "<!doctype html><div id=\"app\"></div>",
                "package.json", "{\"name\":\"vue-test\",\"private\":true}",
                "src/App.vue", "<template><h1>你好，Vue</h1></template>"
        );
        List<ChatRequest> modelRequests = new ArrayList<>();
        var model = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                modelRequests.add(request);
                if (modelRequests.size() == 1) {
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
        var memory = new AtomicReference<List<ChatMessage>>(List.of());
        when(redis.getMessages(appId)).thenAnswer(call -> new ArrayList<>(memory.get()));
        doAnswer(call -> {
            memory.set(List.copyOf(call.<List<ChatMessage>>getArgument(1)));
            return null;
        }).when(redis).updateMessages(eq(appId), anyList());
        var factory = new AiCodeGeneratorServiceFactory();
        ReflectionTestUtils.setField(factory, "reasoningStreamingChatModel", model);
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
        var versions = new AppVersionService(repository, storage, new AppGenerationService(), transactions);
        var facade = new AiCodeGeneratorFacade();
        ReflectionTestUtils.setField(facade, "aiCodeGeneratorServiceFactory", factory);
        ReflectionTestUtils.setField(facade, "appVersionService", versions);
        ReflectionTestUtils.setField(facade, "appVersionStorage", storage);

        var messages = facade.generateAndSaveCodeStream(prompt, type, appId)
                .collectList().block(Duration.ofSeconds(5));

        var events = messages.stream().map(JSONUtil::parseObj).toList();
        int toolCount = expectedFiles.size();
        assertEquals(toolCount * 2 + 1, events.size());
        var requests = events.subList(0, toolCount);
        var requestedPaths = requests.stream().map(event -> event.getStr("id")).collect(Collectors.toSet());
        assertEquals(expectedFiles.keySet(), requestedPaths);
        for (var event : requests) {
            assertEquals("tool_request", event.getStr("type"));
            assertEquals("writeFile", event.getStr("name"));
            assertEquals("{", event.getStr("arguments"));
        }
        for (var event : events.subList(toolCount, toolCount * 2)) {
            assertEquals("tool_executed", event.getStr("type"));
            assertEquals("writeFile", event.getStr("name"));
            assertEquals("文件写入成功: " + event.getStr("id"), event.getStr("result"));
            var arguments = JSONUtil.parseObj(event.getStr("arguments"));
            assertEquals(expectedFiles.get(event.getStr("id")), arguments.getStr("content"));
        }
        var response = events.get(toolCount * 2);
        assertEquals("ai_response", response.getStr("type"));
        assertEquals(completion, response.getStr("data"));
        assertEquals(2, modelRequests.size());
        assertTrue(modelRequests.get(0).toolSpecifications().stream()
                .anyMatch(tool -> tool.name().equals("writeFile")));
        var toolResults = modelRequests.get(1).messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .map(ToolExecutionResultMessage::text)
                .sorted()
                .toList();
        assertEquals(expectedFiles.keySet().stream().map(path -> "文件写入成功: " + path).sorted().toList(), toolResults);
        assertEquals(expectedFiles, storage.readFiles(appId, type.getValue(), 1));
        Path activeDirectory = outputRoot.resolve(type.getValue() + "_" + appId);
        for (var file : expectedFiles.entrySet()) {
            assertEquals(file.getValue(), Files.readString(activeDirectory.resolve(file.getKey())), file.getKey());
        }
        verify(repository).insert(appId, 1, null, prompt, "generated");
        verify(repository).setCurrent(appId, 1, false);
    }
}
