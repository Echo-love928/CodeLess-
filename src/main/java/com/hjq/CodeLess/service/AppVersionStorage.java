package com.hjq.CodeLess.service;

import com.hjq.CodeLess.constant.AppConstant;
import com.hjq.CodeLess.core.saver.CodeFileSaverExecutor;
import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.exception.ErrorCode;
import com.hjq.CodeLess.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@Service
@Slf4j
public class AppVersionStorage {
    private final Path outputRoot;
    private final Path versionsRoot;

    public AppVersionStorage() {
        this(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR), Path.of(System.getProperty("user.dir"), "tmp/code_versions"));
    }

    AppVersionStorage(Path outputRoot, Path versionsRoot) {
        this.outputRoot = outputRoot.toAbsolutePath().normalize();
        this.versionsRoot = versionsRoot.toAbsolutePath().normalize();
    }

    public Path saveGenerated(Long appId, CodeGenTypeEnum type, int version, Object result) {
        if (type == CodeGenTypeEnum.VUE_PROJECT) {
            Path source = (Path) result;
            if (!Files.isRegularFile(source.resolve("package.json"))) throw error("Vue 工程缺少 package.json");
            return snapshot(appId, type.getValue(), version, stage -> copy(source, stage));
        }
        return snapshot(appId, type.getValue(), version,
                stage -> CodeFileSaverExecutor.saveToDirectory(result, type, stage.toFile()));
    }

    public Path createVueProjectWorkspace() {
        rejectSymlinks(versionsRoot);
        try {
            Files.createDirectories(versionsRoot);
            return Files.createTempDirectory(versionsRoot, ".vue-generation-");
        } catch (IOException exception) {
            log.error("创建 Vue 工程临时目录失败", exception);
            throw error("创建 Vue 工程临时目录失败");
        }
    }

    public boolean hasPreview(Long appId, String type) {
        return Files.isRegularFile(outputRoot.resolve(key(appId, type)).resolve("index.html"));
    }

    public Path captureExisting(Long appId, String type, int version) {
        return snapshot(appId, type, version, stage -> copy(outputRoot.resolve(key(appId, type)), stage));
    }

    private Path snapshot(Long appId, String type, int version, Consumer<Path> writer) {
        Path target = versionPath(appId, type, version);
        if (Files.exists(target)) throw error("版本已存在，不能覆盖历史代码");
        Path stage = target.getParent().resolve(".staging-" + UUID.randomUUID());
        try {
            Files.createDirectories(stage);
            writer.accept(stage);
            if (!Files.isRegularFile(stage.resolve("index.html"))) throw error("版本缺少 index.html");
            Files.move(stage, target);
            return target;
        } catch (IOException exception) {
            log.error("保存版本快照失败", exception);
            throw error("保存版本代码失败");
        } finally {
            discard(stage);
        }
    }

    public Path versionPath(Long appId, String type, int version) {
        if (version <= 0) throw error("版本号必须大于 0");
        Path path = versionsRoot.resolve(key(appId, type)).resolve("v" + version);
        rejectSymlinks(path);
        return path;
    }

    public Map<String, String> readFiles(Long appId, String type, int version) {
        Path directory = versionPath(appId, type, version);
        if (!Files.isRegularFile(directory.resolve("index.html"))) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "版本代码不存在");
        Map<String, String> result = new LinkedHashMap<>();
        try {
            var files = CodeGenTypeEnum.VUE_PROJECT.getValue().equals(type)
                    ? listProjectFiles(directory)
                    : List.of(directory.resolve("index.html"), directory.resolve("style.css"), directory.resolve("script.js"));
            for (Path file : files) {
                rejectSymlinks(file);
                if (!Files.exists(file)) continue;
                if (Files.size(file) > 2 * 1024 * 1024) throw error("文件超过 2 MB，无法在线对比");
                String name = directory.relativize(file).toString().replace(File.separatorChar, '/');
                result.put(name, Files.readString(file, StandardCharsets.UTF_8));
            }
            return result;
        } catch (IOException exception) {
            throw error("读取版本代码失败");
        }
    }

    private List<Path> listProjectFiles(Path directory) throws IOException {
        try (var files = Files.walk(directory)) {
            return files.filter(path -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
        }
    }

    public Activation activate(Long appId, String type, Path snapshot) {
        snapshot = snapshot.toAbsolutePath().normalize();
        if (!snapshot.startsWith(versionsRoot)) throw error("版本目录无效");
        rejectSymlinks(snapshot);
        if (!Files.isRegularFile(snapshot.resolve("index.html"))) throw error("版本代码不存在");
        Path target = outputRoot.resolve(key(appId, type));
        Path stage = outputRoot.resolve(".version-stage-" + UUID.randomUUID());
        Path backup = outputRoot.resolve(".version-backup-" + UUID.randomUUID());
        rejectSymlinks(target);
        try {
            Files.createDirectories(stage);
            copy(snapshot, stage);
            if (Files.exists(target)) Files.move(target, backup);
            try {
                Files.move(stage, target);
            } catch (IOException exception) {
                if (Files.exists(backup)) Files.move(backup, target);
                throw exception;
            }
            return new Activation(target, backup);
        } catch (IOException exception) {
            log.error("切换预览版本失败", exception);
            throw error("切换预览版本失败");
        } finally {
            discard(stage);
        }
    }

    public final class Activation implements AutoCloseable {
        private final Path target;
        private final Path backup;
        private boolean committed;
        private Activation(Path target, Path backup) { this.target = target; this.backup = backup; }

        public void commit() { committed = true; discard(backup); }

        @Override public void close() {
            if (committed) return;
            try {
                delete(target);
                if (Files.exists(backup)) Files.move(backup, target);
            } catch (IOException exception) {
                log.error("恢复上一版预览失败，备份保留在 {}", backup, exception);
                throw error("恢复上一版预览失败，请联系管理员");
            }
        }
    }

    public void discard(Path path) {
        try { delete(path); }
        catch (IOException exception) { log.warn("清理版本临时目录失败：{}", path, exception); }
    }

    private void delete(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if ((!normalized.startsWith(outputRoot) && !normalized.startsWith(versionsRoot))
                || normalized.equals(outputRoot) || normalized.equals(versionsRoot)) throw error("目录超出应用存储范围");
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }

    private void copy(Path source, Path target) {
        rejectSymlinks(source);
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                    Files.createDirectories(target.resolve(source.relativize(directory))); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink()) throw error("应用代码不允许包含符号链接");
                    Files.copy(file, target.resolve(source.relativize(file))); return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) { throw error("复制版本代码失败"); }
    }

    private String key(Long appId, String type) {
        if (appId == null || appId <= 0 || CodeGenTypeEnum.getEnumByValue(type) == null) throw error("应用目录参数无效");
        return type + "_" + appId;
    }

    private void rejectSymlinks(Path path) {
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw error("应用代码不允许包含符号链接");
        }
    }

    private BusinessException error(String message) { return new BusinessException(ErrorCode.OPERATION_ERROR, message); }
}
