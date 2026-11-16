package com.hjq.CodeLess.service;

import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.mapper.AppDeletionRepository;
import com.hjq.CodeLess.model.entity.App;
import com.hjq.CodeLess.model.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import reactor.core.publisher.Flux;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AppDeletionServiceTest {
    private final AppDeletionRepository repository = mock(AppDeletionRepository.class);
    private final AppCleanupStorage storage = mock(AppCleanupStorage.class);
    private final AppGenerationService generation = new AppGenerationService();
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final App app = App.builder().id(1L).userId(2L).isDelete(0).deployKey("abc123").build();
    private final User owner = User.builder().id(2L).userRole("user").build();
    private final AppCleanupStorage.Job job = new AppCleanupStorage.Job(Path.of("job"), 1L, "abc123");
    private AppDeletionService service;

    @BeforeEach void setup() {
        when(repository.find(eq(1L), anyBoolean())).thenReturn(app);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(storage.prepare(app)).thenReturn(job);
        service = new AppDeletionService(repository, storage, generation, manager);
    }

    @Test void deletionClearsRelatedDataBeforePurgingStagedFiles() throws Exception {
        assertTrue(service.delete(1L, owner));
        var order = inOrder(storage, repository, manager);
        order.verify(storage).prepare(app);
        order.verify(repository).deleteRelatedData(1L);
        order.verify(manager).commit(any());
        order.verify(storage).purge(job);
        verify(storage).release(job);
        verify(storage, never()).restore(any());
    }

    @Test void databaseFailureRestoresFilesWithoutPurging() throws Exception {
        doThrow(new IllegalStateException("database failed")).when(repository).deleteRelatedData(1L);
        assertThrows(IllegalStateException.class, () -> service.delete(1L, owner));
        verify(storage).restore(job);
        verify(storage).release(job);
        verify(storage, never()).purge(any());
    }

    @Test void commitFailureAlsoRestoresFiles() throws Exception {
        doThrow(new IllegalStateException("commit failed")).when(manager).commit(any());
        assertThrows(IllegalStateException.class, () -> service.delete(1L, owner));
        verify(storage).restore(job);
        verify(storage, never()).purge(any());
    }

    @Test void lostCommitAcknowledgementNeverRestoresFilesForAnAlreadyDeletedApp() throws Exception {
        doAnswer(call -> {
            app.setIsDelete(1);
            throw new IllegalStateException("commit acknowledgement lost");
        }).when(manager).commit(any());
        assertThrows(IllegalStateException.class, () -> service.delete(1L, owner));
        verify(storage).purge(job);
        verify(storage, never()).restore(any());
        verify(storage).release(job);
    }

    @Test void failedPurgeIsRecoveredAfterDeletion() throws Exception {
        doThrow(new IOException("file locked")).doNothing().when(storage).purge(job);
        assertTrue(service.delete(1L, owner));
        app.setIsDelete(1);
        when(storage.jobs()).thenReturn(List.of(job));
        service.recoverPending();
        verify(storage, times(2)).purge(job);
        verify(storage, never()).restore(any());
    }

    @Test void uncommittedCleanupIsRestoredAfterRestart() throws Exception {
        when(storage.jobs()).thenReturn(List.of(job));
        service.recoverPending();
        verify(storage).restore(job);
        verify(storage, never()).purge(any());
    }

    @Test void unauthorizedUserCannotTouchFilesOrDeleteRelatedRows() {
        assertThrows(BusinessException.class, () -> service.delete(1L, User.builder().id(3L).userRole("user").build()));
        verifyNoInteractions(storage, manager);
        verify(repository, never()).deleteRelatedData(anyLong());
    }

    @Test void administratorCanDeleteOtherUsersApplication() {
        assertTrue(service.delete(1L, User.builder().id(3L).userRole("admin").build()));
        verify(repository).deleteRelatedData(1L);
    }

    @Test void generatingApplicationIsRejectedWithoutMovingFiles() {
        var active = generation.generate(1L, Flux::never, ignored -> {}).subscribe();
        try { assertThrows(BusinessException.class, () -> service.delete(1L, owner)); }
        finally { active.dispose(); }
        verifyNoInteractions(storage, manager);
    }

    @Test void repeatedDeleteIsIdempotent() {
        app.setIsDelete(1);
        assertTrue(service.delete(1L, owner));
        verifyNoInteractions(storage);
        verify(repository, never()).deleteRelatedData(anyLong());
    }

    @Test void missingOrInvalidIdNeverTouchesFiles() {
        assertThrows(BusinessException.class, () -> service.delete(null, owner));
        assertThrows(BusinessException.class, () -> service.delete(0L, owner));
        when(repository.find(1L, false)).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.delete(1L, owner));
        verifyNoInteractions(storage);
    }
}
