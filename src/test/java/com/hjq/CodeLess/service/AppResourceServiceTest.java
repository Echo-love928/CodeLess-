package com.hjq.CodeLess.service;
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
}
