package com.hjq.CodeLess.ai;

import com.hjq.CodeLess.ai.model.HtmlCodeResult;
import com.hjq.CodeLess.ai.model.MultiFileCodeResult;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;

public interface AiCodeGeneratorService {

    @SystemMessage(fromResource = "prompt/codegen-html-stream-system-prompt.txt")
    TokenStream generateHtmlCodeTokenStream(@MemoryId Long appId, @UserMessage String userMessage);

    @SystemMessage(fromResource = "prompt/codegen-multi-file-stream-system-prompt.txt")
    TokenStream generateMultiFileCodeTokenStream(@MemoryId Long appId, @UserMessage String userMessage);

    /**
     * 生成 HTML 代码
     *
     * @param appId 应用 ID，用于隔离对话记忆
     * @param userMessage 用户消息
     * @return 生成的代码结果
     */
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    HtmlCodeResult generateHtmlCode(@MemoryId Long appId, @UserMessage String userMessage);

    /**
     * 生成多文件代码
     *
     * @param appId 应用 ID，用于隔离对话记忆
     * @param userMessage 用户消息
     * @return 生成的代码结果
     */
    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    MultiFileCodeResult generateMultiFileCode(@MemoryId Long appId, @UserMessage String userMessage);

    /**
     * 生成 HTML 代码（流式）
     *
     * @param appId 应用 ID，用于隔离对话记忆
     * @param userMessage 用户消息
     * @return 生成的代码结果
     */
    @SystemMessage(fromResource = "prompt/codegen-html-stream-system-prompt.txt")
    Flux<String> generateHtmlCodeStream(@MemoryId Long appId, @UserMessage String userMessage);

    /**
     * 生成多文件代码（流式）
     *
     * @param appId 应用 ID，用于隔离对话记忆
     * @param userMessage 用户消息
     * @return 生成的代码结果
     */
    @SystemMessage(fromResource = "prompt/codegen-multi-file-stream-system-prompt.txt")
    Flux<String> generateMultiFileCodeStream(@MemoryId Long appId, @UserMessage String userMessage);

    /**
     * 生成 Vue 项目代码（流式）
     *
     * @param appId 应用 ID，用于隔离对话记忆
     * @param userMessage 用户消息
     * @return 生成过程的流式响应
     */
    @SystemMessage(fromResource = "prompt/codegen-vue-project-system-prompt.txt")
    TokenStream generateVueProjectCodeStream(@MemoryId Long appId, @UserMessage String userMessage);
}

