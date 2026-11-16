package com.hjq.CodeLess.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AppDeploymentStorageTest {
    @TempDir Path temp;

    @Test
    void pauseRemovesPublishedFilesButRetainsSourceAndCanRepublish() throws Exception {
        Path output = temp.resolve("output");
        Path deploy = temp.resolve("deploy");
        Path source = Files.createDirectories(output.resolve("multi_file_123"));
        Files.writeString(source.resolve("index.html"), "website");
        var storage = new AppDeploymentStorage(output, deploy);
        storage.publish("multi_file", 123L, "abc123");
        assertEquals("website", Files.readString(deploy.resolve("abc123/index.html")));
        storage.pause("abc123");
        assertFalse(Files.exists(deploy.resolve("abc123")));
        assertTrue(Files.exists(source.resolve("index.html")));
        storage.publish("multi_file", 123L, "abc123");
        assertTrue(Files.exists(deploy.resolve("abc123/index.html")));
    }

    @Test
    void pauseMovesBackupOutsidePublicRootBeforePersisting() throws Exception {
        Path output = temp.resolve("output");
        Path deploy = temp.resolve("deploy");
        Files.createDirectories(output.resolve("html_123"));
        Files.writeString(output.resolve("html_123/index.html"), "private after save");
        var storage = new AppDeploymentStorage(output, deploy);
        storage.publish("html", 123L, "abc123");
        storage.pause("abc123", () -> {
            try (var published = Files.list(deploy); var backup = Files.list(temp.resolve("deploy.work"))) {
                assertEquals(0, published.count());
                assertEquals(1, backup.count());
            } catch (java.io.IOException error) { throw new RuntimeException(error); }
        });
        assertFalse(Files.exists(deploy.resolve("abc123")));
    }

    @Test
    void rejectsDeploymentKeyOutsideRoot() throws Exception {
        var storage = new AppDeploymentStorage(temp.resolve("output"), temp.resolve("deploy"));
        Path sentinel = Files.writeString(temp.resolve("important"), "keep");
        assertThrows(IllegalArgumentException.class, () -> storage.pause("../important"));
        assertEquals("keep", Files.readString(sentinel));
    }

    @Test
    void databaseFailureRestoresPreviouslyPublishedVersion() throws Exception {
        Path output = temp.resolve("output");
        Path deploy = temp.resolve("deploy");
        Path source = Files.createDirectories(output.resolve("multi_file_123"));
        Files.writeString(source.resolve("index.html"), "old");
        var storage = new AppDeploymentStorage(output, deploy);
        storage.publish("multi_file", 123L, "abc123");
        Files.writeString(source.resolve("index.html"), "new");
        assertThrows(IllegalStateException.class, () -> storage.publish("multi_file", 123L, "abc123", () -> {
            throw new IllegalStateException("database unavailable");
        }));
        assertEquals("old", Files.readString(deploy.resolve("abc123/index.html")));
        assertThrows(IllegalStateException.class, () -> storage.pause("abc123", () -> {
            throw new IllegalStateException("database unavailable");
        }));
        assertEquals("old", Files.readString(deploy.resolve("abc123/index.html")));
    }
}
