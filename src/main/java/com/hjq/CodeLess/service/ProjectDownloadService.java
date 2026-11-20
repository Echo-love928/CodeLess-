package com.hjq.CodeLess.service;

import com.hjq.CodeLess.model.entity.User;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public interface ProjectDownloadService {
    void downloadAppCode(Long appId, User user, boolean includeBody, HttpServletResponse response) throws IOException;
}
