package com.hjq.CodeLess.service;

import cn.hutool.json.JSONUtil;
import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.exception.ErrorCode;
import java.text.Normalizer;
import java.util.*;

public final class AppMetadataRules {
    private AppMetadataRules() {}
    public static String visibility(String value) {
        if (!Set.of("public", "private").contains(value == null ? "" : value)) throw error("可见范围只能为公开或私有");
        return value;
    }
    public static String text(String value, int max, String label) {
        String text = value == null ? "" : value.strip();
        if (text.codePointCount(0, text.length()) > max) throw error(label + "不能超过 " + max + " 个字符");
        return text;
    }
    public static List<String> tags(List<String> input) {
        if (input == null) return List.of();
        if (input.size() > 8) throw error("最多添加 8 个标签");
        Set<String> result = new LinkedHashSet<>();
        for (String item : input) {
            if (item == null) throw error("标签不能为空");
            String tag = Normalizer.normalize(item, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            if (!tag.matches("[\\p{L}\\p{N}][\\p{L}\\p{N} .+#_-]{0,19}")) throw error("标签需为 1–20 个字母、数字或文字，可含空格、点、+、#、下划线和连字符");
            result.add(tag);
        }
        return List.copyOf(result);
    }
    public static List<String> readTags(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return JSONUtil.toList(JSONUtil.parseArray(json), String.class); }
        catch (Exception ignored) { return List.of(); }
    }
    private static BusinessException error(String message) { return new BusinessException(ErrorCode.PARAMS_ERROR, message); }
}
