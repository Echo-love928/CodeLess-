package com.hjq.CodeLess.service;

import cn.hutool.core.io.FileUtil;
import com.hjq.CodeLess.constant.AppConstant;
import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.exception.ErrorCode;
import com.hjq.CodeLess.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

/** Published files are separate from editable previews. */
@Service
@Slf4j
public class AppDeploymentStorage {
    private final Path outputRoot;
    private final Path deployRoot;
    private final Path workRoot;

    public AppDeploymentStorage() {
        this(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR), Path.of(AppConstant.CODE_DEPLOY_ROOT_DIR));
    }

    AppDeploymentStorage(Path outputRoot, Path deployRoot) {
        this.outputRoot = outputRoot.toAbsolutePath().normalize();
        this.deployRoot = deployRoot.toAbsolutePath().normalize();
        this.workRoot = this.deployRoot.resolveSibling(this.deployRoot.getFileName() + ".work");
    }

    public void publish(String codeGenType, Long appId, String deployKey) {
        publish(codeGenType, appId, deployKey, () -> {});
    }

    public Path requireSourceProject(String codeGenType, Long appId) {
        if (appId == null || appId <= 0 || codeGenType == null || !codeGenType.matches("[a-z_]+")) {
            throw new IllegalArgumentException("应用目录无效");
        }
        Path source = outputRoot.resolve(codeGenType + "_" + appId);
        if (!Files.isRegularFile(source.resolve("index.html"))
                || (CodeGenTypeEnum.VUE_PROJECT.getValue().equals(codeGenType) && Files.isSymbolicLink(source))) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "应用代码不存在，请先完成生成");
        }
        return source;
    }

    public void publish(String codeGenType, Long appId, String deployKey, Runnable persist) {
        Path project = requireSourceProject(codeGenType, appId);
        boolean vue = CodeGenTypeEnum.VUE_PROJECT.getValue().equals(codeGenType);
        Path source = vue ? project.resolve("dist") : project;
        if (vue && (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(source.resolve("index.html"), LinkOption.NOFOLLOW_LINKS))) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Vue 项目构建完成但缺少 dist/index.html");
        }
        Path target = deploymentPath(deployKey);
        Path staging = workPath("staging-");
        Path backup = workPath("backup-");
        try {
            Files.createDirectories(deployRoot);
            Files.createDirectories(workRoot);
            Files.createDirectory(staging);
            if (vue) {
                try (var artifacts = Files.walk(source)) {
                    if (artifacts.anyMatch(Files::isSymbolicLink)) {
                        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Vue 构建产物不能包含符号链接");
                    }
                }
            }
            FileUtil.copyContent(source.toFile(), staging.toFile(), true);
            if (Files.exists(target)) Files.move(target, backup);
            try {
                Files.move(staging, target);
                persist.run();
            } catch (IOException | RuntimeException error) {
                deleteTree(target);
                if (Files.exists(backup)) Files.move(backup, target);
                throw error;
            }
            cleanup(backup);
        } catch (IOException error) {
            log.error("发布应用文件失败", error);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "发布应用文件失败");
        } finally {
            cleanup(staging);
        }
    }

    public void pause(String deployKey) {
        pause(deployKey, () -> {});
    }

    public void pause(String deployKey, Runnable persist) {
        Path target = deploymentPath(deployKey);
        Path backup = workPath("backup-");
        try {
            Files.createDirectories(workRoot);
            if (Files.exists(target)) Files.move(target, backup);
            try {
                persist.run();
            } catch (RuntimeException error) {
                if (Files.exists(backup)) Files.move(backup, target);
                throw error;
            }
            cleanup(backup);
        } catch (IOException error) {
            log.error("撤下应用文件失败", error);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "下线失败，无法撤下部署文件");
        }
    }

    private void cleanup(Path path) {
        try {
            deleteTree(path);
        } catch (IOException error) {
            log.warn("清理临时发布目录失败：{}", path, error);
        }
    }

    private Path workPath(String prefix) {
        // Backups must never remain under the web server's document root, even if cleanup fails.
        if (Files.isSymbolicLink(workRoot)) throw new IllegalArgumentException("临时部署目录无效");
        return workRoot.resolve(prefix + UUID.randomUUID());
    }

    private Path deploymentPath(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("部署标识无效");
        }
        Path path = deployRoot.resolve(key).normalize();
        if (!deployRoot.equals(path.getParent()) || Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("部署目录无效");
        }
        return path;
    }

    private void deleteTree(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!deployRoot.equals(normalized.getParent()) && !workRoot.equals(normalized.getParent())) {
            throw new IOException("拒绝清理部署目录以外的路径");
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        // walkFileTree does not follow symbolic links.
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
