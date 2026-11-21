package com.hjq.CodeLess.ai.tools;

import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.LinkOption;

@Component("readFile")
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class FileReadTool extends BaseTool {
    private static final long MAX_FILE_SIZE = 2L * 1024 * 1024;
    private final VueProjectWorkspace workspace;

    public FileReadTool(VueProjectWorkspace workspace) {
        this.workspace = workspace;
    }

    @Tool("读取 Vue 工作区内指定文件的文本内容")
    public String readFile(@P("文件的相对路径") String relativeFilePath) {
        return workspace.access("读取文件", relativeFilePath, false, path -> {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return "错误：文件不存在或不是文件 - " + relativeFilePath;
            }
            if (Files.size(path) > MAX_FILE_SIZE) return "错误：文件超过 2 MB - " + relativeFilePath;
            return Files.readString(path);
        });
    }

    @Override
    public String getToolName() {
        return "readFile";
    }

    @Override
    public String getDisplayName() {
        return "读取文件";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String relativeFilePath = arguments.getStr("relativeFilePath");
        return String.format("[工具调用] %s %s", getDisplayName(), relativeFilePath);
    }
}
