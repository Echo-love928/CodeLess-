package com.hjq.CodeLess.ai;

import com.hjq.CodeLess.ai.tools.FileWriteTool;
import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.exception.ErrorCode;
import com.hjq.CodeLess.model.enums.CodeGenTypeEnum;
import com.hjq.CodeLess.service.ChatHistoryService;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiCodeGeneratorServiceFactory {

    @Resource
    private ChatModel chatModel;

    @Resource(name = "openAiStreamingChatModel")
    private StreamingChatModel streamingChatModel;

    @Resource(name = "reasoningStreamingChatModel")
    private StreamingChatModel reasoningStreamingChatModel;

    @Resource
    private RedisChatMemoryStore redisChatMemoryStore;

    @Resource
    private ChatHistoryService chatHistoryService;

    @Bean
    public AiCodeGeneratorService aiCodeGeneratorService() {
        return buildDefaultService(appChatMemoryProvider());
    }

    public AiCodeGeneratorService createAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenType,
                                                               FileWriteTool fileWriteTool) {
        if (codeGenType == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }
        var chatMemory = appChatMemoryProvider().get(appId);
        ChatMemoryProvider memoryProvider = memoryId -> {
            if (!Long.valueOf(appId).equals(memoryId)) {
                throw new IllegalArgumentException("对话记忆与应用 ID 不匹配");
            }
            return chatMemory;
        };
        return switch (codeGenType) {
            case VUE_PROJECT -> AiServices.builder(AiCodeGeneratorService.class)
                    .streamingChatModel(reasoningStreamingChatModel)
                    .chatMemoryProvider(memoryProvider)
                    .tools(fileWriteTool)
                    .hallucinatedToolNameStrategy(request -> ToolExecutionResultMessage.from(
                            request, "Error: there is no tool called " + request.name()))
                    .build();
            case HTML, MULTI_FILE -> buildDefaultService(memoryProvider);
        };
    }

    private AiCodeGeneratorService buildDefaultService(ChatMemoryProvider memoryProvider) {
        return AiServices.builder(AiCodeGeneratorService.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(memoryProvider)
                .build();
    }

    private ChatMemoryProvider appChatMemoryProvider() {
        var memoryStore = new AppChatMemoryStore(redisChatMemoryStore, chatHistoryService);
        return appId -> MessageWindowChatMemory.builder()
                .id(appId)
                .chatMemoryStore(memoryStore)
                .maxMessages(AppChatMemoryStore.MAX_MESSAGES)
                .build();
    }

    @Bean
    public AiAppNameGeneratorService aiAppNameGeneratorService() {
        return AiServices.builder(AiAppNameGeneratorService.class)
                .chatModel(chatModel)
                .build();
    }
}

