package com.hjq.CodeLess.ai.model.message;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.response.PartialToolCall;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 工具调用消息
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class ToolRequestMessage extends StreamMessage {

    private String id;

    private String name;

    private String arguments;

    public ToolRequestMessage(ToolExecutionRequest toolExecutionRequest) {
        this(toolExecutionRequest.id(), toolExecutionRequest.name(), toolExecutionRequest.arguments());
    }

    public ToolRequestMessage(PartialToolCall toolCall) {
        this(toolCall.id(), toolCall.name(), toolCall.partialArguments());
    }

    private ToolRequestMessage(String id, String name, String arguments) {
        super(StreamMessageTypeEnum.TOOL_REQUEST.getValue());
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }
}
