package com.hjq.CodeLess.service;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
class AppBrowseLimiterTest {
    @Test void budgetsAreIndependentAndResetAfterOneMinute() {
        var time = new AtomicLong(0);
        var clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(time.get()); }
        };
        var limiter = new AppBrowseLimiter(clock);
        limiter.check("search", "one", 2); limiter.check("search", "one", 2);
        assertThrows(RuntimeException.class, () -> limiter.check("search", "one", 2));
        assertDoesNotThrow(() -> limiter.check("search", "two", 2));
        time.set(60_000); assertDoesNotThrow(() -> limiter.check("search", "one", 2));
    }
}
