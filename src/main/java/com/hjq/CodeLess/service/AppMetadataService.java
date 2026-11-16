package com.hjq.CodeLess.service;

import cn.hutool.json.JSONUtil;
import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.exception.ErrorCode;
import com.hjq.CodeLess.model.dto.app.AppAddRequest;
import com.hjq.CodeLess.model.dto.app.AppUpdateRequest;
import com.hjq.CodeLess.model.entity.App;
import com.hjq.CodeLess.model.entity.User;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;

@Service
public class AppMetadataService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final AppGenerationService generation;
    private final AppDeploymentStorage deployment;
    private final AppVersionStorage files;
    private final AppAccessPolicy access;
    public AppMetadataService(JdbcTemplate jdbc, PlatformTransactionManager manager, AppGenerationService generation,
                              AppDeploymentStorage deployment, AppVersionStorage files, AppAccessPolicy access) {
        this.jdbc = jdbc; transaction = new TransactionTemplate(manager); this.generation = generation;
        this.deployment = deployment; this.files = files; this.access = access;
    }

    public void initialize(App app, AppAddRequest request) {
        app.setVisibility(request.getVisibility() == null ? "private" : AppMetadataRules.visibility(request.getVisibility()));
        app.setDescription(AppMetadataRules.text(request.getDescription(), 500, "应用描述"));
        app.setTags(JSONUtil.toJsonStr(AppMetadataRules.tags(request.getTags())));
        app.setFeatureStatus("none"); app.setPriority(0);
    }

    public boolean update(AppUpdateRequest patch, Integer priority, User viewer, boolean adminRoute) {
        if (patch == null || patch.getId() == null) throw invalid("应用 ID 不能为空");
        if (adminRoute && !access.isAdmin(viewer)) throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        App initial = find(patch.getId(), false); authorize(initial, viewer, adminRoute);
        return generation.withIdleApp(initial.getId(), () -> {
            App before = find(patch.getId(), false); authorize(before, viewer, adminRoute);
            String visibility = patch.getVisibility() == null ? before.getVisibility() : AppMetadataRules.visibility(patch.getVisibility());
            Runnable persist = () -> transaction.executeWithoutResult(status -> {
                App app = find(patch.getId(), true); authorize(app, viewer, adminRoute);
                if (patch.getAppName() != null) {
                    String name = AppMetadataRules.text(patch.getAppName(), 50, "应用名称");
                    if (name.isBlank()) throw invalid("应用名称不能为空"); app.setAppName(name);
                }
                if (patch.getCover() != null) app.setCover(AppMetadataRules.text(patch.getCover(), 512, "封面地址"));
                if (patch.getDescription() != null) app.setDescription(AppMetadataRules.text(patch.getDescription(), 500, "应用描述"));
                if (patch.getTags() != null) app.setTags(JSONUtil.toJsonStr(AppMetadataRules.tags(patch.getTags())));
                app.setVisibility(visibility);
                if (priority != null) {
                    if (!adminRoute || priority < 0 || priority > 999) throw invalid("优先级无效");
                    if (priority == 99) {
                        requireEligible(app); app.setFeatureStatus("approved"); app.setFeatureReviewedTime(LocalDateTime.now());
                    } else if ("approved".equals(app.getFeatureStatus())) app.setFeatureStatus("none");
                    app.setPriority(priority);
                }
                if ("private".equals(visibility)) {
                    if (priority != null && priority == 99) throw invalid("私有应用不能设为精选");
                    app.setPriority(0); app.setFeatureStatus("none"); app.setFeatureRequestMessage(null);
                    app.setFeatureReviewMessage(null); app.setFeatureRequestedTime(null); app.setFeatureReviewedTime(null);
                    app.setDeploymentStatus("offline"); app.setDeployedVersion(null);
                }
                persist(app);
            });
            // Persist inside the storage callback so a failed database commit restores the published files.
            if ("private".equals(visibility) && before.getDeployKey() != null && !before.getDeployKey().isBlank()) deployment.pause(before.getDeployKey(), persist);
            else persist.run();
            return true;
        });
    }

    public boolean apply(Long appId, String reason, User owner) {
        authorize(find(appId, false), owner, false);
        return generation.withIdleApp(appId, () -> transaction.execute(status -> {
            App app = find(appId, true); authorize(app, owner, false); requireEligible(app);
            if ("pending".equals(app.getFeatureStatus()) || "approved".equals(app.getFeatureStatus())) return true;
            app.setFeatureStatus("pending"); app.setFeatureRequestMessage(AppMetadataRules.text(reason, 500, "申请说明"));
            app.setFeatureReviewMessage(null); app.setFeatureRequestedTime(LocalDateTime.now()); app.setFeatureReviewedTime(null);
            app.setPriority(0); persist(app); return true;
        }));
    }

    public boolean review(Long appId, Boolean approved, String reviewMessage, User admin) {
        if (!access.isAdmin(admin)) throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        if (approved == null) throw invalid("请选择审核结果");
        return generation.withIdleApp(appId, () -> transaction.execute(status -> {
            App app = find(appId, true);
            if (!"pending".equals(app.getFeatureStatus())) throw invalid("该应用当前没有待审核申请");
            if (approved) requireEligible(app);
            app.setFeatureStatus(approved ? "approved" : "rejected"); app.setPriority(approved ? 99 : 0);
            app.setFeatureReviewMessage(AppMetadataRules.text(reviewMessage, 500, "审核说明"));
            app.setFeatureReviewedTime(LocalDateTime.now()); persist(app); return true;
        }));
    }

    private void requireEligible(App app) {
        if (!"public".equals(app.getVisibility())) throw invalid("请先将应用设为公开");
        if ("generating".equals(app.getGenerationStatus()) || !("completed".equals(app.getGenerationStatus()) || app.getVersion() != null && app.getVersion() > 0)
                || !files.hasPreview(app.getId(), app.getCodeGenType())) throw invalid("请先完成应用生成");
    }
    private void authorize(App app, User viewer, boolean admin) {
        if (viewer == null) throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        if (!(admin && access.isAdmin(viewer)) && !app.getUserId().equals(viewer.getId())) throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
    }
    private App find(Long id, boolean lock) {
        if (id == null || id <= 0) throw invalid("应用 ID 无效");
        var rows = jdbc.query("select * from app where id=? and isDelete=0" + (lock ? " for update" : ""), BeanPropertyRowMapper.newInstance(App.class), id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        return rows.get(0);
    }
    private void persist(App app) {
        int rows = jdbc.update("update app set appName=?,cover=?,description=?,tags=?,visibility=?,priority=?,featureStatus=?,"
                + "featureRequestMessage=?,featureReviewMessage=?,featureRequestedTime=?,featureReviewedTime=?,deploymentStatus=?,deployedVersion=?,editTime=CURRENT_TIMESTAMP where id=? and isDelete=0",
                app.getAppName(), app.getCover(), app.getDescription(), app.getTags(), app.getVisibility(), app.getPriority(), app.getFeatureStatus(),
                app.getFeatureRequestMessage(), app.getFeatureReviewMessage(), app.getFeatureRequestedTime(), app.getFeatureReviewedTime(),
                app.getDeploymentStatus(), app.getDeployedVersion(), app.getId());
        if (rows != 1) throw new BusinessException(ErrorCode.OPERATION_ERROR, "保存应用信息失败");
    }
    private BusinessException invalid(String message) { return new BusinessException(ErrorCode.PARAMS_ERROR, message); }
}
