package com.lld.ratelimiter.gcra;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.lld.ratelimiter.RateLimiter;

/**
 * GCRA (generic cell rate algorithm) rate limiter: a sustained rate of one request
 * per {@code emissionInterval}, with a burst of up to {@code floor(tau/T) + 1} from
 * idle, tracked as a single timestamp per client.
 *
 * <p>Instead of a bucket of tokens, each client keeps the time it is next <em>due</em>
 * to send — the theoretical arrival time, {@code TAT}. A request is rejected when it
 * arrives more than {@code tau} before that due time (the client is running too far
 * ahead of schedule); otherwise it is allowed and the schedule is pushed forward by
 * one interval.
 *
 * <p>State is one {@link Node} per client in a {@link ConcurrentHashMap}. The node is
 * its own lock, so the check and the advance are atomic per client while different
 * clients run in parallel.
 */
public class GcraRateLimiter implements RateLimiter {

    private final long emissionIntervalMillis;   // T
    private final long burstToleranceMillis;     // tau
    private final Clock clock;
    private final ConcurrentHashMap<String, Node> nodes = new ConcurrentHashMap<>();

    public GcraRateLimiter(Duration emissionInterval, Duration burstTolerance) {
        this(emissionInterval, burstTolerance, Clock.systemUTC());
    }

    public GcraRateLimiter(Duration emissionInterval, Duration burstTolerance, Clock clock) {
        Objects.requireNonNull(emissionInterval, "emissionInterval");
        if (emissionInterval.isZero() || emissionInterval.isNegative()) {
            throw new IllegalArgumentException("emissionInterval must be > 0, was " + emissionInterval);
        }
        Objects.requireNonNull(burstTolerance, "burstTolerance");
        if (burstTolerance.isNegative()) {
            throw new IllegalArgumentException("burstTolerance must be >= 0, was " + burstTolerance);
        }
        // Clock resolution is milliseconds; clamp a sub-millisecond interval to 1 ms.
        this.emissionIntervalMillis = Math.max(1L, emissionInterval.toMillis());
        this.burstToleranceMillis = burstTolerance.toMillis();   // 0 is valid: strict pacing
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean allowRequest(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId must be non-null and non-blank");
        }

        long now = clock.millis();
        Node node = nodes.computeIfAbsent(clientId,
                key -> new Node(emissionIntervalMillis, burstToleranceMillis));
        return node.allowRequest(now);
    }

    /**
     * One client's schedule. The node is also its own lock, so threads for the same
     * client serialize here while different clients run in parallel.
     */
    private static final class Node {

        private final long emissionIntervalMillis;
        private final long burstToleranceMillis;
        private long tat = Long.MIN_VALUE;   // theoretical arrival time; unset until first use

        Node(long emissionIntervalMillis, long burstToleranceMillis) {
            this.emissionIntervalMillis = emissionIntervalMillis;
            this.burstToleranceMillis = burstToleranceMillis;
        }

        synchronized boolean allowRequest(long now) {
            if (tat == Long.MIN_VALUE) {          // the first request sets the schedule
                tat = now;
            }
            if (now < tat - burstToleranceMillis) {   // more than tau ahead of schedule
                return false;
            }
            // On time or early within tolerance: allow, and push the next due time out
            // by one interval (from now if the client was late).
            tat = Math.max(now, tat) + emissionIntervalMillis;
            return true;
        }
    }
}
