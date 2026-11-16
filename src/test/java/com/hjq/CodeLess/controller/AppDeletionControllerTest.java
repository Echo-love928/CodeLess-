package com.hjq.CodeLess.controller;

import com.hjq.CodeLess.common.DeleteRequest;
import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.model.entity.User;
import com.hjq.CodeLess.service.AppService;
import com.hjq.CodeLess.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppDeletionControllerTest {
    @Test void bothEndpointsUseCleanupServiceAndRejectMissingId() {
        var controller = new AppController();
        var apps = mock(AppService.class);
        var users = mock(UserService.class);
        var request = mock(HttpServletRequest.class);
        var user = User.builder().id(2L).userRole("admin").build();
        ReflectionTestUtils.setField(controller, "appService", apps);
        ReflectionTestUtils.setField(controller, "userService", users);
        when(users.getLoginUser(request)).thenReturn(user);
        when(apps.deleteApp(1L, user)).thenReturn(true);
        var body = new DeleteRequest(); body.setId(1L);
        assertTrue(controller.deleteApp(body, request).getData());
        assertTrue(controller.deleteAppByAdmin(body, request).getData());
        verify(apps, times(2)).deleteApp(1L, user);
        verify(apps, never()).removeById(1L);
        assertThrows(BusinessException.class, () -> controller.deleteApp(new DeleteRequest(), request));
        assertThrows(BusinessException.class, () -> controller.deleteAppByAdmin(new DeleteRequest(), request));
    }
}
