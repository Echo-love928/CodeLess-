package com.hjq.CodeLess.service;

import com.hjq.CodeLess.exception.ErrorCode;
import com.hjq.CodeLess.exception.ThrowUtils;
import com.hjq.CodeLess.model.entity.ChatHistory;
import com.hjq.CodeLess.model.entity.User;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;

/** 导出数据库中的完整历史，不依赖页面分页或 Redis 记忆窗口。 */
@Service
public class ChatHistoryExportService {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private final AppService appService;
    private final ChatHistoryService chatHistoryService;
    private final AppAccessPolicy accessPolicy;

    public ChatHistoryExportService(AppService appService, ChatHistoryService chatHistoryService,
                                    AppAccessPolicy accessPolicy) {
        this.appService = appService;
        this.chatHistoryService = chatHistoryService;
        this.accessPolicy = accessPolicy;
    }

    public MarkdownExport export(Long appId, User loginUser) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 无效");
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        var app = appService.getById(appId);
        ThrowUtils.throwIf(app == null || Integer.valueOf(1).equals(app.getIsDelete()), ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!accessPolicy.canManage(app, loginUser), ErrorCode.NO_AUTH_ERROR, "无权导出该应用的对话历史");

        List<ChatHistory> records = chatHistoryService.list(QueryWrapper.create()
                .eq("appId", appId)
                .orderBy("createTime", true)
                .orderBy("id", true));
        String name = app.getAppName() == null || app.getAppName().isBlank() ? "未命名应用" : app.getAppName();
        StringBuilder markdown = new StringBuilder("# ").append(escapeHeading(name)).append(" — 对话记录\n\n")
                .append("- 应用 ID：`").append(appId).append("`\n")
                .append("- 消息数量：").append(records.size()).append("\n\n");
        if (records.isEmpty()) markdown.append("暂无对话记录。\n");
        for (int index = 0; index < records.size(); index++) {
            ChatHistory record = records.get(index);
            String role = switch (record.getMessageType() == null ? "" : record.getMessageType()) {
                case "user" -> "用户";
                case "ai" -> "AI";
                default -> "其他消息";
            };
            markdown.append("---\n\n## 消息 ").append(index + 1).append(" · ").append(role).append("\n\n")
                    .append("时间：").append(record.getCreateTime() == null ? "未知" : TIME_FORMAT.format(record.getCreateTime()))
                    .append("\n\n");
            String content = record.getMessage() == null ? "" : record.getMessage();
            markdown.append(content).append("\n");
            // 中断生成可能留下未闭合的代码块，补上结束标记以免吞掉后续消息的标题。
            String openFence = openFence(content);
            if (!openFence.isEmpty()) markdown.append(openFence).append("\n");
            markdown.append("\n");
        }
        return new MarkdownExport(fileName(name, appId), markdown.toString());
    }

    private static String escapeHeading(String name) {
        return name.replaceAll("[\\r\\n\\p{Cntrl}]+", " ").strip()
                .replaceAll("([\\\\`*_{}\\[\\]<>()#+.!|~-])", "\\\\$1");
    }

    private static String fileName(String name, Long appId) {
        String safeName = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        if (safeName.isBlank()) safeName = "未命名应用";
        safeName = safeName.substring(0, safeName.offsetByCodePoints(0, Math.min(60, safeName.codePointCount(0, safeName.length()))));
        return safeName + "-对话记录-" + appId + ".md";
    }

    private static String openFence(String content) {
        String open = "";
        for (String line : content.split("\\R", -1)) {
            var matcher = FENCE.matcher(line);
            if (!matcher.matches()) continue;
            String marker = matcher.group(1);
            if (open.isEmpty()) {
                if (marker.charAt(0) != '`' || !matcher.group(2).contains("`")) open = marker;
            } else if (marker.charAt(0) == open.charAt(0) && marker.length() >= open.length()
                    && matcher.group(2).isBlank()) {
                open = "";
            }
        }
        return open;
    }

    public record MarkdownExport(String fileName, String content) {}
}
