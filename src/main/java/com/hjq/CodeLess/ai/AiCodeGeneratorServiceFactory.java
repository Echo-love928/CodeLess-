package com.hjq.CodeLess.ai;

import com.hjq.CodeLess.ai.tools.ToolManager;
import com.hjq.CodeLess.ai.tools.VueProjectWorkspace;
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
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class AiCodeGeneratorServiceFactory {
    private static final int MAX_VUE_TOOL_ARGUMENT_RETRIES = 2;

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

    @Resource
    private ToolManager toolManager;

    @Bean
    public AiCodeGeneratorService aiCodeGeneratorService() {
        return buildDefaultService(appChatMemoryProvider(false));
    }

    public AiCodeGeneratorService createAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenType,
                                                               VueProjectWorkspace workspace) {
        if (codeGenType == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }
        var chatMemory = appChatMemoryProvider(codeGenType == CodeGenTypeEnum.VUE_PROJECT).get(appId);
        ChatMemoryProvider memoryProvider = memoryId -> {
            if (!Long.valueOf(appId).equals(memoryId)) {
                throw new IllegalArgumentException("对话记忆与应用 ID 不匹配");
            }
            return chatMemory;
        };
        return switch (codeGenType) {
            case VUE_PROJECT -> {
                var argumentErrors = new AtomicInteger();
                yield AiServices.builder(AiCodeGeneratorService.class)
                        .streamingChatModel(reasoningStreamingChatModel)
                        .chatMemoryProvider(memoryProvider)
                        .tools(toolManager.getAllTools(workspace))
                        .toolArgumentsErrorHandler((error, context) -> {
                            if (argumentErrors.getAndIncrement() >= MAX_VUE_TOOL_ARGUMENT_RETRIES) {
                                throw new BusinessException(ErrorCode.OPERATION_ERROR,
                                        "Vue 文件工具参数多次不完整或格式错误，请重试生成");
                            }
                            String name = context.toolExecutionRequest().name();
                            if (!"writeFile".equals(name)) {
                                return ToolErrorHandlerResult.text("参数缺失或 JSON 格式错误。请重新调用 "
                                        + name + "，传入符合工具定义的完整参数。");
                            }
                            return ToolErrorHandlerResult.text("文件未写入，参数缺失或 JSON 格式错误。"
                                    + "请重新调用 writeFile，传入完整的 relativeFilePath 和 content。"
                                    + "一次只重发这个文件，文件过长时请拆成较小的组件或样式文件。");
                        })
                        .hallucinatedToolNameStrategy(request -> ToolExecutionResultMessage.from(
                                request, "Error: there is no tool called " + request.name()))
                        .build();
            }
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

    private ChatMemoryProvider appChatMemoryProvider(boolean discardUnfinishedToolCalls) {
        var memoryStore = new AppChatMemoryStore(redisChatMemoryStore, chatHistoryService);
        return appId -> {
            if (discardUnfinishedToolCalls) memoryStore.discardUnfinishedToolCalls(appId);
            return MessageWindowChatMemory.builder()
                    .id(appId)
                    .chatMemoryStore(memoryStore)
                    .maxMessages(AppChatMemoryStore.MAX_MESSAGES)
                    .build();
        };
    }

    @Bean
    public AiAppNameGeneratorService aiAppNameGeneratorService() {
        return AiServices.builder(AiAppNameGeneratorService.class)
                .chatModel(chatModel)
                .build();
    }
}

