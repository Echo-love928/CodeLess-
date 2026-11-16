package com.hjq.CodeLess.service;
import com.hjq.CodeLess.constant.AppConstant;
import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.exception.ErrorCode;
import com.hjq.CodeLess.model.entity.App;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.io.IOException;
import java.util.regex.Pattern;
@Service
public class AppResourceService {
    private static final Pattern KEY = Pattern.compile("^(html|multi_file)_([1-9][0-9]{0,18})$");
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
        Path file = base.resolve(name).normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file)) throw missing();
        if (!base.toRealPath().startsWith(root.toRealPath()) || !file.toRealPath().startsWith(base.toRealPath())) throw missing();
        for (Path current = file; current != null && current.startsWith(root); current = current.getParent()) if (Files.isSymbolicLink(current)) throw missing();
        return file;
    }
    private BusinessException missing() { return new BusinessException(ErrorCode.NOT_FOUND_ERROR, "资源不存在"); }
}
