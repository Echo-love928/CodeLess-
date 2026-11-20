package com.hjq.CodeLess.service.impl;

import cn.hutool.core.io.IORuntimeException;
import cn.hutool.core.util.ZipUtil;
import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.exception.ErrorCode;
import com.hjq.CodeLess.exception.ThrowUtils;
import com.hjq.CodeLess.mapper.AppMapper;
import com.hjq.CodeLess.model.entity.App;
import com.hjq.CodeLess.model.entity.User;
import com.hjq.CodeLess.service.AppResourceService;
import com.hjq.CodeLess.service.ProjectDownloadService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProjectDownloadServiceImpl implements ProjectDownloadService {
    private final AppMapper appMapper;
    private final AppResourceService resources;

    /**
     * 需要过滤的文件和目录名称
     */
    private static final Set<String> IGNORED_NAMES = Set.of(
            "node_modules",
            ".git",
            "dist",
            "build",
            ".DS_Store",
            ".env",
            "target",
            ".mvn",
            ".idea",
            ".vscode"
    );

    /**
     * 需要过滤的文件扩展名
     */
    private static final Set<String> IGNORED_EXTENSIONS = Set.of(
            ".log",
            ".tmp",
            ".cache"
    );

    @Override
    public void downloadAppCode(Long appId, User user, boolean includeBody, HttpServletResponse response) throws IOException {
        App app = appMapper.selectOneById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!app.getUserId().equals(user.getId()), ErrorCode.NO_AUTH_ERROR, "无权限下载该应用代码");
        ThrowUtils.throwIf("generating".equals(app.getGenerationStatus()), ErrorCode.OPERATION_ERROR, "请先等待生成完成或停止生成");
        Path projectPath = resources.requirePreviewDirectory(app);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + appId + ".zip\"");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (!includeBody) return;

        File projectDir = projectPath.toFile();
        FileFilter filter = file -> isPathAllowed(projectPath, file.toPath());
        try {
            ZipUtil.zip(StreamUtils.nonClosing(response.getOutputStream()), StandardCharsets.UTF_8, false, filter, projectDir);
            response.flushBuffer();
        } catch (IOException | IORuntimeException failure) {
            log.warn("应用 {} 的代码压缩或发送失败", appId, failure);
            if (response.isCommitted()) throw new IOException("发送应用代码压缩包失败", failure);
            response.resetBuffer();
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, null);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "项目打包下载失败");
        }

        try {
            if (appMapper.incrementDownloadCount(appId) != 1) {
                log.warn("应用 {} 已发送代码压缩包，更新下载次数时未找到应用", appId);
            }
        } catch (DataAccessException failure) {
            // 压缩包已发送，此时无法切换为 JSON 错误响应。
            log.error("应用 {} 已发送代码压缩包，记录下载次数失败", appId, failure);
        }
    }


    /**
     * 检查路径是否允许包含在压缩包中
     *
     * @param projectRoot 项目根目录
     * @param fullPath    完整路径
     * @return 是否允许
     */
    private boolean isPathAllowed(Path projectRoot, Path fullPath) {
        // 获取相对路径
        Path relativePath = projectRoot.relativize(fullPath);
        // 检查路径中的每一部分
        for (Path part : relativePath) {
            String partName = part.toString();
            // 检查是否在忽略名称列表中
            if (IGNORED_NAMES.contains(partName)) {
                return false;
            }
            // 检查文件扩展名
            if (IGNORED_EXTENSIONS.stream().anyMatch(partName::endsWith)) {
                return false;
            }
        }
        return true;
    }
}
