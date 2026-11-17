package com.hjq.CodeLess.service;

import com.hjq.CodeLess.ai.AiAppNameGeneratorService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

@Service
@Slf4j
public class AppNameGeneratorService {
    @Resource
    private AiAppNameGeneratorService aiAppNameGeneratorService;

    public String generateName(String initPrompt) {
        String fallback = abbreviate(initPrompt.strip().replaceAll("\\s+", " "), 20);
        return Mono.fromCallable(() -> normalize(aiAppNameGeneratorService.generateAppName(initPrompt), fallback))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(8))
                .onErrorResume(error -> {
                    log.warn("AI 应用命名失败，使用需求摘要：{}", error.getClass().getSimpleName());
                    return Mono.just(fallback);
                }).block();
    }

    private String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String name = value.strip().split("\\R", 2)[0]
                .replaceAll("^(应用名称|名称)\\s*[:：]\\s*", "")
                .replaceAll("^[\\s\"'“”‘’`#]+|[\\s\"'“”‘’`]+$", "").strip();
        return name.isBlank() ? fallback : abbreviate(name, 20);
    }

    private String abbreviate(String value, int length) {
        return value.substring(0, value.offsetByCodePoints(0, Math.min(value.codePointCount(0, value.length()), length)));
    }
}
