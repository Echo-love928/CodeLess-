package com.hjq.CodeLess.service;

import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.exception.ErrorCode;
import com.hjq.CodeLess.mapper.AppMapper;
import com.hjq.CodeLess.model.entity.App;
import com.hjq.CodeLess.model.entity.User;
import com.hjq.CodeLess.service.impl.ProjectDownloadServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectDownloadServiceTest {
    private static final long APP_ID = 1990000000000000001L;
    private static final User OWNER = User.builder().id(2L).build();

    @TempDir Path outputRoot;
    private final AppMapper mapper = mock(AppMapper.class);
    private ProjectDownloadService service;

    @BeforeEach
    void setUp() {
        service = new ProjectDownloadServiceImpl(mapper, new AppResourceService(outputRoot));
        when(mapper.incrementDownloadCount(APP_ID)).thenReturn(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"html", "multi_file", "vue_project"})
    void requiresAUsablePreviewAndCountsEachSuccessfulZip(String codeGenType) throws Exception {
        Path project = createProject(codeGenType);
        if ("vue_project".equals(codeGenType)) Files.writeString(project.resolve("index.html"), "Vue 源码入口");
        var missingPreview = new MockHttpServletResponse();
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), assertThrows(BusinessException.class,
                () -> service.downloadAppCode(APP_ID, OWNER, true, missingPreview)).getCode());
        assertNull(missingPreview.getHeader("Content-Disposition"));
        verify(mapper, never()).incrementDownloadCount(APP_ID);

        createPreview(project, codeGenType);
        Files.writeString(project.resolve(".env"), "不应进入压缩包");
        var response = new MockHttpServletResponse();
        service.downloadAppCode(APP_ID, OWNER, true, response);
        assertEquals(200, response.getStatus());
        assertEquals("application/zip", response.getContentType());
        assertEquals("attachment; filename=\"" + APP_ID + ".zip\"", response.getHeader("Content-Disposition"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals(Set.of("index.html"), zipEntries(response));
        verify(mapper, times(1)).incrementDownloadCount(APP_ID);

        service.downloadAppCode(APP_ID, OWNER, true, new MockHttpServletResponse());
        verify(mapper, times(2)).incrementDownloadCount(APP_ID);
    }

    @Test
    void headAndUnauthorizedRequestsDoNotCountAsDownloads() throws IOException {
        Path project = createProject("html");
        createPreview(project, "html");
        var headResponse = new MockHttpServletResponse();
        service.downloadAppCode(APP_ID, OWNER, false, headResponse);
        assertEquals("application/zip", headResponse.getContentType());
        assertEquals(0, headResponse.getContentAsByteArray().length);

        User other = User.builder().id(3L).userRole("admin").build();
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), assertThrows(BusinessException.class,
                () -> service.downloadAppCode(APP_ID, other, true, new MockHttpServletResponse())).getCode());
        verify(mapper, never()).incrementDownloadCount(APP_ID);
    }

    @Test
    void failedResponseIsNotCounted() throws IOException {
        Path project = createProject("html");
        createPreview(project, "html");
        var response = mock(jakarta.servlet.http.HttpServletResponse.class);
        when(response.getOutputStream()).thenThrow(new IOException("broken connection"));

        assertThrows(BusinessException.class, () -> service.downloadAppCode(APP_ID, OWNER, true, response));
        verify(mapper, never()).incrementDownloadCount(APP_ID);
    }

    private Path createProject(String codeGenType) throws IOException {
        when(mapper.selectOneById(APP_ID)).thenReturn(App.builder().id(APP_ID).userId(OWNER.getId())
                .codeGenType(codeGenType).generationStatus("completed").build());
        return Files.createDirectory(outputRoot.resolve(codeGenType + "_" + APP_ID));
    }

    private void createPreview(Path project, String codeGenType) throws IOException {
        Path preview = "vue_project".equals(codeGenType) ? Files.createDirectory(project.resolve("dist")) : project;
        Files.writeString(preview.resolve("index.html"), "预览页面");
    }

    private Set<String> zipEntries(MockHttpServletResponse response) throws IOException {
        Set<String> entries = new HashSet<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) entries.add(entry.getName());
        }
        return entries;
    }
}
