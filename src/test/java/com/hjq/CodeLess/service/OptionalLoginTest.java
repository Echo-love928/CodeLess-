package com.hjq.CodeLess.service;

import com.hjq.CodeLess.mapper.UserMapper;
import com.hjq.CodeLess.model.entity.User;
import com.hjq.CodeLess.service.impl.UserServiceImpl;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OptionalLoginTest {
    @Test void optionalLookupRecognizesLoginSessionAndRefreshesRoles() {
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
    }
    @Test void anonymousLookupDoesNotCreateSession() {
        var request = new MockHttpServletRequest();
        assertNull(new UserServiceImpl().getLoginUserOptional(request));
        assertNull(request.getSession(false));
    }
}
