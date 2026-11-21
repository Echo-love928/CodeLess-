package com.hjq.CodeLess.ai.tools;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Holds the filesystem boundary and lifecycle shared by all tools for one generation. */
@Slf4j
public final class VueProjectWorkspace {
    private final Path root;
    private boolean closed;

    public VueProjectWorkspace(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public synchronized boolean close() {
        if (closed) return false;
        closed = true;
        return true;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    synchronized String access(String action, String relativePath, boolean allowRoot, Operation operation) {
        if (closed) return action + "失败：生成任务已结束";
        try {
            Path relative = Path.of(relativePath == null ? "" : relativePath);
            Path path = root.resolve(relative).normalize();
            if (relative.getRoot() != null || !path.startsWith(root) || (!allowRoot && path.equals(root))) {
                return action + "失败：路径必须位于项目目录内";
            }
            if (Files.isSymbolicLink(root)) return action + "失败：不允许访问符号链接";
            Path realRoot = Files.exists(root, LinkOption.NOFOLLOW_LINKS) ? root.toRealPath() : root;
            for (Path current = path; !current.equals(root); current = current.getParent()) {
                if (Files.isSymbolicLink(current) || (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                        && !current.toRealPath().equals(realRoot.resolve(root.relativize(current))))) {
                    return action + "失败：不允许访问符号链接";
                }
            }
            return operation.execute(path);
        } catch (InvalidPathException invalidPath) {
            return action + "失败：路径格式无效";
        } catch (IOException failure) {
            log.warn("{} Vue 工作区文件失败", action, failure);
            return action + "失败: " + relativePath;
        }
    }

    static void saveText(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Path staged = Files.createTempFile(path.getParent(), ".codeless-", ".tmp");
        try {
            Files.writeString(staged, content);
            try {
                Files.move(staged, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staged, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    @FunctionalInterface
    interface Operation {
        String execute(Path path) throws IOException;
    }
}
