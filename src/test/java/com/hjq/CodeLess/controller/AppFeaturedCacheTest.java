package com.hjq.CodeLess.controller;

import com.hjq.CodeLess.model.dto.app.AppQueryRequest;
import com.hjq.CodeLess.model.entity.App;
import com.hjq.CodeLess.model.entity.User;
import com.hjq.CodeLess.model.vo.AppVO;
import com.hjq.CodeLess.service.AppAccessPolicy;
import com.hjq.CodeLess.service.AppBrowseLimiter;
import com.hjq.CodeLess.service.AppSearchService;
import com.hjq.CodeLess.service.AppService;
import com.hjq.CodeLess.service.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class AppFeaturedCacheTest {
    @Test
    void cachedPageKeepsRateLimitingRedactionAndCurrentVisibility() {
        var jdbc = mock(JdbcTemplate.class);
        var app = App.builder().id(1L).userId(2L).visibility("public").initPrompt("owner-only prompt").build();
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L, 0L);
        when(jdbc.query(startsWith("select id from app"), ArgumentMatchers.<RowMapper<Long>>any(), any(Object[].class)))
                .thenReturn(List.of(app.getId()));
        String currentAppQuery = "select * from app where isDelete=0 and visibility='public'"
                + " and priority=99 and featureStatus='approved' and id in (?)";
        when(jdbc.query(eq(currentAppQuery), ArgumentMatchers.<RowMapper<App>>any(), any(Object[].class)))
                .thenReturn(List.of(app)).thenReturn(List.of(app)).thenReturn(List.of());
        when(jdbc.query(startsWith("select * from app"), ArgumentMatchers.<RowMapper<App>>any(), eq(10), eq(0L)))
                .thenReturn(List.of());

        var request = new MockHttpServletRequest();
        var userService = mock(UserService.class);
        when(userService.getLoginUserOptional(request)).thenReturn(User.builder().id(2L).build(), null, null);
        var apps = mock(AppService.class);
        when(apps.getAppVOList(anyList())).thenAnswer(call -> call.<List<App>>getArgument(0).stream().map(record -> {
            var view = new AppVO();
            view.setInitPrompt(record.getInitPrompt());
            return view;
        }).toList());
        var limiter = mock(AppBrowseLimiter.class);

        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class, () -> jdbc);
            context.registerBean(ConcurrentMapCacheManager.class, () -> new ConcurrentMapCacheManager("good_app_page"));
            context.register(CacheConfig.class, AppAccessPolicy.class, AppSearchService.class);
            context.refresh();

            var controller = new AppController();
            ReflectionTestUtils.setField(controller, "appSearchService", context.getBean(AppSearchService.class));
            ReflectionTestUtils.setField(controller, "appAccessPolicy", context.getBean(AppAccessPolicy.class));
            ReflectionTestUtils.setField(controller, "userService", userService);
            ReflectionTestUtils.setField(controller, "appService", apps);
            ReflectionTestUtils.setField(controller, "appBrowseLimiter", limiter);

            var query = new AppQueryRequest();
            assertEquals("owner-only prompt",
                    controller.listGoodAppVOByPage(query, request).getData().getRecords().getFirst().getInitPrompt());
            assertNull(controller.listGoodAppVOByPage(query, request).getData().getRecords().getFirst().getInitPrompt());
            assertTrue(controller.listGoodAppVOByPage(query, request).getData().getRecords().isEmpty());

            verify(jdbc).query(startsWith("select id from app"), ArgumentMatchers.<RowMapper<Long>>any(), any(Object[].class));
            verify(jdbc, times(3))
                    .query(eq(currentAppQuery), ArgumentMatchers.<RowMapper<App>>any(), any(Object[].class));
            verify(limiter, times(3)).check("search-ip", request.getRemoteAddr(), 60);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableCaching
    static class CacheConfig {}
}
