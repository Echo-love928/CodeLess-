package com.hjq.CodeLess.ai;

import com.hjq.CodeLess.ai.tools.FileWriteTool;
import com.hjq.CodeLess.ai.tools.ToolManager;
import com.hjq.CodeLess.ai.tools.VueProjectWorkspace;
import com.hjq.CodeLess.model.enums.CodeGenTypeEnum;
import com.hjq.CodeLess.service.ChatHistoryService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用真实 AiServices 代理和消息窗口，只替换外部模型及 Redis I/O。 */
class AiCodeGeneratorMemoryTest {
    private static final Long APP_A = 1990000000000000001L;
    private static final Long APP_B = 1990000000000000002L;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String JSON_REPLY = """
            {"htmlCode":"<h1>ok</h1>","description":"generated"}
            """;
    private final Map<Object, List<ChatMessage>> storedMessages = new HashMap<>();
    private final List<ChatRequest> requests = new ArrayList<>();
    private RedisChatMemoryStore store;
    private AiCodeGeneratorServiceFactory factory;
    private AiCodeGeneratorService service;

    @BeforeEach
    void setUp() {
        store = mock(RedisChatMemoryStore.class);
        when(store.getMessages(any())).thenAnswer(call ->
                new ArrayList<>(storedMessages.getOrDefault(call.getArgument(0), List.of())));
        doAnswer(call -> {
            storedMessages.put(call.getArgument(0), new ArrayList<>(call.<List<ChatMessage>>getArgument(1)));
            return null;
        }).when(store).updateMessages(any(), anyList());
        factory = newFactory();
        service = factory.aiCodeGeneratorService();
    }

    private AiCodeGeneratorServiceFactory newFactory() {
        var result = new AiCodeGeneratorServiceFactory();
        ReflectionTestUtils.setField(result, "redisChatMemoryStore", store);
        ReflectionTestUtils.setField(result, "chatHistoryService", mock(ChatHistoryService.class));
        ReflectionTestUtils.setField(result, "chatModel", new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                requests.add(request);
                return ChatResponse.builder().aiMessage(AiMessage.from(JSON_REPLY)).build();
            }
        });
        ReflectionTestUtils.setField(result, "streamingChatModel", new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                requests.add(request);
                String text = "```html\n<h1>ok</h1>\n```";
                handler.onPartialResponse(text);
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(text)).build());
            }
        });
        return result;
    }

    @ParameterizedTest
    @EnumSource(EntryPoint.class)
    void allGenerationMethodsRememberTheirOwnAppAndIsolateOtherApps(EntryPoint entryPoint) {
        entryPoint.invoke(service, APP_A, "APP_A_ORIGINAL");
        entryPoint.invoke(service, APP_B, "APP_B_ORIGINAL");
        entryPoint.invoke(service, APP_A, "APP_A_FOLLOWUP");

        assertEquals(3, requests.size());
        assertContains(userTexts(requests.get(0)), "APP_A_ORIGINAL");
        assertNotContains(userTexts(requests.get(1)), "APP_A_ORIGINAL");
        assertContains(userTexts(requests.get(1)), "APP_B_ORIGINAL");
        assertContains(userTexts(requests.get(2)), "APP_A_ORIGINAL");
        assertContains(userTexts(requests.get(2)), "APP_A_FOLLOWUP");
        assertNotContains(userTexts(requests.get(2)), "APP_B_ORIGINAL");
        assertTrue(requests.get(2).messages().stream().anyMatch(AiMessage.class::isInstance));
        assertEquals(java.util.Set.of(APP_A, APP_B), storedMessages.keySet());
        verify(store, never()).updateMessages(eq("default"), anyList());
    }

    @Test
    void vueUsesTheReasoningModelAndExecutesFileTools(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("obsolete.txt"), "old");
        var batches = List.of(
                List.of(toolRequest("unknownTool", "{}"),
                        toolRequest("writeFile", """
                                {"relativeFilePath":"src/App.vue","content":"<template>你好</template>"}
                                """)),
                List.of(toolRequest("readDir", """
                                {"relativeDirPath":""}
                                """),
                        toolRequest("readFile", """
                                {"relativeFilePath":"src/App.vue"}
                                """)),
                List.of(toolRequest("modifyFile", """
                                {"relativeFilePath":"src/App.vue","oldContent":"你好","newContent":"欢迎"}
                                """),
                        toolRequest("deleteFile", """
                                {"relativeFilePath":"obsolete.txt"}
                                """))
        );
        ReflectionTestUtils.setField(factory, "reasoningStreamingChatModel", new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                int round = requests.size();
                requests.add(request);
                var reply = round < batches.size() ? AiMessage.from(batches.get(round)) : AiMessage.from("done");
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(reply).build());
            }
        });
        try (var context = new AnnotationConfigApplicationContext("com.hjq.CodeLess.ai.tools")) {
            var manager = context.getBean(ToolManager.class);
            ReflectionTestUtils.setField(factory, "toolManager", manager);
            var vue = factory.createAiCodeGeneratorService(APP_A, CodeGenTypeEnum.VUE_PROJECT, new VueProjectWorkspace(project));
            EntryPoint.consume(vue.generateVueProjectCodeStream(APP_A, "Vue project"));

            Path secondProject = Files.createDirectory(project.resolve("another-project"));
            var writer = assertInstanceOf(FileWriteTool.class,
                    manager.getTool("writeFile", new VueProjectWorkspace(secondProject)));
            assertEquals("文件写入成功: src/App.vue", writer.writeFile("src/App.vue", "<template>另一个工程</template>"));
            assertEquals("<template>另一个工程</template>", Files.readString(secondProject.resolve("src/App.vue")));
        }

        assertEquals(4, requests.size());
        assertEquals(java.util.Set.of("writeFile", "readFile", "modifyFile", "readDir", "deleteFile"),
                requests.get(0).toolSpecifications().stream().map(spec -> spec.name())
                        .collect(java.util.stream.Collectors.toSet()));
        assertTrue(requests.get(1).messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .anyMatch(message -> message.text().equals("Error: there is no tool called unknownTool")));
        var readResults = requests.get(2).messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .collect(java.util.stream.Collectors.toMap(ToolExecutionResultMessage::id,
                        ToolExecutionResultMessage::text));
        assertTrue(readResults.get("readDir").contains("src/App.vue"));
        assertEquals("<template>你好</template>", readResults.get("readFile"));
        assertEquals("<template>欢迎</template>", Files.readString(project.resolve("src/App.vue")));
        assertFalse(Files.exists(project.resolve("obsolete.txt")));
        assertEquals(java.util.Set.of(APP_A), storedMessages.keySet());
    }

    private ToolExecutionRequest toolRequest(String name, String arguments) {
        return ToolExecutionRequest.builder().id(name).name(name).arguments(arguments).build();
    }

    @Test
    void aNewServiceInstanceRestoresMessagesFromTheSharedStore() {
        service.generateHtmlCode(APP_A, "PERSISTED_REQUEST");
        var recreatedService = newFactory().aiCodeGeneratorService();
        recreatedService.generateHtmlCode(APP_A, "AFTER_RESTART");

        assertContains(userTexts(requests.get(1)), "PERSISTED_REQUEST");
        assertContains(userTexts(requests.get(1)), "AFTER_RESTART");
        assertTrue(requests.get(1).messages().stream().anyMatch(AiMessage.class::isInstance));
    }

    @Test
    void synchronousAndStreamingCallsShareMemoryForTheSameApp() {
        service.generateHtmlCode(APP_A, "SYNCHRONOUS_REQUEST");
        service.generateHtmlCodeStream(APP_A, "STREAMING_FOLLOWUP").blockLast(TIMEOUT);

        assertContains(userTexts(requests.get(1)), "SYNCHRONOUS_REQUEST");
        assertContains(userTexts(requests.get(1)), "STREAMING_FOLLOWUP");
        assertEquals(1, requests.get(1).messages().stream().filter(SystemMessage.class::isInstance).count());
    }

    @Test
    void messageWindowKeepsAtMostTwentyMessagesIncludingTheSystemPrompt() {
        for (int index = 0; index < 14; index++) {
            service.generateHtmlCode(APP_A, "REQUEST_" + index + "_END");
            assertTrue(storedMessages.get(APP_A).size() <= 20);
        }
        List<ChatMessage> messages = storedMessages.get(APP_A);
        assertEquals(20, messages.size());
        assertInstanceOf(SystemMessage.class, messages.get(0));
        assertNotContains(userTexts(messages), "REQUEST_0_END");
        assertContains(userTexts(messages), "REQUEST_13_END");

        service.generateHtmlCode(APP_A, "NEXT_REQUEST");
        assertNotContains(userTexts(requests.get(14)), "REQUEST_0_END");
        assertContains(userTexts(requests.get(14)), "REQUEST_13_END");
    }

    @Test
    void namingIsStatelessAndDoesNotPolluteCodeGenerationMemory() {
        var naming = factory.aiAppNameGeneratorService();
        naming.generateAppName("FIRST_NAME_PROMPT");
        naming.generateAppName("SECOND_NAME_PROMPT");

        assertEquals(1, userTexts(requests.get(1)).size());
        assertNotContains(userTexts(requests.get(1)), "FIRST_NAME_PROMPT");
        assertTrue(storedMessages.isEmpty());
        verifyNoInteractions(store);

        service.generateHtmlCode(APP_A, "CODE_PROMPT");
        assertNotContains(userTexts(requests.get(2)), "NAME_PROMPT");
        assertEquals(java.util.Set.of(APP_A), storedMessages.keySet());
    }

    private static List<String> userTexts(ChatRequest request) {
        return userTexts(request.messages());
    }

    private static List<String> userTexts(List<ChatMessage> messages) {
        return messages.stream().filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast).map(UserMessage::singleText).toList();
    }

    private static void assertContains(List<String> texts, String value) {
        assertTrue(texts.stream().anyMatch(text -> text.contains(value)), () -> "Missing message: " + value);
    }

    private static void assertNotContains(List<String> texts, String value) {
        assertTrue(texts.stream().noneMatch(text -> text.contains(value)), () -> "Unexpected message: " + value);
    }

    enum EntryPoint {
        HTML_SYNC, MULTI_FILE_SYNC, HTML_TOKEN, MULTI_FILE_TOKEN, HTML_FLUX, MULTI_FILE_FLUX;

        void invoke(AiCodeGeneratorService ai, Long appId, String prompt) {
            switch (this) {
                case HTML_SYNC -> assertNotNull(ai.generateHtmlCode(appId, prompt));
                case MULTI_FILE_SYNC -> assertNotNull(ai.generateMultiFileCode(appId, prompt));
                case HTML_TOKEN -> consume(ai.generateHtmlCodeTokenStream(appId, prompt));
                case MULTI_FILE_TOKEN -> consume(ai.generateMultiFileCodeTokenStream(appId, prompt));
                case HTML_FLUX -> assertFalse(ai.generateHtmlCodeStream(appId, prompt).collectList().block(TIMEOUT).isEmpty());
                case MULTI_FILE_FLUX -> assertFalse(ai.generateMultiFileCodeStream(appId, prompt).collectList().block(TIMEOUT).isEmpty());
            }
        }

        private static void consume(dev.langchain4j.service.TokenStream tokens) {
            var completion = new java.util.concurrent.CompletableFuture<ChatResponse>();
            tokens.onPartialResponse(ignored -> {})
                    .onCompleteResponse(completion::complete)
                    .onError(completion::completeExceptionally)
                    .start();
            assertNotNull(completion.orTimeout(5, java.util.concurrent.TimeUnit.SECONDS).join());
        }
    }
}
