package com.hjq.CodeLess.core.handler;

import cn.hutool.json.JSONUtil;
import com.hjq.CodeLess.ai.model.message.StreamMessageTypeEnum;
import org.springframework.stereotype.Component;

/** Vue 工程模式从结构化消息中提取 AI 正文。 */
@Component
public class JsonMessageStreamHandler {

    String extractAiText(String chunk) {
        var message = JSONUtil.parseObj(chunk);
        if (StreamMessageTypeEnum.AI_RESPONSE.getValue().equals(message.getStr("type"))) {
            return message.getStr("data", "");
        }
        return "";
    }
}
