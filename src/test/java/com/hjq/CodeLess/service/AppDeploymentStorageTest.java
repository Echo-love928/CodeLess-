package com.hjq.CodeLess.service;

import com.hjq.CodeLess.core.builder.VueProjectBuilder;
import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.model.entity.App;
import com.hjq.CodeLess.model.entity.User;
import com.hjq.CodeLess.service.impl.AppServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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

    @Test
    void deployingVueBuildsTheCurrentProjectAndPublishesOnlyDist() throws Exception {
        Path project = Files.createDirectories(temp.resolve("output/vue_project_123"));
        Files.writeString(project.resolve("index.html"), "editable entry");
        Files.writeString(project.resolve("package.json"), "{}");
        var builder = mock(VueProjectBuilder.class);
        when(builder.buildProject(project.toString())).thenAnswer(invocation -> {
            Files.createDirectories(project.resolve("dist/assets"));
            Files.writeString(project.resolve("dist/index.html"), "built entry");
            Files.writeString(project.resolve("dist/assets/main.js"), "built script");
            return true;
        });
        var service = deploymentService(builder);
        doReturn(true).when(service).updateById(any(App.class));

        assertEquals("http://localhost:9090/abc123/", service.deployApp(123L, User.builder().id(2L).build()));

        Path published = temp.resolve("deploy/abc123");
        assertEquals("built entry", Files.readString(published.resolve("index.html")));
        assertEquals("built script", Files.readString(published.resolve("assets/main.js")));
        assertFalse(Files.exists(published.resolve("package.json")));
        assertFalse(Files.exists(published.resolve("dist")));
        var update = ArgumentCaptor.forClass(App.class);
        var calls = inOrder(builder, service);
        calls.verify(builder).buildProject(project.toString());
        calls.verify(service).updateById(update.capture());
        assertEquals(4, update.getValue().getDeployedVersion());
        assertEquals("online", update.getValue().getDeploymentStatus());
    }

    @Test
    void failedVueBuildOrMissingDistKeepsTheCurrentDeployment() throws Exception {
        Path project = Files.createDirectories(temp.resolve("output/vue_project_123"));
        Files.writeString(project.resolve("index.html"), "editable entry");
        Path deployedEntry = Files.createDirectories(temp.resolve("deploy/abc123")).resolve("index.html");
        Files.writeString(deployedEntry, "last published build");
        var builder = mock(VueProjectBuilder.class);
        when(builder.buildProject(project.toString())).thenReturn(false, true);
        var service = deploymentService(builder);
        var owner = User.builder().id(2L).build();

        var failedBuild = assertThrows(BusinessException.class, () -> service.deployApp(123L, owner));
        assertEquals("Vue 项目构建失败，请检查代码和依赖", failedBuild.getMessage());
        var missingDist = assertThrows(BusinessException.class, () -> service.deployApp(123L, owner));
        assertEquals("Vue 项目构建完成但缺少 dist/index.html", missingDist.getMessage());

        assertEquals("last published build", Files.readString(deployedEntry));
        verify(builder, times(2)).buildProject(project.toString());
        verify(service, never()).updateById(any(App.class));
    }

    private AppServiceImpl deploymentService(VueProjectBuilder builder) {
        var service = spy(new AppServiceImpl());
        ReflectionTestUtils.setField(service, "appGenerationService", new AppGenerationService());
        ReflectionTestUtils.setField(service, "appVersionService", mock(AppVersionService.class));
        ReflectionTestUtils.setField(service, "appDeploymentStorage",
                new AppDeploymentStorage(temp.resolve("output"), temp.resolve("deploy")));
        ReflectionTestUtils.setField(service, "vueProjectBuilder", builder);
        ReflectionTestUtils.setField(service, "deployBaseUrl", "http://localhost:9090");
        doReturn(App.builder().id(123L).userId(2L).codeGenType("vue_project").visibility("public")
                .generationStatus("completed").deployKey("abc123").version(4).build()).when(service).getById(123L);
        return service;
    }
}
