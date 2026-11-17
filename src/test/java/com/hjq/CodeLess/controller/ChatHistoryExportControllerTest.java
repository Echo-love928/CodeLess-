package com.hjq.CodeLess.controller;

import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.exception.ErrorCode;
import com.hjq.CodeLess.exception.GlobalExceptionHandler;
import com.hjq.CodeLess.model.entity.User;
import com.hjq.CodeLess.service.ChatHistoryExportService;
import com.hjq.CodeLess.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatHistoryExportControllerTest {
    private final UserService users = mock(UserService.class);
    private final ChatHistoryExportService exports = mock(ChatHistoryExportService.class);
    private final User owner = User.builder().id(2L).build();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var controller = new ChatHistoryController();
        ReflectionTestUtils.setField(controller, "userService", users);
        ReflectionTestUtils.setField(controller, "chatHistoryExportService", exports);
        when(users.getLoginUser(any())).thenReturn(owner);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void returnsUtf8MarkdownAsAnAttachmentWithPrivateCachingDisabled() throws Exception {
        String name = "咖啡馆🌟-对话记录-1990000000000000001.md";
        String markdown = "# 咖啡馆🌟\n\n## 用户\n\n你好\n\n```html\n<h1>世界</h1>\n```\n";
        when(exports.export(1990000000000000001L, owner)).thenReturn(new ChatHistoryExportService.MarkdownExport(name, markdown));
        var response = mvc.perform(get("/chatHistory/app/1990000000000000001/export"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse();
        assertArrayEquals(markdown.getBytes(StandardCharsets.UTF_8), response.getContentAsByteArray());
        assertEquals(markdown.getBytes(StandardCharsets.UTF_8).length, response.getContentLength());
        var disposition = ContentDisposition.parse(response.getHeader(HttpHeaders.CONTENT_DISPOSITION));
        assertEquals("attachment", disposition.getType());
        assertEquals(name, disposition.getFilename());
        verify(exports).export(1990000000000000001L, owner);
    }

    @Test
    void unauthenticatedRequestsReturnAnErrorInsteadOfADownload() throws Exception {
        when(users.getLoginUser(any())).thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));
        mvc.perform(get("/chatHistory/app/1/export"))
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_LOGIN_ERROR.getCode()))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION));
        verifyNoInteractions(exports);
    }

    @Test
    void permissionErrorsRemainJsonRatherThanBeingServedAsMarkdown() throws Exception {
        when(exports.export(1L, owner)).thenThrow(new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权导出"));
        mvc.perform(get("/chatHistory/app/1/export"))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value(ErrorCode.NO_AUTH_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value("无权导出"))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION));
    }
}
