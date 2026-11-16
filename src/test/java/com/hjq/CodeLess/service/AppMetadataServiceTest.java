package com.hjq.CodeLess.service;

import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.model.dto.app.*;
import com.hjq.CodeLess.model.entity.App;
import com.hjq.CodeLess.model.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AppMetadataServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final AppDeploymentStorage deployment = mock(AppDeploymentStorage.class);
    private final AppVersionStorage files = mock(AppVersionStorage.class);
    private final App app = App.builder().id(1L).userId(2L).visibility("public").tags("[]").priority(0)
            .featureStatus("none").codeGenType("html").generationStatus("completed").version(1).deployKey("abc123").deploymentStatus("online").build();
    private final User owner = User.builder().id(2L).userRole("user").build();
    private final User admin = User.builder().id(3L).userRole("admin").build();
    private AppMetadataService service;
    @BeforeEach void setup() {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<App>>any(), eq(1L))).thenReturn(List.of(app));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(files.hasPreview(1L, "html")).thenReturn(true);
        doAnswer(call -> { ((Runnable)call.getArgument(1)).run(); return null; }).when(deployment).pause(anyString(), any(Runnable.class));
        service = new AppMetadataService(jdbc, manager, new AppGenerationService(), deployment, files, new AppAccessPolicy());
    }
    @Test void newApplicationsDefaultToPrivateAndNeverPublishTheirPromptAsDescription() {
        var created = new App(); var input = new AppAddRequest(); input.setInitPrompt("private prompt");
        service.initialize(created, input);
        assertEquals("private", created.getVisibility()); assertEquals("", created.getDescription()); assertEquals("[]", created.getTags());
        assertEquals(0, created.getPriority());
    }
    @Test void privateTransitionWithdrawsRecommendationAndUnpublishesBeforeCommit() {
        app.setPriority(99); app.setFeatureStatus("approved");
        var patch = new AppUpdateRequest(); patch.setId(1L); patch.setVisibility("private");
        assertTrue(service.update(patch, null, owner, false));
        assertEquals("private", app.getVisibility()); assertEquals("offline", app.getDeploymentStatus());
        assertEquals(0, app.getPriority()); assertEquals("none", app.getFeatureStatus());
        verify(deployment).pause(eq("abc123"), any(Runnable.class));
        verify(manager).commit(any());
    }
    @Test void ownerCanApplyButOnlyAdministratorCanApprove() {
        assertTrue(service.apply(1L, "useful app", owner)); assertEquals("pending", app.getFeatureStatus()); assertEquals(0, app.getPriority());
        assertThrows(BusinessException.class, () -> service.review(1L, true, "approve", owner));
        assertTrue(service.review(1L, true, "approved", admin)); assertEquals("approved", app.getFeatureStatus()); assertEquals(99, app.getPriority());
    }
    @Test void rejectedApplicationsCanBeImprovedAndAppliedAgain() {
        app.setFeatureStatus("pending"); service.review(1L, false, "improve description", admin);
        assertEquals("rejected", app.getFeatureStatus()); assertEquals(0, app.getPriority());
        service.apply(1L, "improved", owner); assertEquals("pending", app.getFeatureStatus()); assertNull(app.getFeatureReviewMessage());
    }
    @Test void privateApplicationsAndOtherPeoplesApplicationsCannotBeSubmitted() {
        app.setVisibility("private"); assertThrows(BusinessException.class, () -> service.apply(1L, "reason", owner));
        app.setVisibility("public"); assertThrows(BusinessException.class, () -> service.apply(1L, "reason", admin));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
    @Test void userCannotUseAdministrativeUpdateOrEditSomeoneElsesApplication() {
        var patch = new AppUpdateRequest(); patch.setId(1L); patch.setVisibility("public");
        assertThrows(BusinessException.class, () -> service.update(patch, 99, owner, true));
        assertThrows(BusinessException.class, () -> service.update(patch, null, User.builder().id(4L).build(), false));
        verifyNoInteractions(deployment);
    }
}
