package com.hjq.CodeLess.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.hjq.CodeLess.constant.AppConstant;
import com.hjq.CodeLess.model.enums.CodeGenTypeEnum;
import com.hjq.CodeLess.utils.WebScreenshotUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

/**
 * 使用本机 Chrome / Edge 的无头模式为已生成的应用截取首页封面。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AppCoverGeneratorService {

    private static final String COVER_FILE_NAME = "cover.png";

    @Value("${app.cover.enabled:true}")
    private boolean enabled;

    @Value("${app.preview.base-url}")
    private String previewBaseUrl;

    private final WebScreenshotUtils webScreenshots;

    /**
     * 截取应用预览页，并返回可以直接保存到 app.cover 的公开地址。
     * 截图失败时返回 empty，避免影响已经成功生成的应用代码。
     */
    public Optional<String> generateCover(Long appId, String codeGenType) {
        if (!enabled) {
            log.debug("应用封面自动截图已关闭");
            return Optional.empty();
        }
        if (appId == null || StrUtil.isBlank(codeGenType)) {
            log.warn("跳过应用封面截图：应用 ID 或生成类型为空");
            return Optional.empty();
        }

        // Vue 源码入口需要 Vite。项目部署后由截图服务截取实际发布的页面。
        if (CodeGenTypeEnum.VUE_PROJECT.getValue().equals(codeGenType)) {
            return Optional.empty();
        }

        String staticKey = codeGenType + "_" + appId;
        Path appDir = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, staticKey).toAbsolutePath().normalize();
        if (!Files.isDirectory(appDir)) {
            log.warn("应用 {} 的生成目录不存在，无法生成封面：{}", appId, appDir);
            return Optional.empty();
        }

        Path temporaryCover = appDir.resolve("cover-" + UUID.randomUUID() + ".png");
        Path finalCover = appDir.resolve(COVER_FILE_NAME);
        String previewUrl = stripTrailingSlash(previewBaseUrl) + "/" + staticKey + "/";

        try {
            webScreenshots.capture(appDir.resolve("index.html").toUri().toString(), temporaryCover);
            Files.move(temporaryCover, finalCover, StandardCopyOption.REPLACE_EXISTING);
            String coverUrl = previewUrl + COVER_FILE_NAME + "?v=" + System.currentTimeMillis();
            log.info("应用 {} 封面生成成功：{}", appId, finalCover);
            return Optional.of(coverUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("应用 {} 封面截图失败：{}", appId, e.getMessage(), e);
            return Optional.empty();
        } finally {
            FileUtil.del(temporaryCover.toFile());
        }
    }

    private String stripTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
