package com.hjq.CodeLess.controller;
import com.hjq.CodeLess.model.entity.App;
import com.hjq.CodeLess.model.entity.User;
import com.hjq.CodeLess.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AppPreviewPrivacyTest {
    @TempDir Path temp;
    @Test void directPreviewAndCoverCannotBypassVisibility() throws Exception {
        var app = App.builder().id(1L).userId(2L).codeGenType("html").visibility("private").build();
        var apps = mock(AppService.class); var users = mock(UserService.class); var resources = mock(AppResourceService.class);
        when(resources.appId("html_1")).thenReturn(1L); when(apps.getById(1L)).thenReturn(app);
        Path index = Files.writeString(temp.resolve("index.html"), "private content");
        when(resources.resolve(app, "html_1", "/")).thenReturn(index);
        var request = new MockHttpServletRequest(); request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, "/static/html_1/");
        var controller = new StaticResourceController(apps, users, new AppAccessPolicy(), resources, new AppBrowseLimiter());
        assertEquals(404, controller.serveStaticResource("html_1", request).getStatusCode().value());
        when(users.getLoginUserOptional(request)).thenReturn(User.builder().id(3L).userRole("user").build());
        assertEquals(404, controller.serveStaticResource("html_1", request).getStatusCode().value());
        when(users.getLoginUserOptional(request)).thenReturn(User.builder().id(2L).build());
        var response = controller.serveStaticResource("html_1", request); assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        app.setVisibility("public"); when(users.getLoginUserOptional(request)).thenReturn(null);
        assertEquals(200, controller.serveStaticResource("html_1", request).getStatusCode().value());
    }
}
