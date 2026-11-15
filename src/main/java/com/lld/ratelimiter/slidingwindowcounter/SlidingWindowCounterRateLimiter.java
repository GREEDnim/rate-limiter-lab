package com.lld.ratelimiter.slidingwindowcounter;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.lld.ratelimiter.RateLimiter;

/**
 * Sliding-window-counter rate limiter: at most {@code maxRequests} admissions per
 * client over the trailing {@code window}, approximated from two aligned-window
 * counters.
 *
 * <p>State is one {@link Node} per client in a {@link ConcurrentHashMap}, mirroring
 * the other limiters. The node is its own lock, so the window shift, the estimate,
 * and the increment are atomic per client while different clients run in parallel.
 *
 * <p>On each call the trailing window is estimated as
 * {@code currentCount + previousCount * (W - elapsed) / W}, where {@code W} is the
 * window length and {@code elapsed = now % W}. A request is admitted only while that
 * estimate is strictly below {@code maxRequests}.
 */
public class SlidingWindowCounterRateLimiter implements RateLimiter {

    private final int maxRequests;
    private final long windowMillis;
    private final Clock clock;
    private final ConcurrentHashMap<String, Node> nodes = new ConcurrentHashMap<>();

    public SlidingWindowCounterRateLimiter(int maxRequests, Duration window) {
        this(maxRequests, window, Clock.systemUTC());
    }

    public SlidingWindowCounterRateLimiter(int maxRequests, Duration window, Clock clock) {
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
     * One client's two-window counters. The node is its own lock, so threads for the
     * same client serialize here while different clients run in parallel.
     */
    private static final class Node {

        private final int maxRequests;
        private final long windowMillis;
        private final Clock clock;

        private long windowIndex = Long.MIN_VALUE;   // unset: forces a reset on first use
        private int previousWindowCount;
        private int currentWindowCount;

        Node(int maxRequests, long windowMillis, Clock clock) {
            this.maxRequests = maxRequests;
            this.windowMillis = windowMillis;
            this.clock = clock;
        }

        synchronized boolean allowRequest() {
            long now = clock.millis();
            long currentIndex = now / windowMillis;

            // Shift the counters when the window changes: the finished window becomes
            // "previous" only if it is the immediate predecessor; a gap means a window
            // was skipped and is forgotten (only the immediately previous one is kept).
            if (currentIndex > windowIndex) {
                previousWindowCount = (currentIndex == windowIndex + 1) ? currentWindowCount : 0;
                currentWindowCount = 0;
                windowIndex = currentIndex;
            }

            // Estimate the trailing window: all of the current window, plus the part of
            // the previous window that still overlaps (its un-elapsed fraction).
            long elapsed = now % windowMillis;
            double estimate = currentWindowCount
                    + previousWindowCount * ((double) (windowMillis - elapsed) / windowMillis);

            // Decide from the counters as they are; record only what we admit.
            if (estimate < maxRequests) {
                currentWindowCount++;
                return true;
            }
            return false;
        }
    }
}
