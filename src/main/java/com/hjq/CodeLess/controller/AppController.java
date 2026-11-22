package com.hjq.CodeLess.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hjq.CodeLess.ai.ratelimter.annotation.RateLimit;
import com.hjq.CodeLess.ai.ratelimter.enums.RateLimitType;
import com.hjq.CodeLess.annotation.AuthCheck;
import com.hjq.CodeLess.common.BaseResponse;
import com.hjq.CodeLess.common.DeleteRequest;
import com.hjq.CodeLess.common.ResultUtils;
import com.hjq.CodeLess.constant.UserConstant;
import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.exception.ErrorCode;
import com.hjq.CodeLess.exception.ThrowUtils;
import com.hjq.CodeLess.model.dto.app.*;
import com.hjq.CodeLess.model.entity.User;
import com.hjq.CodeLess.model.vo.AppVO;
import com.hjq.CodeLess.service.*;
import com.mybatisflex.core.paginate.Page;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import com.hjq.CodeLess.model.entity.App;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * 应用 控制层。
 *
 * @author <a href="https://github.com/Echo-love928?tab=repositories">CodeLess</a>
 */
@RestController
@RequestMapping("/app")
public class AppController {

    @Resource
    private AppService appService;

    @Resource
    private UserService userService;

    @Resource
    private ProjectDownloadService projectDownloadService;

    @Resource private AppAccessPolicy appAccessPolicy;
    @Resource private AppMetadataService appMetadataService;
    @Resource private AppSearchService appSearchService;
    @Resource private AppBrowseLimiter appBrowseLimiter;

    /**
     * 应用聊天生成代码（流式 SSE）
     *
     * @param appId   应用 ID
     * @param message 用户消息
     * @param request 请求对象
     * @return 生成结果流
     */
    @GetMapping(value = "/chat/gen/code", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(limitType = RateLimitType.USER, rate = 5, rateInterval = 60, message = "AI 对话请求过于频繁，请稍后再试")
    public Flux<ServerSentEvent<String>> chatToGenCode(@RequestParam Long appId,
                                                       @RequestParam String message,
                                                       HttpServletRequest request) {
        // 参数校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID无效");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "用户消息不能为空");
        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);
        // 调用服务生成代码（流式）
        Flux<String> contentFlux = appService.chatToGenCode(appId, message, loginUser);
        // 转换为 ServerSentEvent 格式
        return contentFlux
                .map(chunk -> {
                    // 将内容包装成JSON对象
                    Map<String, String> wrapper = Map.of("d", chunk);
                    String jsonData = JSONUtil.toJsonStr(wrapper);
                    return ServerSentEvent.<String>builder()
                            .data(jsonData)
                            .build();
                })
                .concatWith(Mono.just(
                        // 发送结束事件
                        ServerSentEvent.<String>builder()
                                .event("done")
                                .data("")
                                .build()
                ))
                .onErrorResume(error -> Flux.just(
                        ServerSentEvent.<String>builder()
                                .event(error instanceof CancellationException ? "stopped" : "error")
                                .data(JSONUtil.toJsonStr(Map.of(
                                        "message", error.getMessage() == null ? "代码生成失败" : error.getMessage()
                                )))
                                .build()
                ));
    }

    @PostMapping(value = "/chat/gen/code", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatToGenCodeWithBody(@RequestBody AppChatRequest body,
                                                               HttpServletRequest request) {
        return chatToGenCode(body.appId(), body.message(), request);
    }

    /**
     * 应用部署
     *
     * @param appDeployRequest 部署请求
     * @param request          请求
     * @return 部署 URL
     */
    @PostMapping("/deploy")
    public BaseResponse<String> deployApp(@RequestBody AppDeployRequest appDeployRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appDeployRequest == null, ErrorCode.PARAMS_ERROR);
        Long appId = appDeployRequest.getAppId();
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);
        // 调用服务部署应用
        String deployUrl = appService.deployApp(appId, loginUser);
        return ResultUtils.success(deployUrl);
    }

    @PostMapping("/chat/stop")
    public BaseResponse<Boolean> stopGeneration(@RequestBody AppDeployRequest body, HttpServletRequest request) {
        ThrowUtils.throwIf(body == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(appService.stopGeneration(body.getAppId(), userService.getLoginUser(request)));
    }

    @PostMapping("/undeploy")
    public BaseResponse<Boolean> undeployApp(@RequestBody AppDeployRequest body, HttpServletRequest request) {
        ThrowUtils.throwIf(body == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(appService.undeployApp(body.getAppId(), userService.getLoginUser(request)));
    }

    /**
     * 下载应用代码
     *
     * @param appId    应用ID
     * @param request  请求
     * @param response 响应
     */
    @GetMapping("/download/{appId}")
    public void downloadAppCode(@PathVariable Long appId,
                                HttpServletRequest request,
                                HttpServletResponse response) throws IOException {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID无效");
        projectDownloadService.downloadAppCode(appId, userService.getLoginUser(request),
                HttpMethod.GET.matches(request.getMethod()), response);
    }



    /**
     * 创建应用
     *
     * @param appAddRequest 创建应用请求
     * @param request       请求
     * @return 应用 id
     */
    @PostMapping("/add")
    public BaseResponse<Long> addApp(@RequestBody AppAddRequest appAddRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appAddRequest == null, ErrorCode.PARAMS_ERROR);
        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);
        Long appId = appService.createApp(appAddRequest, loginUser);
        return ResultUtils.success(appId);
    }


    /**
     * 更新应用（用户只能更新自己的应用名称和封面）
     *
     * @param appUpdateRequest 更新请求
     * @param request          请求
     * @return 更新结果
     */
    @PostMapping("/update")
    public BaseResponse<Boolean> updateApp(@RequestBody AppUpdateRequest appUpdateRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appUpdateRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(appMetadataService.update(appUpdateRequest, null, userService.getLoginUser(request), false));
    }

    /**
     * 删除应用（用户只能删除自己的应用）
     *
     * @param deleteRequest 删除请求
     * @param request       请求
     * @return 删除结果
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteApp(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        long id = deleteRequest.getId();
        // 所有权检查、关联数据和文件清理由服务统一处理。
        boolean result = appService.deleteApp(id, loginUser);
        return ResultUtils.success(result);
    }

    /**
     * 根据 id 获取应用详情
     *
     * @param id      应用 id
     * @return 应用详情
     */
    @GetMapping("/get/vo")
    public BaseResponse<AppVO> getAppVOById(long id, HttpServletRequest request) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        User viewer = userService.getLoginUserOptional(request);
        limitBrowse(request, viewer, "detail", 120);
        App app = appService.getById(id);
        appAccessPolicy.requireView(app, viewer);
        return ResultUtils.success(appAccessPolicy.redact(appService.getAppVO(app), app, viewer));
    }


    /**
     * 分页获取当前用户创建的应用列表
     *
     * @param appQueryRequest 查询请求
     * @param request         请求
     * @return 应用列表
     */
    @PostMapping("/my/list/page/vo")
    public BaseResponse<Page<AppVO>> listMyAppVOByPage(@RequestBody AppQueryRequest appQueryRequest, HttpServletRequest request) {
        User viewer = userService.getLoginUser(request);
        return ResultUtils.success(toViews(appSearchService.search(appQueryRequest, viewer, AppSearchService.Scope.MINE, false), viewer));
    }

    /**
     * 分页获取精选应用列表
     *
     * @param appQueryRequest 查询请求
     * @return 精选应用列表
     */
    @PostMapping("/good/list/page/vo")
    public BaseResponse<Page<AppVO>> listGoodAppVOByPage(@RequestBody AppQueryRequest appQueryRequest, HttpServletRequest request) {
        User viewer = userService.getLoginUserOptional(request);
        limitBrowse(request, viewer, "search", 60);
        Page<App> page = appQueryRequest != null && appQueryRequest.getPageNum() <= 10
                ? appSearchService.loadFeaturedPage(appQueryRequest, appSearchService.searchFeaturedIds(appQueryRequest))
                : appSearchService.search(appQueryRequest, viewer, AppSearchService.Scope.PUBLIC, true);
        return ResultUtils.success(toViews(page, viewer));
    }

    /**
     * 管理员删除应用
     *
     * @param deleteRequest 删除请求
     * @return 删除结果
     */
    @PostMapping("/admin/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteAppByAdmin(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        long id = deleteRequest.getId();
        boolean result = appService.deleteApp(id, userService.getLoginUser(request));
        return ResultUtils.success(result);
    }

    /**
     * 管理员更新应用
     *
     * @param appAdminUpdateRequest 更新请求
     * @return 更新结果
     */
    @PostMapping("/admin/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateAppByAdmin(@RequestBody AppAdminUpdateRequest appAdminUpdateRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appAdminUpdateRequest == null, ErrorCode.PARAMS_ERROR);
        AppUpdateRequest patch = new AppUpdateRequest();
        BeanUtil.copyProperties(appAdminUpdateRequest, patch);
        return ResultUtils.success(appMetadataService.update(patch, appAdminUpdateRequest.getPriority(), userService.getLoginUser(request), true));
    }

    /**
     * 管理员分页获取应用列表
     *
     * @param appQueryRequest 查询请求
     * @return 应用列表
     */
    @PostMapping("/admin/list/page/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<AppVO>> listAppVOByPageByAdmin(@RequestBody AppQueryRequest appQueryRequest, HttpServletRequest request) {
        User viewer = userService.getLoginUser(request);
        return ResultUtils.success(toViews(appSearchService.search(appQueryRequest, viewer, AppSearchService.Scope.ADMIN, false), viewer));
    }

    /**
     * 管理员根据 id 获取应用详情
     *
     * @param id 应用 id
     * @return 应用详情
     */
    @GetMapping("/admin/get/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<AppVO> getAppVOByIdByAdmin(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        // 查询数据库
        App app = appService.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        // 获取封装类
        return ResultUtils.success(appService.getAppVO(app));
    }

    @PostMapping("/public/list/page/vo")
    public BaseResponse<Page<AppVO>> listPublicApps(@RequestBody AppQueryRequest query, HttpServletRequest request) {
        User viewer = userService.getLoginUserOptional(request);
        limitBrowse(request, viewer, "search", 60);
        return ResultUtils.success(toViews(appSearchService.search(query, viewer, AppSearchService.Scope.PUBLIC, false), viewer));
    }

    @PostMapping("/feature/apply")
    public BaseResponse<Boolean> applyFeatured(@RequestBody AppFeatureApplyRequest body, HttpServletRequest request) {
        ThrowUtils.throwIf(body == null, ErrorCode.PARAMS_ERROR);
        User owner = userService.getLoginUser(request);
        limitBrowse(request, owner, "feature-apply", 10);
        return ResultUtils.success(appMetadataService.apply(body.getAppId(), body.getReason(), owner));
    }

    @PostMapping("/feature/review")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> reviewFeatured(@RequestBody AppFeatureReviewRequest body, HttpServletRequest request) {
        ThrowUtils.throwIf(body == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(appMetadataService.review(body.getAppId(), body.getApproved(), body.getReviewMessage(), userService.getLoginUser(request)));
    }

    private void limitBrowse(HttpServletRequest request, User viewer, String category, int limit) {
        if (appAccessPolicy.isAdmin(viewer)) return;
        appBrowseLimiter.check(category + "-ip", request.getRemoteAddr(), limit);
        if (viewer != null) appBrowseLimiter.check(category + "-user", viewer.getId().toString(), limit);
    }

    private Page<AppVO> toViews(Page<App> page, User viewer) {
        Page<AppVO> result = new Page<>(page.getPageNumber(), page.getPageSize(), page.getTotalRow());
        List<AppVO> views = appService.getAppVOList(page.getRecords());
        for (int i = 0; i < views.size(); i++) appAccessPolicy.redact(views.get(i), page.getRecords().get(i), viewer);
        result.setRecords(views);
        return result;
    }
}
