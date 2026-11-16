package com.hjq.CodeLess.service;

import cn.hutool.json.JSONUtil;
import com.hjq.CodeLess.constant.AppConstant;
import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.exception.ErrorCode;
import com.hjq.CodeLess.model.entity.App;
import com.hjq.CodeLess.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Durable staging area for application deletions; paths are always derived from controlled roots. */
@Service
@Slf4j
public class AppCleanupStorage {
    private final Path outputRoot;
    private final Path versionsRoot;
    private final Path deployRoot;
    private final Path queueRoot;
    private final Set<Path> active = ConcurrentHashMap.newKeySet();

    public AppCleanupStorage() {
        this(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR), Path.of(System.getProperty("user.dir"), "tmp/code_versions"),
                Path.of(AppConstant.CODE_DEPLOY_ROOT_DIR), Path.of(System.getProperty("user.dir"), "tmp/app_cleanup"));
    }

    AppCleanupStorage(Path output, Path versions, Path deploy, Path queue) {
        outputRoot = output.toAbsolutePath().normalize();
        versionsRoot = versions.toAbsolutePath().normalize();
        deployRoot = deploy.toAbsolutePath().normalize();
        queueRoot = queue.toAbsolutePath().normalize();
    }

    public record Job(Path directory, Long appId, String deployKey) {}

    public Job prepare(App app) {
        Map<String, Path> targets = targets(app.getId(), app.getDeployKey());
        validateRoot(queueRoot);
        Job job = new Job(queueRoot.resolve(UUID.randomUUID().toString()), app.getId(), app.getDeployKey());
        active.add(job.directory());
        try {
            Files.createDirectories(job.directory());
            Files.writeString(job.directory().resolve("job.json"), JSONUtil.createObj()
                    .set("appId", job.appId().toString()).set("deployKey", job.deployKey()).toString(), StandardCharsets.UTF_8);
            for (var target : targets.entrySet()) {
                if (Files.exists(target.getValue(), LinkOption.NOFOLLOW_LINKS)) {
                    move(target.getValue(), job.directory().resolve(target.getKey()));
                }
            }
            return job;
        } catch (IOException | RuntimeException failure) {
            try { restore(job); }
            catch (Exception recoveryFailure) { failure.addSuppressed(recoveryFailure); }
            active.remove(job.directory());
            log.error("应用 {} 文件暂存失败，已尝试恢复原文件", app.getId(), failure);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "无法清理应用文件，请稍后重试");
        }
    }

    public void release(Job job) { active.remove(job.directory()); }

    public void restore(Job job) throws IOException {
        validateJob(job);
        var targets = new ArrayList<>(targets(job.appId(), job.deployKey()).entrySet());
        Collections.reverse(targets);
        for (var target : targets) {
            Path staged = job.directory().resolve(target.getKey());
            if (!Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) continue;
            if (Files.exists(target.getValue(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("原目录已存在，保留暂存数据以避免覆盖");
            Files.createDirectories(target.getValue().getParent());
            move(staged, target.getValue());
        }
        finishJob(job);
    }

    public void purge(Job job) throws IOException {
        validateJob(job);
        // Remove the recovery manifest last, so an interrupted purge can be retried.
        for (String name : targets(job.appId(), job.deployKey()).keySet()) deleteStaged(job.directory().resolve(name));
        finishJob(job);
    }

    private void finishJob(Job job) throws IOException {
        Files.deleteIfExists(job.directory().resolve("job.json"));
        Files.deleteIfExists(job.directory());
    }

    void move(Path source, Path destination) throws IOException { Files.move(source, destination); }

    public List<Job> jobs() {
        validateRoot(queueRoot);
        if (!Files.exists(queueRoot)) return List.of();
        List<Job> jobs = new ArrayList<>();
        try (var directories = Files.list(queueRoot)) {
            for (Path directory : directories.toList()) {
                if (jobs.size() >= 200) break;
                if (active.contains(directory) || !uuid(directory.getFileName().toString())) continue;
                try {
                    validateRoot(directory);
                    Path manifest = directory.resolve("job.json");
                    if (!Files.exists(manifest)) {
                        boolean empty;
                        try (var files = Files.list(directory)) {
                            empty = files.findAny().isEmpty();
                        }
                        if (empty) Files.deleteIfExists(directory);
                        continue;
                    }
                    if (Files.isSymbolicLink(manifest) || Files.size(manifest) > 4096) throw new IOException("无效清理任务");
                    var data = JSONUtil.parseObj(Files.readString(manifest, StandardCharsets.UTF_8));
                    Job job = new Job(directory, data.getLong("appId"), data.getStr("deployKey"));
                    validateJob(job);
                    targets(job.appId(), job.deployKey());
                    jobs.add(job);
                } catch (Exception failure) { log.warn("跳过无法读取的应用清理任务：{}", directory, failure); }
            }
        } catch (IOException failure) { log.warn("读取应用清理任务失败", failure); }
        return jobs;
    }

    private Map<String, Path> targets(Long appId, String deployKey) {
        if (appId == null || appId <= 0) throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 无效");
        Map<String, Path> paths = new LinkedHashMap<>();
        for (CodeGenTypeEnum type : CodeGenTypeEnum.values()) {
            String key = type.getValue() + "_" + appId;
            paths.put("output-" + type.getValue(), child(outputRoot, key));
            paths.put("versions-" + type.getValue(), child(versionsRoot, key));
        }
        if (deployKey != null && !deployKey.isBlank()) {
            if (!deployKey.matches("[A-Za-z0-9_-]{1,64}")) throw new BusinessException(ErrorCode.OPERATION_ERROR, "部署标识无效，拒绝清理非应用目录");
            paths.put("deployment", child(deployRoot, deployKey));
        }
        return paths;
    }

    private Path child(Path root, String name) {
        validateRoot(root);
        Path target = root.resolve(name).normalize();
        if (!root.equals(target.getParent())) throw new IllegalArgumentException("清理路径超出应用根目录");
        validateRoot(target);
        return target;
    }

    private void validateJob(Job job) {
        validateRoot(queueRoot);
        if (!queueRoot.equals(job.directory().toAbsolutePath().normalize().getParent())
                || !uuid(job.directory().getFileName().toString())) throw new IllegalArgumentException("清理任务目录无效");
        validateRoot(job.directory());
    }

    private boolean uuid(String value) {
        return value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private void validateRoot(Path path) {
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("清理路径不允许包含符号链接");
        }
        try {
            if (Files.exists(path) && !path.toRealPath().equals(path.toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("清理路径不能重定向到其他目录");
            }
        } catch (IOException failure) { throw new IllegalArgumentException("无法验证清理路径", failure); }
    }

    private void deleteStaged(Path path) throws IOException {
        if (!queueRoot.equals(path.getParent().getParent()) || !uuid(path.getParent().getFileName().toString())) {
            throw new IllegalArgumentException("拒绝清理暂存区以外的文件");
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                // Windows junctions can be reported as directories rather than symbolic links.
                // Remove the redirection itself without ever traversing its destination.
                if (!directory.toRealPath().equals(directory.toAbsolutePath().normalize())) {
                    Files.delete(directory);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory); return FileVisitResult.CONTINUE;
            }
        });
    }
}
