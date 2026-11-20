package com.hjq.CodeLess.service.impl;

import cn.hutool.core.util.StrUtil;
import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.exception.ErrorCode;
import com.hjq.CodeLess.exception.ThrowUtils;
import com.hjq.CodeLess.manager.CosManager;
import com.hjq.CodeLess.service.ScreenshotService;
import com.hjq.CodeLess.utils.WebScreenshotUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class ScreenshotServiceImpl implements ScreenshotService {

    private final CosManager cosManager;
    private final WebScreenshotUtils webScreenshots;

    @Override
    public String generateAndUploadScreenshot(String webUrl) {
        ThrowUtils.throwIf(StrUtil.isBlank(webUrl), ErrorCode.PARAMS_ERROR, "网页URL不能为空");
        log.info("开始生成网页截图，URL: {}", webUrl);
        Path screenshot = null;
        try {
            screenshot = Files.createTempFile("codeless-deploy-screenshot-", ".png");
            webScreenshots.capture(webUrl, screenshot);
            String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            String cosKey = "/screenshots/%s/%s.png".formatted(datePath, UUID.randomUUID());
            String cosUrl = cosManager.uploadFile(cosKey, screenshot.toFile());
            ThrowUtils.throwIf(StrUtil.isBlank(cosUrl), ErrorCode.OPERATION_ERROR, "截图上传对象存储失败");
            log.info("网页截图生成并上传成功: {} -> {}", webUrl, cosUrl);
            return cosUrl;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "网页截图被中断");
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "网页截图失败：" + e.getMessage());
        } finally {
            if (screenshot != null) {
                try {
                    Files.deleteIfExists(screenshot);
                } catch (IOException e) {
                    log.warn("清理本地截图失败：{}", screenshot, e);
                }
            }
        }
    }
}
