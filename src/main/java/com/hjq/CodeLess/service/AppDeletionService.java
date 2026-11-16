package com.hjq.CodeLess.service;

import com.hjq.CodeLess.constant.UserConstant;
import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.exception.ErrorCode;
import com.hjq.CodeLess.mapper.AppDeletionRepository;
import com.hjq.CodeLess.model.entity.App;
import com.hjq.CodeLess.model.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class AppDeletionService {
    private final AppDeletionRepository repository;
    private final AppCleanupStorage storage;
    private final AppGenerationService generation;
    private final TransactionTemplate transaction;

    public AppDeletionService(AppDeletionRepository repository, AppCleanupStorage storage,
                              AppGenerationService generation, PlatformTransactionManager manager) {
        this.repository = repository; this.storage = storage; this.generation = generation;
        transaction = new TransactionTemplate(manager);
    }

    public boolean delete(Long appId, User user) {
        if (appId == null || appId <= 0) throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 无效");
        authorize(repository.find(appId, false), user);
        return generation.withIdleApp(appId, () -> {
            var staged = new AtomicReference<AppCleanupStorage.Job>();
            boolean committed = false;
            try {
                transaction.executeWithoutResult(status -> {
                    App app = repository.find(appId, true);
                    authorize(app, user);
                    if (Integer.valueOf(1).equals(app.getIsDelete())) return;
                    staged.set(storage.prepare(app));
                    repository.deleteRelatedData(appId);
                });
                committed = true;
                if (staged.get() != null) {
                    try { storage.purge(staged.get()); }
                    catch (Exception failure) { log.warn("应用 {} 已删除，文件清理任务将自动重试", appId, failure); }
                }
                return true;
            } finally {
                if (staged.get() != null) {
                    try {
                        if (!committed) {
                            // A lost commit acknowledgement does not prove the transaction rolled back.
                            // Consult the persisted tombstone before restoring any publicly served files.
                            App persisted = repository.find(appId, false);
                            if (persisted == null || Integer.valueOf(1).equals(persisted.getIsDelete())) storage.purge(staged.get());
                            else storage.restore(staged.get());
                        }
                    } catch (Exception failure) {
                        log.error("应用 {} 删除结果或文件恢复尚未确认，将由恢复任务继续处理", appId, failure);
                    } finally { storage.release(staged.get()); }
                }
            }
        });
    }

    private void authorize(App app, User user) {
        if (user == null) throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        if (app == null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        if (!app.getUserId().equals(user.getId()) && !UserConstant.ADMIN_ROLE.equals(user.getUserRole())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只能删除自己的应用");
        }
    }

    @Scheduled(initialDelayString = "${app.cleanup.retry-delay-ms:60000}", fixedDelayString = "${app.cleanup.retry-delay-ms:60000}")
    public void recoverPending() {
        for (var job : storage.jobs()) {
            try {
                generation.withIdleApp(job.appId(), () -> transaction.execute(status -> {
                    App app = repository.find(job.appId(), true);
                    try {
                        if (app == null || Integer.valueOf(1).equals(app.getIsDelete())) storage.purge(job);
                        else storage.restore(job);
                    } catch (java.io.IOException failure) { throw new IllegalStateException("应用清理任务尚未完成", failure); }
                    return null;
                }));
            } catch (Exception failure) { log.warn("应用 {} 清理恢复失败，下次继续重试", job.appId(), failure); }
        }
    }
}
