package com.hjq.CodeLess.controller;

import com.hjq.CodeLess.common.BaseResponse;
import com.hjq.CodeLess.common.ResultUtils;
import com.hjq.CodeLess.exception.ErrorCode;
import com.hjq.CodeLess.exception.ThrowUtils;
import com.hjq.CodeLess.model.dto.app.AppVersionRestoreRequest;
import com.hjq.CodeLess.model.vo.AppVersionDetailVO;
import com.hjq.CodeLess.model.vo.AppVersionVO;
import com.hjq.CodeLess.service.AppVersionService;
import com.hjq.CodeLess.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/app/version")
public class AppVersionController {
    private final AppVersionService versions;
    private final UserService users;
    public AppVersionController(AppVersionService versions, UserService users) { this.versions = versions; this.users = users; }

    @GetMapping("/list")
    public BaseResponse<List<AppVersionVO>> list(@RequestParam Long appId, HttpServletRequest request) {
        return ResultUtils.success(versions.list(appId, users.getLoginUser(request)));
    }

    @GetMapping("/detail")
    public BaseResponse<AppVersionDetailVO> detail(@RequestParam Long appId, @RequestParam Integer version, HttpServletRequest request) {
        return ResultUtils.success(versions.detail(appId, version, users.getLoginUser(request)));
    }

    @PostMapping("/restore")
    public BaseResponse<Boolean> restore(@RequestBody AppVersionRestoreRequest body, HttpServletRequest request) {
        ThrowUtils.throwIf(body == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(versions.restore(body.getAppId(), body.getVersion(), users.getLoginUser(request)));
    }
}
