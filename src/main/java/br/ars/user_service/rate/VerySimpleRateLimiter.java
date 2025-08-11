// VerySimpleRateLimiter.java
package br.ars.user_service.rate;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class VerySimpleRateLimiter {
    private final int limit;
    private final long windowMillis;
    private final Map<String, Window> counters = new ConcurrentHashMap<>();

    public VerySimpleRateLimiter(int limit, long windowMillis) {
        this.limit = limit; this.windowMillis = windowMillis;
    }
    public boolean allow(String key) {
        long now = Instant.now().toEpochMilli();
        Window w = counters.computeIfAbsent(key, k -> new Window(now));
        synchronized (w) {
            if (now - w.start > windowMillis) { w.start = now; w.count.set(0); }
            return w.count.incrementAndGet() <= limit;
        }
    }
    static class Window { long start; AtomicInteger count = new AtomicInteger(); Window(long s){start=s;} }
}
