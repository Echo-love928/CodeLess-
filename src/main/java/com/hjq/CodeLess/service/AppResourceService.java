package com.hjq.CodeLess.service;
import com.hjq.CodeLess.constant.AppConstant;
import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.exception.ErrorCode;
import com.hjq.CodeLess.model.entity.App;
import com.hjq.CodeLess.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.io.IOException;
import java.util.regex.Pattern;
@Service
public class AppResourceService {
    private static final Pattern KEY = Pattern.compile("^(html|multi_file|vue_project)_([1-9][0-9]{0,18})$");
    private final Path root;
    public AppResourceService() { this(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR)); }
    AppResourceService(Path root) { this.root = root.toAbsolutePath().normalize(); }
    public Long appId(String key) {
        var match = KEY.matcher(key);
        if (!match.matches()) throw missing();
        try { return Long.valueOf(match.group(2)); } catch (NumberFormatException failure) { throw missing(); }
    }
    public Path resolve(App app, String key, String relative) throws IOException {
        Long id = appId(key);
        if (!id.equals(app.getId()) || !key.equals(app.getCodeGenType() + "_" + id)) throw missing();
        if (relative == null || relative.indexOf('\\') >= 0 || relative.indexOf('\0') >= 0) throw missing();
        for (String segment : relative.split("/")) if (segment.equals("..")) throw missing();
        String name = relative.equals("/") || relative.isEmpty() ? "index.html" : relative.replaceFirst("^/", "");
        Path base = root.resolve(key).normalize();
        if (CodeGenTypeEnum.VUE_PROJECT.getValue().equals(app.getCodeGenType())) {
            if (!name.startsWith("dist/")) throw missing();
            base = base.resolve("dist");
            name = name.substring("dist/".length());
        }
        Path file = base.resolve(name).normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file)) throw missing();
        Path realBase = base.toRealPath();
        if (!realBase.equals(root.toRealPath().resolve(root.relativize(base)))
                || !file.toRealPath().startsWith(realBase)) throw missing();
        for (Path current = file; current != null && current.startsWith(root); current = current.getParent()) if (Files.isSymbolicLink(current)) throw missing();
        return file;
    }
    public Path requirePreviewDirectory(App app) {
        String key = app.getCodeGenType() + "_" + app.getId();
        String entry = CodeGenTypeEnum.VUE_PROJECT.getValue().equals(app.getCodeGenType())
                ? "/dist/index.html" : "/";
        try {
            Path file = resolve(app, key, entry);
            if (!Files.isReadable(file)) throw missing();
            return root.resolve(key);
        } catch (BusinessException | IOException failure) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用尚未生成可用的预览，请先生成应用");
        }
    }

    private BusinessException missing() { return new BusinessException(ErrorCode.NOT_FOUND_ERROR, "资源不存在"); }
}
