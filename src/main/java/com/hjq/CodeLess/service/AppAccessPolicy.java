package com.hjq.CodeLess.service;

import com.hjq.CodeLess.constant.UserConstant;
import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.exception.ErrorCode;
import com.hjq.CodeLess.model.entity.App;
import com.hjq.CodeLess.model.entity.User;
import com.hjq.CodeLess.model.vo.AppVO;
import org.springframework.stereotype.Service;

@Service
public class AppAccessPolicy {
    public boolean isAdmin(User user) { return user != null && UserConstant.ADMIN_ROLE.equals(user.getUserRole()); }
    public boolean canManage(App app, User user) {
        return app != null && user != null && (isAdmin(user) || app.getUserId().equals(user.getId()));
    }
    public boolean canView(App app, User user) {
        return app != null && !Integer.valueOf(1).equals(app.getIsDelete())
                && ("public".equals(app.getVisibility()) || canManage(app, user));
    }
    public void requireView(App app, User user) {
        if (!canView(app, user)) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在或不可访问");
    }
    public AppVO redact(AppVO view, App app, User viewer) {
        if (!canManage(app, viewer)) {
            view.setInitPrompt(null);
            view.setFeatureRequestMessage(null);
            view.setFeatureReviewMessage(null);
            if (!"approved".equals(view.getFeatureStatus())) view.setFeatureStatus("none");
            if (view.getUser() != null) {
                var user = new com.hjq.CodeLess.model.vo.UserVO();
                cn.hutool.core.bean.BeanUtil.copyProperties(view.getUser(), user);
                user.setUserAccount(null); view.setUser(user);
            }
        }
        return view;
    }
}
