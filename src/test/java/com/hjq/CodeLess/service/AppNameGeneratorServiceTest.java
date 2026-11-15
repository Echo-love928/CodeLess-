package com.hjq.CodeLess.service;

import com.hjq.CodeLess.ai.AiCodeGeneratorService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppNameGeneratorServiceTest {
    @Test
    void generatesNameFromPromptAndRemovesFormatting() {
        var ai = mock(AiCodeGeneratorService.class);
        when(ai.generateAppName("帮我做一个记录读书进度的网站")).thenReturn("名称：“书页足迹”\n这是解释");
        var service = new AppNameGeneratorService();
        ReflectionTestUtils.setField(service, "aiCodeGeneratorService", ai);
        assertEquals("书页足迹", service.generateName("帮我做一个记录读书进度的网站"));
    }

    @Test
    void modelFailureDoesNotPreventCreatingApp() {
        var ai = mock(AiCodeGeneratorService.class);
        when(ai.generateAppName(anyString())).thenThrow(new IllegalStateException("unavailable"));
        var service = new AppNameGeneratorService();
        ReflectionTestUtils.setField(service, "aiCodeGeneratorService", ai);
        assertEquals("个人读书记录", service.generateName("个人读书记录"));
    }
}
