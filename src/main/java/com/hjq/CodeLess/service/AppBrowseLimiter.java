package com.hjq.CodeLess.service;

import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.exception.ErrorCode;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/** Bounded, single-instance browsing budget. Deploy behind a trusted gateway for distributed limits. */
@Service
public class AppBrowseLimiter {
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();
    public AppBrowseLimiter() { this(Clock.systemUTC()); }
    AppBrowseLimiter(Clock clock) { this.clock = clock; }
    public synchronized void check(String category, String identity, int limit) {
        long minute = clock.millis() / 60_000;
        windows.entrySet().removeIf(entry -> entry.getValue().minute != minute);
        String key = category + ":" + identity;
        Window window = windows.get(key);
        if (window == null) {
            if (windows.size() >= 10_000) throw blocked();
            window = new Window(minute); windows.put(key, window);
        }
        if (++window.count > limit) throw blocked();
    }
    private BusinessException blocked() { return new BusinessException(ErrorCode.FORBIDDEN_ERROR, "访问过于频繁，请稍后再试"); }
    private static class Window { final long minute; int count; Window(long minute) { this.minute = minute; } }
}
