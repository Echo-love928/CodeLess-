package com.hjq.CodeLess.ai.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Set;

@Component("readDir")
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class FileDirReadTool extends BaseTool {
    private static final Set<String> IGNORED_NAMES = Set.of(
            "node_modules", ".git", "dist", "build", ".DS_Store",
            ".env", "target", ".mvn", ".idea", ".vscode", "coverage"
    );
    private static final Set<String> IGNORED_EXTENSIONS = Set.of(".log", ".tmp", ".cache", ".lock");
    private final VueProjectWorkspace workspace;

    public FileDirReadTool(VueProjectWorkspace workspace) {
        this.workspace = workspace;
    }

    @Tool("递归列出 Vue 工作区内指定目录的文件和子目录")
    public String readDir(@P("目录的相对路径，传空字符串列出项目根目录") String relativeDirPath) {
        return workspace.access("读取目录", relativeDirPath, true, directory -> {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                return "错误：目录不存在或不是目录 - " + relativeDirPath;
            }
            Path realDirectory = directory.toRealPath();
            var entries = new ArrayList<String>();
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                    if (dir.equals(directory)) return FileVisitResult.CONTINUE;
                    Path relative = directory.relativize(dir);
                    if (shouldIgnore(dir) || !dir.toRealPath().equals(realDirectory.resolve(relative))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    entries.add(relative.toString().replace(File.separatorChar, '/') + "/");
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile() && !shouldIgnore(file)) {
                        entries.add(directory.relativize(file).toString().replace(File.separatorChar, '/'));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            entries.sort(String::compareTo);
            return entries.isEmpty() ? "项目目录为空" : "项目目录结构:\n" + String.join("\n", entries);
        });
    }

    private boolean shouldIgnore(Path path) {
        String name = path.getFileName().toString();
        return IGNORED_NAMES.contains(name) || IGNORED_EXTENSIONS.stream().anyMatch(name::endsWith);
    }
        @Override
        public String getToolName() {
            return "readDir";
        }

        @Override
        public String getDisplayName() {
            return "读取目录";
        }

        @Override
        public String generateToolExecutedResult(JSONObject arguments) {
            String relativeDirPath = arguments.getStr("relativeDirPath");
            if (StrUtil.isEmpty(relativeDirPath)) {
                relativeDirPath = "根目录";
            }
            return String.format("[工具调用] %s %s", getDisplayName(), relativeDirPath);
        }
    }
