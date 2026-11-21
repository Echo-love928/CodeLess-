package com.hjq.CodeLess.controller;

import com.hjq.CodeLess.model.dto.app.AppChatRequest;
import com.hjq.CodeLess.model.entity.User;
import com.hjq.CodeLess.service.AppService;
import com.hjq.CodeLess.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppGenerationSseTest {
    @Test
    void stoppedStreamSendsStoppedInsteadOfDone() {
        var controller = controllerWith(Flux.error(new CancellationException("生成已停止")));
        var events = controller.chatToGenCode(1L, "generate", mock(HttpServletRequest.class)).collectList().block();
        assertNotNull(events);
        assertEquals(1, events.size());
        assertEquals("stopped", events.get(0).event());
    }

    @Test
    void completedStreamWrapsContentAndSendsDone() {
        var controller = controllerWith(Flux.just("hello"));
        var events = controller.chatToGenCode(1L, "generate", mock(HttpServletRequest.class)).collectList().block();
        assertNotNull(events);
        assertEquals("{\"d\":\"hello\"}", events.get(0).data());
        assertEquals("done", events.get(1).event());
    }

    @Test
    void generationWithARequestBodyUsesTheSameSseFlow() {
        var controller = controllerWith(Flux.just("hello"));
        var events = controller.chatToGenCodeWithBody(new AppChatRequest(1L, "generate"),
                mock(HttpServletRequest.class)).collectList().block();
        assertNotNull(events);
        assertEquals("{\"d\":\"hello\"}", events.get(0).data());
        assertEquals("done", events.get(1).event());
    }

    private AppController controllerWith(Flux<String> flux) {
        var controller = new AppController();
        var apps = mock(AppService.class);
        var users = mock(UserService.class);
        var user = new User();
        when(users.getLoginUser(any())).thenReturn(user);
        when(apps.chatToGenCode(1L, "generate", user)).thenReturn(flux);
        ReflectionTestUtils.setField(controller, "appService", apps);
        ReflectionTestUtils.setField(controller, "userService", users);
        return controller;
    }
}
