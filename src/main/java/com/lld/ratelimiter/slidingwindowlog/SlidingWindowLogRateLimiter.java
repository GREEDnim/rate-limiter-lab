package com.lld.ratelimiter.slidingwindowlog;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.lld.ratelimiter.RateLimiter;

/**
 * Sliding-window-log rate limiter: at most {@code maxRequests} admissions per client
 * over the trailing {@code window}, measured from the injected clock.
 *
 * <p>State is one {@link Node} per client in a {@link ConcurrentHashMap}, mirroring
 * {@code FixedWindowRateLimiter}. The node is its own lock, so admitting is atomic
 * per client while different clients run in parallel.
 */
public class SlidingWindowLogRateLimiter implements RateLimiter {

    private final int maxRequests;
    private final long windowMillis;
    private final Clock clock;
    private final ConcurrentHashMap<String, Node> nodes = new ConcurrentHashMap<>();

    public SlidingWindowLogRateLimiter(int maxRequests, Duration window) {
        this(maxRequests, window, Clock.systemUTC());
    }

    public SlidingWindowLogRateLimiter(int maxRequests, Duration window, Clock clock) {
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

        Node node = nodes.computeIfAbsent(clientId, key -> new Node(maxRequests, windowMillis, clock));
        return node.allowRequest();
    }

    /**
     * One client's log of admission timestamps. The node is its own lock, so threads
     * for the same client serialize here while different clients run in parallel.
     */
    private static final class Node {

        private final int maxRequests;
        private final long windowMillis;
        private final Clock clock;
        private final Deque<Long> timestamps = new ArrayDeque<>();

        Node(int maxRequests, long windowMillis, Clock clock) {
            this.maxRequests = maxRequests;
            this.windowMillis = windowMillis;
            this.clock = clock;
        }

        synchronized boolean allowRequest() {
            long nowMillis = clock.millis();
            long windowStart = nowMillis - windowMillis;
            while (!timestamps.isEmpty() && timestamps.peekFirst() <= windowStart) {
                timestamps.pollFirst();
            }
            if (timestamps.size() < maxRequests) {
                timestamps.addLast(nowMillis);
                return true;
            }
            return false;
        }
    }
}
