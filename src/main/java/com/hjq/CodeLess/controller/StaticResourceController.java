package com.hjq.CodeLess.controller;

import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.exception.ErrorCode;
import com.hjq.CodeLess.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerMapping;
import java.nio.file.Files;

@RestController
@RequestMapping("/static")
public class StaticResourceController {
    private final AppService apps;
    private final UserService users;
    private final AppAccessPolicy access;
    private final AppResourceService resources;
    private final AppBrowseLimiter limiter;
    public StaticResourceController(AppService apps, UserService users, AppAccessPolicy access,
                                     AppResourceService resources, AppBrowseLimiter limiter) {
        this.apps = apps; this.users = users; this.access = access; this.resources = resources; this.limiter = limiter;
    }
    @GetMapping("/{deployKey}/**")
    public ResponseEntity<Resource> serveStaticResource(@PathVariable String deployKey, HttpServletRequest request) {
        try {
            var app = apps.getById(resources.appId(deployKey));
            var viewer = users.getLoginUserOptional(request);
            access.requireView(app, viewer);
            if (!access.canManage(app, viewer)) limiter.check("preview-ip", request.getRemoteAddr(), 600);
            String mapped = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
            String prefix = "/static/" + deployKey;
            if (mapped == null || !mapped.startsWith(prefix)) return failure(HttpStatus.NOT_FOUND);
            String relative = mapped.substring(prefix.length());
            if (relative.isEmpty()) return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                    .header(HttpHeaders.LOCATION, request.getRequestURI() + "/").cacheControl(CacheControl.noStore()).build();
            var file = resources.resolve(app, deployKey, relative);
            String mime = Files.probeContentType(file);
            if (file.toString().endsWith(".js")) mime = "application/javascript";
            if (mime == null) mime = "application/octet-stream";
            if (mime.startsWith("text/") || mime.equals("application/javascript") || mime.equals("application/json")) mime += "; charset=UTF-8";
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, mime)
                    .header(HttpHeaders.VARY, "Cookie").cacheControl(CacheControl.noStore()).body(new FileSystemResource(file));
        } catch (BusinessException failure) {
            return failure(failure.getCode() == ErrorCode.FORBIDDEN_ERROR.getCode() ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.NOT_FOUND);
        } catch (Exception failure) { return failure(HttpStatus.NOT_FOUND); }
    }
    private ResponseEntity<Resource> failure(HttpStatus status) {
        return ResponseEntity.status(status).header(HttpHeaders.VARY, "Cookie").cacheControl(CacheControl.noStore()).build();
    }
}
