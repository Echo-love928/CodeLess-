package com.hjq.CodeLess.service;

import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.exception.ErrorCode;
import com.hjq.CodeLess.model.entity.App;
import com.hjq.CodeLess.model.entity.ChatHistory;
import com.hjq.CodeLess.model.entity.User;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatHistoryExportServiceTest {
    private static final Long APP_ID = 1990000000000000001L;
    private final AppService apps = mock(AppService.class);
    private final ChatHistoryService history = mock(ChatHistoryService.class);
    private final ChatHistoryExportService service = new ChatHistoryExportService(apps, history, new AppAccessPolicy());
    private final User owner = User.builder().id(2L).userRole("user").build();
    private final App app = App.builder().id(APP_ID).userId(2L).appName("咖啡馆网站").visibility("private").build();

    @BeforeEach
    void setUp() {
        when(apps.getById(APP_ID)).thenReturn(app);
        when(history.list(any(QueryWrapper.class))).thenReturn(List.of());
    }

    @Test
    void exportsAllPagesInStableTimeOrderAndPreservesMarkdownAndLargeIds() {
        var records = new ArrayList<ChatHistory>();
        for (int index = 0; index < 25; index++) {
            records.add(record(index + 1, index % 2 == 0 ? "user" : "ai", "消息内容 " + index));
        }
        String code = "**生成说明**\n\n```html\n<h1>你好 🌟</h1>\n```";
        records.get(1).setMessage(code);
        when(history.list(any(QueryWrapper.class))).thenReturn(records);

        var export = service.export(APP_ID, owner);
        assertTrue(export.content().startsWith("# 咖啡馆网站 — 对话记录\n"));
        assertTrue(export.content().contains("- 应用 ID：`" + APP_ID + "`"));
        assertTrue(export.content().contains("- 消息数量：25"));
        assertTrue(export.content().contains("## 消息 1 · 用户"));
        assertTrue(export.content().contains("## 消息 2 · AI"));
        assertTrue(export.content().contains(code));
        assertTrue(export.content().contains("时间：2026-11-17 12:00:00"));
        assertTrue(export.content().contains("## 消息 25 · 用户"));
        assertTrue(export.content().indexOf("消息内容 0\n") < export.content().indexOf("消息内容 24\n"));
        assertEquals("咖啡馆网站-对话记录-" + APP_ID + ".md", export.fileName());

        var query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(history).list(query.capture());
        String sql = query.getValue().toSQL();
        assertTrue(sql.contains(APP_ID.toString()), sql);
        assertTrue(sql.matches("(?is).*ORDER BY.*createTime.*ASC.*id.*ASC.*"), sql);
        assertFalse(sql.toUpperCase().contains("LIMIT"), sql);
    }

    @Test
    void anAdministratorCanExportAnotherUsersPrivateApp() {
        var administrator = User.builder().id(99L).userRole("admin").build();
        assertTrue(service.export(APP_ID, administrator).content().contains("暂无对话记录"));
        verify(history).list(any(QueryWrapper.class));
    }

    @Test
    void publicVisibilityDoesNotGiveOtherUsersAccessToConversationExport() {
        app.setVisibility("public");
        var stranger = User.builder().id(3L).userRole("user").build();
        var error = assertThrows(BusinessException.class, () -> service.export(APP_ID, stranger));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), error.getCode());
        verifyNoInteractions(history);
    }

    @Test
    void rejectsAnonymousInvalidMissingAndDeletedAppsBeforeQueryingHistory() {
        assertEquals(ErrorCode.NOT_LOGIN_ERROR.getCode(), assertThrows(BusinessException.class,
                () -> service.export(APP_ID, null)).getCode());
        assertThrows(BusinessException.class, () -> service.export(APP_ID, new User()));
        assertThrows(BusinessException.class, () -> service.export(null, owner));
        assertThrows(BusinessException.class, () -> service.export(0L, owner));
        when(apps.getById(APP_ID)).thenReturn(null);
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), assertThrows(BusinessException.class,
                () -> service.export(APP_ID, owner)).getCode());
        app.setIsDelete(1);
        when(apps.getById(APP_ID)).thenReturn(app);
        assertThrows(BusinessException.class, () -> service.export(APP_ID, owner));
        verifyNoInteractions(history);
    }

    @Test
    void escapesTheApplicationTitleAndProducesASafeUnicodeFileName() {
        app.setAppName("../我的[应用]\r\n# 标题: \"测试\"");
        var export = service.export(APP_ID, owner);
        assertTrue(export.content().contains("我的\\[应用\\]"));
        assertFalse(export.content().contains("\n# 标题"));
        assertFalse(export.fileName().matches("(?s).*[\\\\/:*?\"<>|\\p{Cntrl}].*"));
        app.setAppName("🌟".repeat(100));
        var emojiExport = service.export(APP_ID, owner);
        assertTrue(emojiExport.fileName().startsWith("🌟".repeat(60) + "-对话记录-"));
        app.setAppName("  ");
        assertTrue(service.export(APP_ID, owner).fileName().startsWith("未命名应用-"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"```html\n<h1>中断", "~~~~js\nconst x = 1", "````markdown\n```js\nconst x = 1\n```"})
    void anUnfinishedCodeFenceCannotHideFollowingMessages(String content) {
        when(history.list(any(QueryWrapper.class))).thenReturn(List.of(
                record(1, "ai", content), record(2, "user", "后续需求")));
        String fence = content.substring(0, content.indexOf('\n')).replaceAll("[^`~]", "");
        String markdown = service.export(APP_ID, owner).content();
        assertTrue(markdown.contains(content + "\n" + fence + "\n\n---\n\n## 消息 2 · 用户"));
    }

    @Test
    void handlesEmptyHistoryAndLegacyNullFields() {
        assertTrue(service.export(APP_ID, owner).content().contains("- 消息数量：0\n\n暂无对话记录。"));
        when(history.list(any(QueryWrapper.class))).thenReturn(List.of(new ChatHistory()));
        String markdown = service.export(APP_ID, owner).content();
        assertTrue(markdown.contains("其他消息"));
        assertTrue(markdown.contains("时间：未知"));
        assertFalse(markdown.contains("null"));
    }

    private static ChatHistory record(long id, String type, String content) {
        return ChatHistory.builder().id(id).appId(APP_ID).messageType(type).message(content)
                .createTime(LocalDateTime.of(2026, 11, 17, 12, 0)).build();
    }
}
