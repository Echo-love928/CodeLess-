package com.hjq.CodeLess.utils;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** 使用已安装的 Chrome 或 Edge 截图，每个任务使用独立的浏览器配置目录。 */
@Component
@Slf4j
public class WebScreenshotUtils {

    @Value("${app.cover.browser-path:}")
    private String configuredBrowserPath;

    @Value("${app.cover.width:1200}")
    private int coverWidth;

    @Value("${app.cover.height:675}")
    private int coverHeight;

    @Value("${app.cover.timeout-seconds:20}")
    private long timeoutSeconds;

    @Value("${app.cover.render-wait-millis:3000}")
    private long renderWaitMillis;

    public void capture(String pageUrl, Path output) throws IOException, InterruptedException {
        Path browser = findBrowserExecutable()
                .orElseThrow(() -> new IOException("未找到 Chrome 或 Edge，可通过 BROWSER_PATH 指定浏览器"));
        Path profile = Files.createTempDirectory("codeless-cover-");
        Process process = null;
        try {
            process = new ProcessBuilder(buildScreenshotCommand(browser, output, profile, pageUrl))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            long timeout = Math.max(timeoutSeconds, 1);
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                throw new IOException("浏览器截图超时（" + timeout + " 秒）");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(output) || Files.size(output) == 0) {
                throw new IOException("浏览器未生成截图，退出码：" + process.exitValue());
            }
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            try {
                FileUtil.del(profile.toFile());
            } catch (RuntimeException e) {
                log.warn("清理截图浏览器目录失败：{}", profile, e);
            }
        }
    }

    private List<String> buildScreenshotCommand(Path browserPath, Path outputPath,
                                                 Path profilePath, String pageUrl) {
        List<String> command = new ArrayList<>();
        command.add(browserPath.toString());
        command.add("--headless=new");
        command.add("--disable-gpu");
        command.add("--disable-extensions");
        command.add("--disable-sync");
        command.add("--hide-scrollbars");
        command.add("--no-first-run");
        command.add("--no-default-browser-check");
        command.add("--run-all-compositor-stages-before-draw");
        command.add("--window-size=" + Math.max(coverWidth, 320) + "," + Math.max(coverHeight, 180));
        command.add("--virtual-time-budget=" + Math.max(renderWaitMillis, 0));
        command.add("--user-data-dir=" + profilePath);
        command.add("--screenshot=" + outputPath);
        command.add(pageUrl);
        return command;
    }

    private Optional<Path> findBrowserExecutable() {
        if (StrUtil.isNotBlank(configuredBrowserPath)) {
            Path configuredPath = Path.of(configuredBrowserPath.trim());
            if (Files.isRegularFile(configuredPath)) {
                return Optional.of(configuredPath);
            }
            log.warn("配置的浏览器不存在：{}", configuredPath);
        }

        List<String> candidates = List.of(
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
                System.getProperty("user.home") + "\\AppData\\Local\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
                "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
                "/usr/bin/google-chrome",
                "/usr/bin/chromium",
                "/usr/bin/chromium-browser",
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
        );
        return candidates.stream()
                .map(Path::of)
                .filter(Files::isRegularFile)
                .findFirst();
    }
}
