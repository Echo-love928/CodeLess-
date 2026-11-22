package com.hjq.CodeLess.core.handler;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hjq.CodeLess.ai.model.message.StreamMessageTypeEnum;
import com.hjq.CodeLess.ai.tools.BaseTool;
import com.hjq.CodeLess.ai.tools.ToolManager;
import com.hjq.CodeLess.ai.tools.VueProjectWorkspace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** 格式化 Vue 工具事件，并提取对话历史显示的内容。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JsonMessageStreamHandler {
    private static final int MAX_TOOL_DETAIL_BYTES = 48 * 1024;
    private static final String OMITTED_DETAILS = "\n\n[工具调用] 后续工具详情已省略。\n\n";

    private final ToolManager toolManager;

    public Flux<String> formatToolEvents(Flux<String> originFlux, VueProjectWorkspace workspace) {
        return Flux.defer(() -> {
            var state = new ToolOutput();
            return originFlux.mapNotNull(chunk -> formatToolEvent(chunk, workspace, state));
        });
    }

    private String formatToolEvent(String chunk, VueProjectWorkspace workspace, ToolOutput state) {
        var message = JSONUtil.parseObj(chunk);
        var type = StreamMessageTypeEnum.getEnumByValue(message.getStr("type"));
        if (type == null || type == StreamMessageTypeEnum.AI_RESPONSE) return chunk;
        String name = message.getStr("name");
        if (type == StreamMessageTypeEnum.TOOL_REQUEST) {
            String id = message.getStr("id");
            if (StrUtil.isBlank(id) || StrUtil.isBlank(name) || !state.seenToolIds.add(id)) return null;
            BaseTool tool = toolManager.getTool(name, workspace);
            message.set("display", tool == null ? "[选择工具] 未知工具" : tool.generateToolRequestResponse());
        } else {
            BaseTool tool = StrUtil.isBlank(name) ? null : toolManager.getTool(name, workspace);
            String summary = "[工具调用] " + (tool == null ? "未知工具" : tool.getDisplayName());
            message.set("display", state.limit(formatExecutedResult(message, tool, summary), summary));
        }
        return message.toString();
    }

    private String formatExecutedResult(JSONObject message, BaseTool tool, String summary) {
        if (tool == null) return summary;
        String result = message.getStr("result");
        if (Boolean.TRUE.equals(message.getBool("failed")) || StrUtil.startWithAny(result,
                "文件写入失败", "读取文件失败", "修改文件失败", "读取目录失败", "删除文件失败", "错误：", "警告：")) {
            return summary + "（未完成）";
        }
        try {
            String arguments = message.getStr("arguments", "");
            if (!JSONUtil.isTypeJSONObject(arguments.strip())) return summary + "（参数格式无效）";
            return StrUtil.blankToDefault(tool.generateToolExecutedResult(JSONUtil.parseObj(arguments)), summary);
        } catch (RuntimeException error) {
            log.warn("格式化 Vue 工具结果失败，工具：{}", tool.getToolName(), error);
            return summary + "（详情无法显示）";
        }
    }

    String extractChatText(String chunk) {
        var message = JSONUtil.parseObj(chunk);
        if (StreamMessageTypeEnum.AI_RESPONSE.getValue().equals(message.getStr("type"))) {
            return message.getStr("data", "");
        }
        if (StreamMessageTypeEnum.TOOL_EXECUTED.getValue().equals(message.getStr("type"))) {
            return message.getStr("display", "");
        }
        return "";
    }

    private static final class ToolOutput {
        private final Set<String> seenToolIds = new HashSet<>();
        // 给 AI 正文预留空间，避免工具详情撑满 chat_history 的 TEXT 字段。
        private int remainingBytes = MAX_TOOL_DETAIL_BYTES - OMITTED_DETAILS.getBytes(StandardCharsets.UTF_8).length;

        private String limit(String detail, String summary) {
            if (remainingBytes < 0) return "";
            String display = "\n\n" + detail.strip() + "\n\n";
            int size = display.getBytes(StandardCharsets.UTF_8).length;
            if (size > remainingBytes) {
                display = "\n\n" + summary + "（详情已省略）\n\n";
                size = display.getBytes(StandardCharsets.UTF_8).length;
            }
            if (size > remainingBytes) {
                remainingBytes = -1;
                return OMITTED_DETAILS;
            }
            remainingBytes -= size;
            return display;
        }
    }
}
