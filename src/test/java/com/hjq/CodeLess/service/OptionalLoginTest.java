package com.hjq.CodeLess.service;

import com.hjq.CodeLess.controller.UserController;
import com.hjq.CodeLess.exception.ErrorCode;
import com.hjq.CodeLess.exception.GlobalExceptionHandler;
import com.hjq.CodeLess.mapper.UserMapper;
import com.hjq.CodeLess.model.entity.User;
import com.hjq.CodeLess.service.impl.UserServiceImpl;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OptionalLoginTest {
    @Test void optionalLookupRecognizesLoginSessionAndRefreshesRoles() throws Exception {
        var service = spy(new UserServiceImpl());
        var mapper = mock(UserMapper.class);
        ReflectionTestUtils.setField(service, "mapper", mapper);
        var stored = User.builder().id(7L).userAccount("fixture").userRole("user")
                .userPassword(service.getEncryptPassword("fixture-password")).build();
        when(mapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(stored);
        var refreshed = User.builder().id(7L).userRole("admin").build();
        doReturn(refreshed).when(service).getById(7L);
        var request = new MockHttpServletRequest();
        service.userLogin("fixture", "fixture-password", request);
        assertSame(refreshed, service.getLoginUserOptional(request));
        loginStatusEndpoint(service).perform(get("/user/get/login").session((MockHttpSession) request.getSession(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userRole").value("admin"));
    }
    @Test void anonymousLookupDoesNotCreateSession() {
        var request = new MockHttpServletRequest();
        assertNull(new UserServiceImpl().getLoginUserOptional(request));
        assertNull(request.getSession(false));
    }

    @Test
    void anonymousLoginStatusReturnsWithoutAnExceptionOrNewSession() throws Exception {
        var result = loginStatusEndpoint(new UserServiceImpl()).perform(get("/user/get/login"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_LOGIN_ERROR.getCode()))
                .andReturn();

        assertNull(result.getResolvedException());
        assertNull(result.getRequest().getSession(false));
    }

    private MockMvc loginStatusEndpoint(UserService service) {
        var controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", service);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
