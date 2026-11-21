package com.hjq.CodeLess.ai.tools;

import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.LinkOption;

@Component("modifyFile")
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class FileModifyTool extends BaseTool {
    private final VueProjectWorkspace workspace;

    public FileModifyTool(VueProjectWorkspace workspace) {
        this.workspace = workspace;
    }

    @Tool("修改 Vue 工作区内的现有文件，将匹配的旧文本替换为新文本")
    public String modifyFile(
            @P("文件的相对路径") String relativeFilePath,
            @P("要替换的非空旧内容，按原文精确匹配") String oldContent,
            @P("替换后的新内容") String newContent) {
        if (oldContent == null || oldContent.isEmpty() || newContent == null) {
            return "错误：必须提供非空的旧内容和替换后的新内容";
        }
        return workspace.access("修改文件", relativeFilePath, false, path -> {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return "错误：文件不存在或不是文件 - " + relativeFilePath;
            }
            String original = Files.readString(path);
            if (!original.contains(oldContent)) {
                return "警告：文件中未找到要替换的内容，文件未修改 - " + relativeFilePath;
            }
            if (oldContent.equals(newContent)) return "信息：替换后文件内容未发生变化 - " + relativeFilePath;
            VueProjectWorkspace.saveText(path, original.replace(oldContent, newContent));
            return "文件修改成功: " + relativeFilePath;
        });
    }

        @Override
        public String getToolName() {
            return "modifyFile";
        }

        @Override
        public String getDisplayName() {
            return "修改文件";
        }

        @Override
        public String generateToolExecutedResult(JSONObject arguments) {
            String relativeFilePath = arguments.getStr("relativeFilePath");
            String oldContent = arguments.getStr("oldContent");
            String newContent = arguments.getStr("newContent");
            // 显示对比内容
            return String.format("""
                [工具调用] %s %s

                替换前：
                %s

                替换后：
                %s
                """, getDisplayName(), relativeFilePath, fencedCode("", oldContent), fencedCode("", newContent));
        }
    }
