package com.hjq.CodeLess.core.handler;

import org.springframework.stereotype.Component;

/** HTML 和多文件模式直接使用模型返回的文本。 */
@Component
public class SimpleTextStreamHandler {

    String extractAiText(String chunk) {
        return chunk;
    }
}
