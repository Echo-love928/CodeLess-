package com.hjq.CodeLess.ai;

import com.hjq.CodeLess.service.ChatHistoryService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.List;

/** Redis 未命中时从数据库恢复；也适用于同一 AI Service 实例运行期间的缓存过期。 */
public class AppChatMemoryStore implements ChatMemoryStore {
    public static final int MAX_MESSAGES = 20;

    private final ChatMemoryStore redisStore;
    private final ChatHistoryService chatHistoryService;

    public AppChatMemoryStore(ChatMemoryStore redisStore, ChatHistoryService chatHistoryService) {
        this.redisStore = redisStore;
        this.chatHistoryService = chatHistoryService;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        List<ChatMessage> messages = redisStore.getMessages(memoryId);
        if (!messages.isEmpty()) return messages;
        if (!(memoryId instanceof Long appId) || appId <= 0) {
            throw new IllegalArgumentException("对话记忆必须使用有效的应用 ID");
        }
        // 恢复时直接使用原始 Redis store，避免再次进入本层造成递归加载。
        var memory = MessageWindowChatMemory.builder()
                .id(appId)
                .maxMessages(MAX_MESSAGES)
                .chatMemoryStore(redisStore)
                .build();
        chatHistoryService.loadChatHistoryToMemory(appId, memory, MAX_MESSAGES);
        return redisStore.getMessages(memoryId);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        redisStore.updateMessages(memoryId, messages);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redisStore.deleteMessages(memoryId);
    }
}
