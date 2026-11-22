package com.hjq.CodeLess.core.handler;

import cn.hutool.core.util.StrUtil;
import com.hjq.CodeLess.model.entity.User;
import com.hjq.CodeLess.model.enums.ChatHistoryMessageTypeEnum;
import com.hjq.CodeLess.model.enums.CodeGenTypeEnum;
import com.hjq.CodeLess.service.ChatHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.function.Function;

/** 按生成类型提取并保存对话内容，同时转发原始流。 */
@Component
@RequiredArgsConstructor
public class StreamHandlerExecutor {

    private final SimpleTextStreamHandler simpleTextStreamHandler;
    private final JsonMessageStreamHandler jsonMessageStreamHandler;

    public Flux<String> doExecute(Flux<String> originFlux,
                                  ChatHistoryService chatHistoryService,
                                  long appId, User loginUser, CodeGenTypeEnum codeGenType) {
        Function<String, String> extractChatText = switch (codeGenType) {
            case HTML, MULTI_FILE -> simpleTextStreamHandler::extractAiText;
            case VUE_PROJECT -> jsonMessageStreamHandler::extractChatText;
        };

        return Flux.defer(() -> {
            StringBuilder aiResponse = new StringBuilder();
            return originFlux
                    .doOnNext(chunk -> aiResponse.append(extractChatText.apply(chunk)))
                    .doOnComplete(() -> {
                        String reply = aiResponse.toString();
                        if (StrUtil.isBlank(reply) && codeGenType == CodeGenTypeEnum.VUE_PROJECT) {
                            reply = "网站已生成，可以预览。";
                        }
                        if (StrUtil.isNotBlank(reply)) {
                            chatHistoryService.addChatMessage(appId, reply,
                                    ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                        }
                    })
                    .doOnError(error -> chatHistoryService.addChatMessage(appId,
                            "AI回复失败: " + error.getMessage(),
                            ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId()));
        });
    }
}
