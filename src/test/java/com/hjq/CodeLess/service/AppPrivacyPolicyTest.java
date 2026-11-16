package com.hjq.CodeLess.service;
import com.hjq.CodeLess.model.entity.App;
import com.hjq.CodeLess.model.entity.User;
import com.hjq.CodeLess.model.vo.AppVO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AppPrivacyPolicyTest {
    private final AppAccessPolicy policy = new AppAccessPolicy();
    @Test void privateApplicationsAreVisibleOnlyToOwnerAndAdministrator() {
        var app = App.builder().userId(1L).visibility("private").build();
        assertFalse(policy.canView(app, null));
        assertFalse(policy.canView(app, User.builder().id(2L).userRole("user").build()));
        assertTrue(policy.canView(app, User.builder().id(1L).userRole("user").build()));
        assertTrue(policy.canView(app, User.builder().id(2L).userRole("admin").build()));
        app.setIsDelete(1); assertFalse(policy.canView(app, User.builder().id(1L).build()));
    }
    @Test void publicApplicationsDoNotRevealPromptsOrReviewMessages() {
        var app = App.builder().userId(1L).visibility("public").build(); assertTrue(policy.canView(app, null));
        var view = new AppVO(); view.setInitPrompt("secret"); view.setFeatureRequestMessage("request"); view.setFeatureReviewMessage("review");
        policy.redact(view, app, User.builder().id(2L).build());
        assertNull(view.getInitPrompt()); assertNull(view.getFeatureRequestMessage()); assertNull(view.getFeatureReviewMessage());
    }
    @Test void tagsAreNormalizedAndBounded() {
        assertEquals(java.util.List.of("工具", "vue"), AppMetadataRules.tags(java.util.List.of(" 工具 ", "Vue", "vue")));
        assertThrows(RuntimeException.class, () -> AppMetadataRules.tags(java.util.List.of("<script>")));
        assertThrows(RuntimeException.class, () -> AppMetadataRules.tags(java.util.Collections.nCopies(9, "工具")));
        assertThrows(RuntimeException.class, () -> AppMetadataRules.visibility("unlisted"));
    }
}
