package com.lld.ratelimiter.fixedwindow;

import com.lld.ratelimiter.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window rate limiter: at most {@code maxRequests} admissions per client per
 * epoch-aligned window.
 *
 * <p>The window a request falls in is {@code floor(clock.millis() / windowMillis)},
 * so windows are fixed to the clock, not to the client's first request. Each client
 * keeps its own window index and counter, and resets lazily the next time it is
 * seen in a newer window.
 *
 * <p>State is one {@link Node} per client in a {@link ConcurrentHashMap}. The node
 * is its own lock, so {@code tryConsume} is atomic per client: threads for the same
 * client serialize, while threads for different clients run in parallel (lock
 * striping).
 *
 * <p>The reset is guarded by {@code currentWindowIndex > windowIndex} rather than
 * {@code !=}. Under concurrency two callers can read the clock at slightly
 * different instants, so a caller may arrive carrying an older window index; the
 * comparison must only ever move the node forward. With {@code !=}, a stale caller
 * would rewind the window and be handed a fresh budget, over-admitting.
 */
public class FixedWindowRateLimiter implements RateLimiter {

    private final int maxRequests;
    private final long windowMillis;
    private final Clock clock;
    private final ConcurrentHashMap<String, Node> nodes = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(int maxRequests, Duration window) {
        this(maxRequests, window, Clock.systemUTC());
    }

    public FixedWindowRateLimiter(int maxRequests, Duration window, Clock clock) {
        if (maxRequests <= 0) {
            throw new IllegalArgumentException("maxRequests must be > 0, was " + maxRequests);
        }
        Objects.requireNonNull(window, "window");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be > 0, was " + window);
        }
        this.maxRequests = maxRequests;
        // Clock resolution is milliseconds; clamp sub-millisecond windows to 1 ms.
        this.windowMillis = Math.max(1L, window.toMillis());
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean allowRequest(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId must be non-null and non-blank");
        }

        long currentIndex = clock.millis() / windowMillis;
        Node node = nodes.computeIfAbsent(clientId, key -> new Node());
        return node.tryConsume(currentIndex, maxRequests);
    }

    /**
     * One client's counter. The node is also its own lock, so threads for the same
     * client serialize here while different clients run in parallel.
     */
    private static final class Node {

        private long windowIndex = Long.MIN_VALUE;   // forces a reset on first use
        private int remaining;

        synchronized boolean tryConsume(long currentWindowIndex, int maxRequests) {
            if (currentWindowIndex > windowIndex) {   // only ever move forward
                windowIndex = currentWindowIndex;
                remaining = maxRequests;
            }
            if (remaining > 0) {
                remaining--;
                return true;
            }
            return false;
        }
    }
}
