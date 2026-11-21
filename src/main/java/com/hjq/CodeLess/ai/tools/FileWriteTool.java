package com.hjq.CodeLess.ai.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 文件写入工具
 * 支持 AI 通过工具调用的方式写入文件
 */
@Component("writeFile")
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class FileWriteTool extends BaseTool {

    private final VueProjectWorkspace workspace;

    public FileWriteTool(Path projectRoot) {
        this(new VueProjectWorkspace(projectRoot));
    }

    public FileWriteTool(VueProjectWorkspace workspace) {
        this.workspace = workspace;
    }

    public boolean stopWriting() {
        return workspace.close();
    }

    @Tool("写入文件到指定路径")
    public String writeFile(
            @P("文件的相对路径")
            String relativeFilePath,
            @P("要写入文件的内容")
            String content
    ) {
        return workspace.access("文件写入", relativeFilePath, false, path -> {
            VueProjectWorkspace.saveText(path, content);
            return "文件写入成功: " + relativeFilePath;
        });
    }

        @Override
        public String getToolName() {
            return "writeFile";
        }

        @Override
        public String getDisplayName() {
            return "写入文件";
        }

        @Override
        public String generateToolExecutedResult(JSONObject arguments) {
            String relativeFilePath = arguments.getStr("relativeFilePath");
            String suffix = FileUtil.getSuffix(relativeFilePath);
            String content = arguments.getStr("content");
            return String.format("[工具调用] %s %s\n%s", getDisplayName(), relativeFilePath, fencedCode(suffix, content));
        }
    }
