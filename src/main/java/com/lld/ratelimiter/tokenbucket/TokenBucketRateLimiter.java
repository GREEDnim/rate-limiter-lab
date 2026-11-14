package com.lld.ratelimiter.tokenbucket;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.lld.ratelimiter.RateLimiter;

/**
 * Token-bucket rate limiter: each client owns a bucket of at most {@code capacity}
 * tokens that refills {@code refillTokens} every {@code refillPeriod}, lazily
 * driven by the injected {@link Clock}.
 *
 * <p>State is one {@link Node} per client in a {@link ConcurrentHashMap}. The node
 * is its own lock, so {@code allowRequest} is atomic per client: threads for the
 * same client serialize, while threads for different clients run in parallel.
 *
 * <p>The bucket level is kept as a fraction and refilled by the elapsed rate
 * ({@code elapsed * refillTokens / refillPeriod}), so a partial period still
 * accrues and the sustained rate stays exact even when the period does not divide
 * evenly by the token count.
 */
public class TokenBucketRateLimiter implements RateLimiter {

    private final int capacity;
    private final int refillTokens;
    private final long refillPeriodMillis;
    private final Clock clock;
    private final ConcurrentHashMap<String, Node> nodes = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(int capacity, int refillTokens, Duration refillPeriod) {
        this(capacity, refillTokens, refillPeriod, Clock.systemUTC());
    }

    public TokenBucketRateLimiter(int capacity, int refillTokens, Duration refillPeriod, Clock clock) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, was " + capacity);
        }
        if (refillTokens <= 0) {
            throw new IllegalArgumentException("refillTokens must be > 0, was " + refillTokens);
        }
        Objects.requireNonNull(refillPeriod, "refillPeriod");
        if (refillPeriod.isZero() || refillPeriod.isNegative()) {
            throw new IllegalArgumentException("refillPeriod must be > 0, was " + refillPeriod);
        }
        this.capacity = capacity;
        this.refillTokens = refillTokens;
        // Clock resolution is milliseconds; clamp sub-millisecond periods to 1 ms.
        this.refillPeriodMillis = Math.max(1L, refillPeriod.toMillis());
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean allowRequest(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId must be non-null and non-blank");
        }

        Node node = nodes.computeIfAbsent(clientId, key -> new Node());
        return node.allowRequest(clock.millis());
    }

    /**
     * One client's bucket. The node is also its own lock, so threads for the same
     * client serialize here while different clients run in parallel.
     */
    private final class Node {

        private double tokens = capacity;                 // starts full
        private long lastRefillMs = clock.millis();

        synchronized boolean allowRequest(long nowMs) {
            long elapsed = nowMs - lastRefillMs;
            if (elapsed > 0) {
                // Refill by the elapsed rate, not by whole periods, so partial time
                // still accrues and the rate is exact for any token count.
                tokens = Math.min(capacity,
                        tokens + (double) elapsed * refillTokens / refillPeriodMillis);
                lastRefillMs = nowMs;
            }
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
