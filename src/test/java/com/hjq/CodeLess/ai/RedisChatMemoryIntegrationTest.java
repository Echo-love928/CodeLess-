package com.hjq.CodeLess.ai;

import com.hjq.CodeLess.model.entity.ChatHistory;
import com.hjq.CodeLess.service.impl.ChatHistoryServiceImpl;
import com.mybatisflex.core.query.QueryWrapper;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import redis.clients.jedis.JedisPooled;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 显式启用后连接真实 Redis；只读写随机前缀下的测试 key，模型与数据库查询使用测试替身。 */
@EnabledIfSystemProperty(named = "codeless.redis.integration", matches = "true")
class RedisChatMemoryIntegrationTest {
    private static final Long APP_ID = 1990000000000000001L;
    private final String prefix = "codeless:memory-integration:" + UUID.randomUUID() + ":";
    private final ChatHistoryServiceImpl history = spy(new ChatHistoryServiceImpl());
    private final List<ChatRequest> requests = new ArrayList<>();
    private RedisChatMemoryStore store;
    private JedisPooled client;
    private AiCodeGeneratorService ai;

    @BeforeEach
    void setUp() {
        store = RedisChatMemoryStore.builder().host("127.0.0.1").port(6379).prefix(prefix).ttl(3600L).build();
        client = (JedisPooled) ReflectionTestUtils.getField(store, "client");
        assertEquals("PONG", client.ping());
        var factory = new AiCodeGeneratorServiceFactory();
        ReflectionTestUtils.setField(factory, "redisChatMemoryStore", store);
        ReflectionTestUtils.setField(factory, "chatHistoryService", history);
        ReflectionTestUtils.setField(factory, "chatModel", mock(ChatModel.class));
        ReflectionTestUtils.setField(factory, "streamingChatModel", new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                requests.add(request);
                String reply = "<h1>reply " + requests.size() + "</h1>";
                handler.onPartialResponse(reply);
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(reply)).build());
            }
        });
        ai = factory.aiCodeGeneratorService();
    }

    @AfterEach
    void cleanUpOwnKey() {
        if (client != null) {
            client.del(prefix + APP_ID);
            client.close();
        }
    }

    @Test
    void aNewAppPersistsEveryUserAndAiMessageAcrossMultipleStreamingTurns() {
        doReturn(List.of(record(1, "user", "FIRST"))).when(history).list(any(QueryWrapper.class));
        chat("FIRST");
        assertRedis(1);
        chat("SECOND");
        assertRedis(2);
        chat("THIRD");
        assertRedis(3);
        assertEquals(List.of("FIRST", "SECOND", "THIRD"), userTexts(requests.get(2)));
        System.out.println("LIVE REDIS: new app, 3 streaming turns, 3 user + 3 AI + system, TTL verified");
    }

    @Test
    void anOldAppRestoresDatabaseHistoryAndRecoversAgainAfterRedisEviction() {
        doReturn(List.of(record(3, "user", "CURRENT"), record(2, "ai", "OLD_REPLY"), record(1, "user", "OLD_PROMPT")))
                .when(history).list(any(QueryWrapper.class));
        assertFalse(client.exists(prefix + APP_ID));
        chat("CURRENT");
        assertRedis(2);
        assertEquals(List.of("OLD_PROMPT", "CURRENT"), userTexts(requests.get(0)));
        assertTrue(client.get(prefix + APP_ID).contains("OLD_REPLY"));

        client.del(prefix + APP_ID);
        doReturn(List.of(record(5, "user", "AFTER_EVICTION"), record(4, "ai", "CURRENT_REPLY"),
                record(3, "user", "CURRENT"), record(2, "ai", "OLD_REPLY"), record(1, "user", "OLD_PROMPT")))
                .when(history).list(any(QueryWrapper.class));
        chat("AFTER_EVICTION");
        assertRedis(3);
        assertEquals(List.of("OLD_PROMPT", "CURRENT", "AFTER_EVICTION"), userTexts(requests.get(1)));
        System.out.println("LIVE REDIS: historical context restored, current message once, same-instance eviction recovered, TTL verified");
    }

    private void chat(String prompt) {
        var parts = ai.generateHtmlCodeStream(APP_ID, prompt).collectList().block(Duration.ofSeconds(5));
        assertNotNull(parts);
        assertFalse(parts.isEmpty());
    }

    private void assertRedis(int turns) {
        var messages = store.getMessages(APP_ID);
        assertEquals(turns, messages.stream().filter(UserMessage.class::isInstance).count());
        assertEquals(turns, messages.stream().filter(AiMessage.class::isInstance).count());
        assertEquals(turns * 2 + 1, messages.size());
        assertNotNull(client.get(prefix + APP_ID));
        assertTrue(client.ttl(prefix + APP_ID) > 0 && client.ttl(prefix + APP_ID) <= 3600);
    }

    private static ChatHistory record(long id, String type, String message) {
        return ChatHistory.builder().id(id).appId(APP_ID).messageType(type).message(message).build();
    }

    private static List<String> userTexts(ChatRequest request) {
        return request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                .map(UserMessage::singleText).toList();
    }
}
