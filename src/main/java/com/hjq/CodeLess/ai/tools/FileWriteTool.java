package com.hjq.CodeLess.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 文件写入工具
 * 支持 AI 通过工具调用的方式写入文件
 */
@Slf4j
public class FileWriteTool {

    private final Path projectRoot;
    private boolean closed;

    public FileWriteTool(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
    }

    public synchronized boolean stopWriting() {
        if (closed) {
            return false;
        }
        closed = true;
        return true;
    }

    @Tool("写入文件到指定路径")
    public synchronized String writeFile(
            @P("文件的相对路径")
            String relativeFilePath,
            @P("要写入文件的内容")
            String content
    ) {
        if (closed) {
            return "文件写入失败：生成任务已结束";
        }
        try {
            Path relativePath = Path.of(relativeFilePath);
            Path path = projectRoot.resolve(relativePath).normalize();
            if (relativePath.getRoot() != null || !path.startsWith(projectRoot) || path.equals(projectRoot)) {
                return "文件写入失败：文件路径必须位于项目目录内";
            }
            for (Path current = path; current.startsWith(projectRoot); current = current.getParent()) {
                if (Files.isSymbolicLink(current)) {
                    return "文件写入失败：文件路径不允许包含符号链接";
                }
            }
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            return "文件写入成功: " + relativeFilePath;
        } catch (IOException | InvalidPathException e) {
            log.warn("写入 Vue 工程文件失败", e);
            return "文件写入失败: " + relativeFilePath;
        }
    }
}
