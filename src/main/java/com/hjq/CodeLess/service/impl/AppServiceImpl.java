package com.hjq.CodeLess.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.hjq.CodeLess.constant.AppConstant;
import com.hjq.CodeLess.core.AiCodeGeneratorFacade;
import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.exception.ErrorCode;
import com.hjq.CodeLess.exception.ThrowUtils;
import com.hjq.CodeLess.mapper.AppMapper;
import com.hjq.CodeLess.model.dto.app.AppQueryRequest;
import com.hjq.CodeLess.model.entity.App;
import com.hjq.CodeLess.model.entity.User;
import com.hjq.CodeLess.model.enums.CodeGenTypeEnum;
import com.hjq.CodeLess.model.vo.AppVO;
import com.hjq.CodeLess.model.vo.UserVO;
import com.hjq.CodeLess.service.AppService;
import com.hjq.CodeLess.service.AppCoverGeneratorService;
import com.hjq.CodeLess.service.AppGenerationService;
import com.hjq.CodeLess.service.AppDeploymentStorage;
import com.hjq.CodeLess.service.UserService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 应用 服务层实现。
 *
 * @author <a href="https://github.com/Echo-love928?tab=repositories">CodeLess</a>
 */
@Service
@Slf4j
public class AppServiceImpl extends ServiceImpl<AppMapper, App>  implements AppService {

    @Resource
    private UserService userService;

    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;

    @Resource
    private AppCoverGeneratorService appCoverGeneratorService;

    @Resource
    private AppGenerationService appGenerationService;

    @Resource
    private AppDeploymentStorage appDeploymentStorage;

    @Value("${app.deploy.base-url}")
    private String deployBaseUrl;

    @Override
    public Flux<String> chatToGenCode(Long appId, String message, User loginUser) {
        // 1. 参数校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "用户消息不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        // 2. 查询应用信息
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 3. 验证用户是否有权限访问该应用，仅本人可以生成代码
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");
        }
        // 4. 获取应用的代码生成类型
        String codeGenTypeStr = app.getCodeGenType();
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenTypeStr);
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的代码生成类型");
        }
        return appGenerationService.generate(appId, () -> {
            Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream(message, codeGenTypeEnum, appId);
            Mono<String> coverTask = Mono.<String>fromRunnable(() -> generateAndSaveAppCover(appId, codeGenTypeStr))
                    .subscribeOn(Schedulers.boundedElastic());
            // Flush SSE headers after task registration so stop is available before the first model token.
            return Flux.just("").concatWith(codeStream).concatWith(coverTask);
        }, status -> updateGenerationStatus(appId, status));
    }

    private synchronized void updateGenerationStatus(Long appId, String status) {
        App update = new App();
        update.setId(appId);
        update.setGenerationStatus(status);
        ThrowUtils.throwIf(!updateById(update), ErrorCode.OPERATION_ERROR, "保存生成状态失败");
    }

    @Override
    public boolean stopGeneration(Long appId, User loginUser) {
        getOwnedApp(appId, loginUser);
        appGenerationService.stop(appId);
        // Idempotent: a generation may have completed just before the stop request.
        return true;
    }

    private App getOwnedApp(Long appId, User loginUser) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 无效");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        App app = getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!app.getUserId().equals(loginUser.getId()), ErrorCode.NO_AUTH_ERROR, "仅应用所有者可以执行此操作");
        return app;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverGenerationStatuses() {
        // This project runs one backend instance. A process restart interrupts its active generations.
        for (App app : list(QueryWrapper.create().in("generationStatus", "unknown", "generating"))) {
            boolean hasCode = new File(AppConstant.CODE_OUTPUT_ROOT_DIR,
                    app.getCodeGenType() + "_" + app.getId() + "/index.html").isFile();
            updateGenerationStatus(app.getId(), "generating".equals(app.getGenerationStatus())
                    ? "failed" : hasCode ? "completed" : "pending");
        }
    }

    /**
     * 生成应用封面并更新数据库。封面失败不应让代码生成失败。
     */
    private void generateAndSaveAppCover(Long appId, String codeGenType) {
        try {
            appCoverGeneratorService.generateCover(appId, codeGenType).ifPresent(coverUrl -> {
                App updateApp = new App();
                updateApp.setId(appId);
                updateApp.setCover(coverUrl);
                boolean updated = this.updateById(updateApp);
                if (!updated) {
                    log.warn("应用 {} 封面已生成，但数据库 cover 字段更新失败", appId);
                }
            });
        } catch (Exception e) {
            log.warn("应用 {} 自动封面处理失败：{}", appId, e.getMessage(), e);
        }
    }

    @Override
    public synchronized String deployApp(Long appId, User loginUser) {
        // 1. 参数校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        // 2. 查询应用信息
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 3. 验证用户是否有权限部署该应用，仅本人可以部署
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限部署该应用");
        }
        ThrowUtils.throwIf("generating".equals(app.getGenerationStatus()), ErrorCode.OPERATION_ERROR, "请先等待生成完成或停止生成");
        // 4. 检查是否已有 deployKey
        String deployKey = app.getDeployKey();
        // 没有则生成 6 位 deployKey（大小写字母 + 数字）
        if (StrUtil.isBlank(deployKey)) {
            deployKey = RandomUtil.randomString(6);
        }
        // 8. 更新应用的 deployKey 和部署时间
        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setDeployKey(deployKey);
        updateApp.setDeploymentStatus("online");
        updateApp.setDeployedTime(LocalDateTime.now());
        appDeploymentStorage.publish(app.getCodeGenType(), appId, deployKey, () ->
                ThrowUtils.throwIf(!this.updateById(updateApp), ErrorCode.OPERATION_ERROR, "更新应用部署信息失败"));
        // 9. 返回可访问的 URL
        return String.format("%s/%s/", StrUtil.removeSuffix(deployBaseUrl.trim(), "/"), deployKey);
    }

    @Override
    public synchronized boolean undeployApp(Long appId, User loginUser) {
        App app = getOwnedApp(appId, loginUser);
        App update = new App();
        update.setId(appId);
        update.setDeploymentStatus("offline");
        Runnable persist = () -> ThrowUtils.throwIf(!updateById(update), ErrorCode.OPERATION_ERROR, "保存部署状态失败");
        if (StrUtil.isNotBlank(app.getDeployKey())) appDeploymentStorage.pause(app.getDeployKey(), persist);
        else persist.run();
        return true;
    }


    @Override
    public AppVO getAppVO(App app) {
        if (app == null) {
            return null;
        }
        AppVO appVO = new AppVO();
        BeanUtil.copyProperties(app, appVO);
        // 关联查询用户信息
        Long userId = app.getUserId();
        if (userId != null) {
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            appVO.setUser(userVO);
        }
        return appVO;
    }

    @Override
    public List<AppVO> getAppVOList(List<App> appList) {
        if (CollUtil.isEmpty(appList)) {
            return new ArrayList<>();
        }
        // 批量获取用户信息，避免 N+1 查询问题
        Set<Long> userIds = appList.stream()
                .map(App::getUserId)
                .collect(Collectors.toSet());
        Map<Long, UserVO> userVOMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, userService::getUserVO));
        return appList.stream().map(app -> {
            AppVO appVO = new AppVO();
            BeanUtil.copyProperties(app, appVO);
            UserVO userVO = userVOMap.get(app.getUserId());
            appVO.setUser(userVO);
            return appVO;
        }).collect(Collectors.toList());
    }


    @Override
    public QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest) {
        if (appQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = appQueryRequest.getId();
        String appName = appQueryRequest.getAppName();
        String cover = appQueryRequest.getCover();
        String initPrompt = appQueryRequest.getInitPrompt();
        String codeGenType = appQueryRequest.getCodeGenType();
        String deployKey = appQueryRequest.getDeployKey();
        Integer priority = appQueryRequest.getPriority();
        Long userId = appQueryRequest.getUserId();
        String sortField = appQueryRequest.getSortField();
        String sortOrder = appQueryRequest.getSortOrder();
        return QueryWrapper.create()
                .eq("id", id)
                .like("appName", appName)
                .like("cover", cover)
                .like("initPrompt", initPrompt)
                .eq("codeGenType", codeGenType)
                .eq("deployKey", deployKey)
                .eq("priority", priority)
                .eq("userId", userId)
                .orderBy(sortField, "ascend".equals(sortOrder));
    }
}
