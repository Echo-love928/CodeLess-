package com.hjq.CodeLess.service;

import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.manager.CosManager;
import com.hjq.CodeLess.model.entity.App;
import com.hjq.CodeLess.service.impl.AppServiceImpl;
import com.hjq.CodeLess.service.impl.ScreenshotServiceImpl;
import com.hjq.CodeLess.utils.WebScreenshotUtils;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppScreenshotTest {

    @Test
    void deployedPageScreenshotIsUploadedAndSavedAsCover() throws Exception {
        String deployedPage = "http://localhost:9090/abc123/";
        String coverUrl = "https://cdn.example.com/screenshots/deployed.png";
        var browser = mock(WebScreenshotUtils.class);
        var storage = mock(CosManager.class);
        var captured = new CompletableFuture<Path>();
        doAnswer(call -> {
            assertTrue(Thread.currentThread().isVirtual());
            Path output = call.getArgument(1);
            Files.writeString(output, "test screenshot");
            captured.complete(output);
            return null;
        }).when(browser).capture(eq(deployedPage), any(Path.class));
        when(storage.uploadFile(endsWith(".png"), any(File.class))).thenAnswer(call -> {
            File screenshot = call.getArgument(1);
            assertEquals(captured.getNow(null), screenshot.toPath());
            assertEquals("test screenshot", Files.readString(screenshot.toPath()));
            return coverUrl;
        });
        var service = spy(new AppServiceImpl());
        ReflectionTestUtils.setField(service, "screenshotService", new ScreenshotServiceImpl(storage, browser));
        doReturn(true).when(service).update(any(App.class), any(QueryWrapper.class));

        service.generateAppScreenshotAsync(123L, deployedPage);

        var update = ArgumentCaptor.forClass(App.class);
        verify(service, timeout(5000)).update(update.capture(), any(QueryWrapper.class));
        assertEquals(coverUrl, update.getValue().getCover());
        assertFalse(Files.exists(captured.get(1, TimeUnit.SECONDS)));
    }

    @Test
    void olderScreenshotCannotOverwriteTheLatestDeploymentCover() throws Exception {
        String deployedPage = "http://localhost:9090/abc123/";
        var firstTask = new CompletableFuture<Thread>();
        var finishFirst = new CompletableFuture<String>();
        var screenshots = mock(ScreenshotService.class);
        when(screenshots.generateAndUploadScreenshot(deployedPage)).thenAnswer(call -> {
            firstTask.complete(Thread.currentThread());
            return finishFirst.get(5, TimeUnit.SECONDS);
        }).thenReturn("https://cdn.example.com/screenshots/latest.png");
        var service = spy(new AppServiceImpl());
        ReflectionTestUtils.setField(service, "screenshotService", screenshots);
        doReturn(true).when(service).update(any(App.class), any(QueryWrapper.class));

        service.generateAppScreenshotAsync(123L, deployedPage);
        Thread olderTask = firstTask.get(5, TimeUnit.SECONDS);
        service.generateAppScreenshotAsync(123L, deployedPage);

        try {
            verify(service, timeout(5000)).update(any(App.class), any(QueryWrapper.class));
        } finally {
            finishFirst.complete("https://cdn.example.com/screenshots/old.png");
            olderTask.join(5000);
        }
        assertFalse(olderTask.isAlive());
        var update = ArgumentCaptor.forClass(App.class);
        verify(service).update(update.capture(), any(QueryWrapper.class));
        assertEquals("https://cdn.example.com/screenshots/latest.png", update.getValue().getCover());
    }

    @Test
    void failedCaptureDoesNotUploadAndCleansUpTheTemporaryFile() throws Exception {
        var browser = mock(WebScreenshotUtils.class);
        var storage = mock(CosManager.class);
        doThrow(new IOException("browser timed out")).when(browser).capture(anyString(), any(Path.class));
        var service = new ScreenshotServiceImpl(storage, browser);

        var error = assertThrows(BusinessException.class,
                () -> service.generateAndUploadScreenshot("http://localhost:9090/abc123/"));

        assertTrue(error.getMessage().contains("browser timed out"));
        var output = ArgumentCaptor.forClass(Path.class);
        verify(browser).capture(anyString(), output.capture());
        assertFalse(Files.exists(output.getValue()));
        verifyNoInteractions(storage);
    }

    @Test
    void vueSourcePageIsNotUsedForTheCover() {
        var browser = mock(WebScreenshotUtils.class);
        var service = new AppCoverGeneratorService(browser);
        ReflectionTestUtils.setField(service, "enabled", true);

        assertTrue(service.generateCover(123L, "vue_project").isEmpty());
        verifyNoInteractions(browser);
    }
}
