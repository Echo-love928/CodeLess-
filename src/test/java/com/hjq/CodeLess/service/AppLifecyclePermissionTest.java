package com.hjq.CodeLess.service;

import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.exception.ErrorCode;
import com.hjq.CodeLess.model.entity.App;
import com.hjq.CodeLess.model.entity.User;
import com.hjq.CodeLess.service.impl.AppServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppLifecyclePermissionTest {
    @Test
    void otherUsersCannotStopGenerateDeployOrPause() {
        var service = spy(new AppServiceImpl());
        var generation = mock(AppGenerationService.class);
        var storage = mock(AppDeploymentStorage.class);
        ReflectionTestUtils.setField(service, "appGenerationService", generation);
        ReflectionTestUtils.setField(service, "appDeploymentStorage", storage);
        App app = App.builder().id(1L).userId(2L).codeGenType("multi_file").build();
        doReturn(app).when(service).getById(1L);
        User other = new User();
        other.setId(3L);
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), assertThrows(BusinessException.class,
                () -> service.stopGeneration(1L, other)).getCode());
        assertThrows(BusinessException.class, () -> service.chatToGenCode(1L, "generate", other));
        assertThrows(BusinessException.class, () -> service.deployApp(1L, other));
        assertThrows(BusinessException.class, () -> service.undeployApp(1L, other));
        verifyNoInteractions(generation, storage);
    }

    @Test
    void cannotDeployWhileGenerating() {
        var service = spy(new AppServiceImpl());
        var storage = mock(AppDeploymentStorage.class);
        ReflectionTestUtils.setField(service, "appDeploymentStorage", storage);
        App app = App.builder().id(1L).userId(2L).generationStatus("generating").build();
        doReturn(app).when(service).getById(1L);
        User owner = new User();
        owner.setId(2L);
        assertThrows(BusinessException.class, () -> service.deployApp(1L, owner));
        verifyNoInteractions(storage);
    }
}
