package com.hjq.CodeLess.ai.tools;

import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Locale;
import java.util.Set;

@Component("deleteFile")
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class FileDeleteTool extends BaseTool {
    private static final Set<String> PROTECTED_FILES = Set.of(
            "package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml",
            "vite.config.js", "vite.config.ts", "vue.config.js",
            "tsconfig.json", "tsconfig.app.json", "tsconfig.node.json",
            "index.html", "main.js", "main.ts", "app.vue", ".gitignore", "readme.md"
    );
    private final VueProjectWorkspace workspace;

    public FileDeleteTool(VueProjectWorkspace workspace) {
        this.workspace = workspace;
    }

    @Tool("删除 Vue 工作区内不再需要的单个文件")
    public String deleteFile(@P("文件的相对路径") String relativeFilePath) {
        return workspace.access("删除文件", relativeFilePath, false, path -> {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return "警告：文件不存在，无需删除 - " + relativeFilePath;
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return "错误：指定路径不是文件，无法删除 - " + relativeFilePath;
            }
            if (PROTECTED_FILES.contains(path.getFileName().toString().toLowerCase(Locale.ROOT))) {
                return "错误：不允许删除重要文件 - " + relativeFilePath;
            }
            Files.delete(path);
            return "文件删除成功: " + relativeFilePath;
        });
    }

    @Override
    public String getToolName() {
        return "deleteFile";
    }

    @Override
    public String getDisplayName() {
        return "删除文件";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String relativeFilePath = arguments.getStr("relativeFilePath");
        return String.format("[工具调用] %s %s", getDisplayName(), relativeFilePath);
    }
}
