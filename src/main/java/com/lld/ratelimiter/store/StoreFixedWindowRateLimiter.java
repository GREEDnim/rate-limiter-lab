package com.lld.ratelimiter.store;

import java.time.Duration;
import java.util.Objects;

import com.lld.ratelimiter.RateLimiter;

/**
 * The fixed-window limiter of problem 01, but with its counter in a shared
 * {@link RateLimitStore} instead of a local map — so the same limit holds across every
 * process that shares the store.
 *
 * <p>The window is <b>epoch-aligned</b> and comes from the <em>store's</em> clock, so all
 * nodes agree: the key is {@code clientId + ":" + (store.nowMillis() / windowMillis)}, a
 * new window is a new key, and the store's atomic {@code incrementAndGet} decides the
 * count. Because the limiter only depends on the {@link RateLimitStore} interface, this
 * class is byte-for-byte the same whether the store is in memory (tests) or Redis
 * (production) — only the store implementation changes.
 */
public class StoreFixedWindowRateLimiter implements RateLimiter {

    private final int maxRequests;
    private final long windowMillis;
    private final RateLimitStore store;

    public StoreFixedWindowRateLimiter(int maxRequests, Duration window, RateLimitStore store) {
        if (maxRequests <= 0) {
            throw new IllegalArgumentException("maxRequests must be > 0, was " + maxRequests);
        }
        Objects.requireNonNull(window, "window");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be > 0, was " + window);
        }
        this.maxRequests = maxRequests;
        // Clock resolution is milliseconds; clamp a sub-millisecond window to 1 ms.
        this.windowMillis = Math.max(1L, window.toMillis());
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public boolean allowRequest(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId must be non-null and non-blank");
        }

        long now = store.nowMillis();                      // time comes from the store
        long windowIndex = now / windowMillis;             // epoch-aligned window
        String key = clientId + ":" + windowIndex;         // a new window is a new key

        long count = store.incrementAndGet(key, Duration.ofMillis(windowMillis));
        return count <= maxRequests;
    }
}
