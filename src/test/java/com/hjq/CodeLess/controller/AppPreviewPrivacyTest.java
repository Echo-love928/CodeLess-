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

    @Test
    void injectsThePreviewBridgeOnlyForTheOwnersIframe() throws Exception {
        var app = App.builder().id(1L).userId(2L).visibility("public").build();
        var apps = mock(AppService.class);
        var users = mock(UserService.class);
        var resources = mock(AppResourceService.class);
        var controller = new StaticResourceController(apps, users, new AppAccessPolicy(), resources, new AppBrowseLimiter());
        var request = new MockHttpServletRequest();
        request.setContextPath("/api");
        request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, "/static/html_1/");
        String html = "<!DOCTYPE html><html><head><title>站点</title></head><body>预览</body></html>";
        var index = Files.writeString(temp.resolve("index.html"), html);
        when(resources.appId("html_1")).thenReturn(1L);
        when(apps.getById(1L)).thenReturn(app);
        when(resources.resolve(app, "html_1", "/")).thenReturn(index);
        var owner = User.builder().id(2L).build();
        when(users.getLoginUserOptional(request)).thenReturn(owner);
        assertEquals(html, controller.serveStaticResource("html_1", request).getBody()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));

        request.addHeader("Sec-Fetch-Dest", "iframe");
        String embedded = controller.serveStaticResource("html_1", request).getBody()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(embedded.startsWith("<!DOCTYPE html>"));
        assertTrue(embedded.contains("src=\"/api/visual-editor.js\""));
        assertTrue(embedded.contains("<body>预览</body>"));
        assertNotNull(getClass().getResource("/static/visual-editor.js"));

        when(users.getLoginUserOptional(request)).thenReturn(User.builder().id(3L).build());
        assertEquals(html, controller.serveStaticResource("html_1", request).getBody()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
}
