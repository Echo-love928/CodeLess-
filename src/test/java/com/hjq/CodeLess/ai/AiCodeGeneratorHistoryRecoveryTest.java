package com.hjq.CodeLess.ai;

import com.hjq.CodeLess.model.entity.ChatHistory;
import com.hjq.CodeLess.service.ChatHistoryService;
import com.hjq.CodeLess.service.impl.ChatHistoryServiceImpl;
import com.mybatisflex.core.query.QueryWrapper;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiCodeGeneratorHistoryRecoveryTest {
    private static final Long APP_ID = 1990000000000000001L;
    private final Map<Object, List<ChatMessage>> redis = new HashMap<>();
    private final List<ChatRequest> requests = new ArrayList<>();
    private final ChatHistoryServiceImpl history = spy(new ChatHistoryServiceImpl());
    private RedisChatMemoryStore store;

    @BeforeEach
    void setUp() {
        store = mock(RedisChatMemoryStore.class);
        when(store.getMessages(any())).thenAnswer(call ->
                new ArrayList<>(redis.getOrDefault(call.getArgument(0), List.of())));
        doAnswer(call -> {
            redis.put(call.getArgument(0), new ArrayList<>(call.<List<ChatMessage>>getArgument(1)));
            return null;
        }).when(store).updateMessages(any(), anyList());
        doAnswer(call -> { redis.remove(call.getArgument(0)); return null; }).when(store).deleteMessages(any());
    }

    private AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean("chatModel", ChatModel.class, () -> new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                requests.add(request);
                return ChatResponse.builder().aiMessage(AiMessage.from("{\"htmlCode\":\"ok\",\"description\":\"ok\"}")).build();
            }
        });
        context.registerBean("openAiStreamingChatModel", StreamingChatModel.class, () -> mock(StreamingChatModel.class));
        context.registerBean("reasoningStreamingChatModel", StreamingChatModel.class, () -> mock(StreamingChatModel.class));
        context.registerBean("redisChatMemoryStore", RedisChatMemoryStore.class, () -> store);
        context.getBeanFactory().registerSingleton("chatHistoryService", history);
        context.register(AiCodeGeneratorServiceFactory.class);
        context.refresh();
        return context;
    }

    @Test
    void coldHistoryIsLoadedBeforeTheCurrentUserMessageExactlyOnce() {
        doReturn(List.of(record(3, "user", "CURRENT"), record(2, "ai", "OLD_REPLY"), record(1, "user", "OLD_PROMPT")))
                .when(history).list(any(QueryWrapper.class));
        try (var context = context()) {
            context.getBean(AiCodeGeneratorService.class).generateHtmlCode(APP_ID, "CURRENT");
            assertEquals(List.of("OLD_PROMPT", "CURRENT"), userTexts(requests.get(0)));
            assertTrue(requests.get(0).messages().stream().anyMatch(message -> message.equals(AiMessage.from("OLD_REPLY"))));
            assertEquals(5, redis.get(APP_ID).size());
            verify(history, times(1)).loadChatHistoryToMemory(eq(APP_ID), any(), eq(20));
        }
    }

    @Test
    void warmRedisIsUsedWithoutClearingOrReloadingDatabaseHistory() {
        redis.put(APP_ID, List.of(UserMessage.from("CACHED_PROMPT"), AiMessage.from("CACHED_REPLY")));
        try (var context = context()) {
            context.getBean(AiCodeGeneratorService.class).generateHtmlCode(APP_ID, "CURRENT");
            assertEquals(List.of("CACHED_PROMPT", "CURRENT"), userTexts(requests.get(0)));
            verifyNoInteractions(history);
            verify(store, never()).deleteMessages(any());
        }
    }

    @Test
    void redisEvictionRecoversHistoryEvenWhenTheAiServiceStillCachesTheMemoryInstance() {
        doReturn(List.of(record(1, "user", "FIRST"))).when(history).list(any(QueryWrapper.class));
        try (var context = context()) {
            var ai = context.getBean(AiCodeGeneratorService.class);
            ai.generateHtmlCode(APP_ID, "FIRST");
            redis.remove(APP_ID);
            doReturn(List.of(record(3, "user", "SECOND"), record(2, "ai", "FIRST_REPLY"), record(1, "user", "FIRST")))
                    .when(history).list(any(QueryWrapper.class));
            ai.generateHtmlCode(APP_ID, "SECOND");
            assertEquals(List.of("FIRST", "SECOND"), userTexts(requests.get(1)));
            assertEquals(5, redis.get(APP_ID).size());
        }
    }

    @Test
    void databaseFailureDoesNotSilentlyGenerateWithoutTheOldContext() {
        doThrow(new IllegalStateException("database unavailable")).when(history).list(any(QueryWrapper.class));
        try (var context = context()) {
            assertThrows(RuntimeException.class, () -> context.getBean(AiCodeGeneratorService.class).generateHtmlCode(APP_ID, "CURRENT"));
            assertTrue(requests.isEmpty());
            assertTrue(redis.isEmpty());
        }
    }

    private static ChatHistory record(long id, String type, String text) {
        return ChatHistory.builder().id(id).appId(APP_ID).messageType(type).message(text).build();
    }

    private static List<String> userTexts(ChatRequest request) {
        // 同步结构化输出会在最后一条用户消息后附加 JSON 输出说明。
        return request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                .map(UserMessage::singleText).map(text -> text.split("\\n", 2)[0]).toList();
    }
}
