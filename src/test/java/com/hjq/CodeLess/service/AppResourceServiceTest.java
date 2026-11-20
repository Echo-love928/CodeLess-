package com.hjq.CodeLess.service;
import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.model.entity.App;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class AppResourceServiceTest {
    @TempDir Path root;
    @Test void resourcesCannotEscapeApplicationDirectory() throws Exception {
        Files.createDirectories(root.resolve("html_1")); Files.writeString(root.resolve("html_1/index.html"), "app"); Files.writeString(root.resolve("secret"), "secret");
        var resources = new AppResourceService(root); var app = App.builder().id(1L).codeGenType("html").build();
        assertEquals(root.resolve("html_1/index.html"), resources.resolve(app, "html_1", "/"));
        assertThrows(RuntimeException.class, () -> resources.resolve(app, "html_1", "/../secret"));
        assertThrows(RuntimeException.class, () -> resources.resolve(app, "html_1", "/..\\secret"));
        assertThrows(RuntimeException.class, () -> resources.appId(".version-backup-123"));
        assertThrows(RuntimeException.class, () -> resources.appId("html_9999999999999999999"));
    }

    @Test void vuePreviewServesDistButRejectsProjectSources() throws Exception {
        long appId = 1990000000000000001L;
        String key = "vue_project_" + appId;
        Path project = Files.createDirectories(root.resolve(key));
        Path assets = Files.createDirectories(project.resolve("dist/assets"));
        Path index = Files.writeString(project.resolve("dist/index.html"), "compiled page");
        Path script = Files.writeString(assets.resolve("app.js"), "compiled script");
        Files.writeString(project.resolve("index.html"), "source page");
        Files.writeString(project.resolve("package.json"), "{}");
        var resources = new AppResourceService(root);
        var app = App.builder().id(appId).codeGenType("vue_project").build();

        assertEquals(appId, resources.appId(key));
        assertEquals(index, resources.resolve(app, key, "/dist/index.html"));
        assertEquals(script, resources.resolve(app, key, "/dist/assets/app.js"));
        assertThrows(BusinessException.class, () -> resources.resolve(app, key, "/"));
        assertThrows(BusinessException.class, () -> resources.resolve(app, key, "/package.json"));
        assertThrows(BusinessException.class, () -> resources.resolve(app, key, "/dist/../package.json"));
    }
}
