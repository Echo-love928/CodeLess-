package com.hjq.CodeLess.service;

import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.mapper.AppVersionRepository;
import com.hjq.CodeLess.model.entity.App;
import com.hjq.CodeLess.model.entity.User;
import com.hjq.CodeLess.model.enums.CodeGenTypeEnum;
import com.hjq.CodeLess.model.vo.AppVersionVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import reactor.core.publisher.Flux;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AppVersionServiceTest {
    private final AppVersionRepository repository = mock(AppVersionRepository.class);
    private final AppVersionStorage storage = mock(AppVersionStorage.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final AppGenerationService generation = new AppGenerationService();
    private final AppVersionStorage.Activation activation = mock(AppVersionStorage.Activation.class);
    private final App app = App.builder().id(1L).userId(2L).codeGenType("multi_file").version(0).build();
    private AppVersionService service;
    private final User owner = User.builder().id(2L).build();
    private final Path snapshot = Path.of("snapshot");

    @BeforeEach void setup() {
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(repository.findApp(eq(1L), anyBoolean())).thenReturn(app);
        when(repository.nextVersion(1L)).thenReturn(1);
        when(storage.saveGenerated(eq(1L), any(), anyInt(), any())).thenReturn(snapshot);
        when(storage.activate(eq(1L), eq("multi_file"), any())).thenReturn(activation);
        when(storage.versionPath(eq(1L), eq("multi_file"), anyInt())).thenReturn(snapshot);
        service = new AppVersionService(repository, storage, generation, manager);
    }

    @Test void firstGenerationStartsAtOneAndCommitsSnapshot() {
        service.saveGeneratedCode(new Object(), CodeGenTypeEnum.MULTI_FILE, 1L, "first");
        verify(repository).insert(1L, 1, null, "first", "generated");
        verify(repository).setCurrent(1L, 1, false);
        verify(activation).commit();
    }

    @Test void existingUnversionedCodeIsPreservedBeforeGeneratingAnotherVersion() {
        when(storage.hasPreview(1L, "multi_file")).thenReturn(true);
        when(storage.captureExisting(1L, "multi_file", 1)).thenReturn(Path.of("baseline"));
        when(repository.nextVersion(1L)).thenReturn(1, 2);
        service.saveGeneratedCode(new Object(), CodeGenTypeEnum.MULTI_FILE, 1L, "second");
        verify(repository).insert(1L, 1, null, null, "imported");
        verify(repository).insert(1L, 2, 1, "second", "generated");
        verify(repository).setCurrent(1L, 2, false);
    }

    @Test void generationAfterRestoreUsesMaximumHistoryInsteadOfOverwriting() {
        app.setVersion(3);
        when(repository.findVersion(1L, 1)).thenReturn(new AppVersionVO());
        doAnswer(call -> { app.setVersion(call.getArgument(1)); return null; }).when(repository).setCurrent(eq(1L), anyInt(), anyBoolean());
        assertTrue(service.restore(1L, 1, owner));
        assertEquals(1, app.getVersion());
        when(repository.nextVersion(1L)).thenReturn(4);
        service.saveGeneratedCode(new Object(), CodeGenTypeEnum.MULTI_FILE, 1L, "continue");
        verify(repository).insert(1L, 4, 1, "continue", "generated");
        assertEquals(4, app.getVersion());
    }

    @Test void generationUsesSelectedVersionAsItsCodeContext() {
        app.setVersion(2);
        when(storage.readFiles(1L, "multi_file", 2)).thenReturn(java.util.Map.of("index.html", "selected version code"));
        String prompt = service.generationPrompt(1L, "change header");
        assertTrue(prompt.contains("V2"));
        assertTrue(prompt.contains("selected version code"));
        assertTrue(prompt.contains("change header"));
    }

    @Test void vueGenerationSeedsTheSelectedVersionAndLetsToolsReadIt() {
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        app.setVersion(2);
        Path workspace = Path.of("workspace");

        String prompt = service.generationPrompt(1L, "change header", workspace);

        verify(storage).seedVueProjectWorkspace(1L, 2, workspace);
        verify(storage, never()).readFiles(anyLong(), anyString(), anyInt());
        assertTrue(prompt.contains("V2"));
        assertTrue(prompt.contains("readDir"));
        assertTrue(prompt.contains("change header"));
    }

    @Test void failedCommitRestoresPreviewAndRemovesUncommittedSnapshot() {
        doThrow(new IllegalStateException("commit failed")).when(manager).commit(any());
        assertThrows(IllegalStateException.class,
                () -> service.saveGeneratedCode(new Object(), CodeGenTypeEnum.MULTI_FILE, 1L, "failure"));
        verify(activation).close();
        verify(activation, never()).commit();
        verify(storage).discard(snapshot);
    }

    @Test void foreignUsersCannotReadOrRestoreVersions() {
        var other = User.builder().id(3L).userRole("user").build();
        assertThrows(BusinessException.class, () -> service.list(1L, other));
        assertThrows(BusinessException.class, () -> service.detail(1L, 1, other));
        assertThrows(BusinessException.class, () -> service.restore(1L, 1, other));
        verifyNoInteractions(storage);
    }

    @Test void restoreIsBlockedDuringGeneration() {
        var active = generation.generate(1L, Flux::never, ignored -> {}).subscribe();
        try {
            assertThrows(BusinessException.class, () -> service.restore(1L, 1, owner));
        } finally { active.dispose(); }
        verifyNoInteractions(storage);
    }
}
