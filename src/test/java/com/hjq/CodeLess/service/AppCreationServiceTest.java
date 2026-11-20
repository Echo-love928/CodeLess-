package com.hjq.CodeLess.service;

import com.hjq.CodeLess.ai.AiCodeGenTypeRoutingService;
import com.hjq.CodeLess.ai.AiCodeGenTypeRoutingServiceFactory;
import com.hjq.CodeLess.controller.AppController;
import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.exception.ErrorCode;
import com.hjq.CodeLess.model.dto.app.AppAddRequest;
import com.hjq.CodeLess.model.entity.App;
import com.hjq.CodeLess.model.entity.User;
import com.hjq.CodeLess.model.enums.CodeGenTypeEnum;
import com.hjq.CodeLess.service.impl.AppServiceImpl;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppCreationServiceTest {
    private static final long APP_ID = 1990000000000000001L;
    private static final User OWNER = User.builder().id(2L).build();
    private final AiCodeGenTypeRoutingService routing = mock(AiCodeGenTypeRoutingService.class);
    private final AppNameGeneratorService naming = mock(AppNameGeneratorService.class);
    private final AppServiceImpl service = spy(new AppServiceImpl());

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "aiCodeGenTypeRoutingService", routing);
        ReflectionTestUtils.setField(service, "appNameGeneratorService", naming);
        ReflectionTestUtils.setField(service, "appMetadataService", mock(AppMetadataService.class, CALLS_REAL_METHODS));
        when(naming.generateName("创建一个页面")).thenReturn("模型生成的应用名称");
        doAnswer(invocation -> {
            App app = invocation.getArgument(0);
            app.setId(APP_ID);
            return true;
        }).when(service).save(any(App.class));
    }

    @ParameterizedTest
    @EnumSource(CodeGenTypeEnum.class)
    void endpointDelegatesCreationAndPersistsTheSelectedType(CodeGenTypeEnum type) {
        var request = new MockHttpServletRequest();
        var users = mock(UserService.class);
        when(users.getLoginUser(request)).thenReturn(OWNER);
        var controller = new AppController();
        ReflectionTestUtils.setField(controller, "userService", users);
        ReflectionTestUtils.setField(controller, "appService", service);
        var factory = new AiCodeGenTypeRoutingServiceFactory();
        ReflectionTestUtils.setField(factory, "chatModel", new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from(type.name())).build();
            }
        });
        ReflectionTestUtils.setField(service, "aiCodeGenTypeRoutingService", factory.aiCodeGenTypeRoutingService());

        var response = controller.addApp(createRequest(), request);

        assertEquals(APP_ID, response.getData());
        App saved = savedApp();
        assertEquals(OWNER.getId(), saved.getUserId());
        assertEquals("创建一个页面", saved.getInitPrompt());
        assertEquals("模型生成的应用名称", saved.getAppName());
        assertEquals(type.getValue(), saved.getCodeGenType());
        assertEquals("private", saved.getVisibility());
        assertEquals("应用描述", saved.getDescription());
        assertEquals(List.of("demo"), AppMetadataRules.readTags(saved.getTags()));
        assertEquals("none", saved.getFeatureStatus());
        assertEquals("pending", saved.getGenerationStatus());
        assertEquals("offline", saved.getDeploymentStatus());
        assertEquals(0, saved.getVersion());
    }

    @Test
    void routerFailureFallsBackToVue() {
        when(routing.routeCodeGenType("创建一个页面")).thenThrow(new IllegalArgumentException("invalid model response"));

        assertEquals(APP_ID, service.createApp(createRequest(), OWNER));
        assertEquals(CodeGenTypeEnum.VUE_PROJECT.getValue(), savedApp().getCodeGenType());
    }

    @Test
    void rejectsBlankPromptBeforeCallingTheModelsOrSaving() {
        var addRequest = new AppAddRequest();
        addRequest.setInitPrompt("  ");

        var error = assertThrows(BusinessException.class, () -> service.createApp(addRequest, OWNER));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), error.getCode());
        verifyNoInteractions(naming, routing);
        verify(service, never()).save(any(App.class));
    }

    private App savedApp() {
        var capture = ArgumentCaptor.forClass(App.class);
        verify(service).save(capture.capture());
        return capture.getValue();
    }

    private AppAddRequest createRequest() {
        var addRequest = new AppAddRequest();
        addRequest.setInitPrompt("  创建一个页面  ");
        addRequest.setDescription(" 应用描述 ");
        addRequest.setTags(List.of("Demo"));
        return addRequest;
    }
}
