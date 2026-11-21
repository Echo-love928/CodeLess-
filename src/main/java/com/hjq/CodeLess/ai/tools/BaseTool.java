package com.hjq.CodeLess.ai.tools;

import cn.hutool.json.JSONObject;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 工具基类
 * 定义所有工具的通用接口
 */
public abstract class BaseTool {
    private static final Pattern BACKTICKS = Pattern.compile("`+");

    /**
     * 获取工具的英文名称（对应方法名）
     *
     * @return 工具英文名称
     */
    public abstract String getToolName();

    /**
     * 获取工具的中文显示名称
     *
     * @return 工具中文名称
     */
    public abstract String getDisplayName();

    /**
     * 生成工具请求时的返回值（显示给用户）
     *
     * @return 工具请求显示内容
     */
    public String generateToolRequestResponse() {
        return String.format("\n\n[选择工具] %s\n\n", getDisplayName());
    }

    /**
     * 生成工具执行结果格式（保存到数据库）
     *
     * @param arguments 工具执行参数
     * @return 格式化的工具执行结果
     */
    public abstract String generateToolExecutedResult(JSONObject arguments);

    protected String fencedCode(String language, String content) {
        String code = Objects.toString(content, "");
        var backticks = BACKTICKS.matcher(code);
        int fenceLength = 3;
        while (backticks.find()) {
            fenceLength = Math.max(fenceLength, backticks.end() - backticks.start() + 1);
        }
        String fence = "`".repeat(fenceLength);
        String label = Objects.toString(language, "").replaceAll("[^a-zA-Z0-9_-]", "");
        return fence + label + "\n" + code + "\n" + fence;
    }
}
