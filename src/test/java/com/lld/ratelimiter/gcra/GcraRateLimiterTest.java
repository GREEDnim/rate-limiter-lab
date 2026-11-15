package com.lld.ratelimiter.gcra;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lld.ratelimiter.RateLimiter;
import com.lld.support.MutableClock;

/**
 * Functional behaviour (the spec) for a GCRA rate limiter.
 *
 * <p>A client is on a virtual schedule. {@code emissionInterval} = {@code T} is the
 * gap between allowed requests; {@code burstTolerance} = {@code tau} is how far
 * ahead of the schedule a client may run. From idle, the burst is
 * {@code floor(tau / T) + 1}, and the earliest a request may be admitted is
 * {@code TAT - tau}. Every test drives an injected {@link MutableClock} so time is
 * exact, and time only ever moves forward.
 */
class GcraRateLimiterTest {

    private static final Instant START = Instant.parse("2024-01-01T00:00:00Z");

    private static final Duration T = Duration.ofMillis(100); // emission interval
    private static final Duration TAU = Duration.ofMillis(900); // burst tolerance -> burst of 10

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
    }

    /** Builds a limiter that reads the test-controlled {@link #clock}. */
    private RateLimiter limiter(Duration emissionInterval, Duration burstTolerance) {
        return new GcraRateLimiter(emissionInterval, burstTolerance, clock);
    }

    /**
     * From idle a client may run {@code tau} ahead of the schedule, which is
     * {@code floor(tau / T) + 1} requests: {@code 900/100 + 1 = 10} here. Both
     * outcomes are asserted, so an always-allow or always-reject implementation
     * fails.
     */
    @Test
    void allows_a_burst_of_tolerance_over_interval_plus_one_from_idle() {
        RateLimiter limiter = limiter(T, TAU);

        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.allowRequest("alice"), "burst request " + i + " should be allowed");
        }
        assertFalse(limiter.allowRequest("alice"), "11th is more than tau ahead of the schedule");
    }

    /** With no burst tolerance the client is paced strictly: one request per interval. */
    @Test
    void no_burst_when_burst_tolerance_is_zero() {
        RateLimiter limiter = limiter(T, Duration.ZERO);

        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"), "not yet the next interval");
        clock.setInstant(START.plusMillis(99));
        assertFalse(limiter.allowRequest("alice"), "one millisecond early");
        clock.setInstant(START.plusMillis(100));
        assertTrue(limiter.allowRequest("alice"), "exactly one interval later");
        assertFalse(limiter.allowRequest("alice"));
    }

    /** A tolerance equal to the interval allows a burst of exactly two. */
    @Test
    void burst_is_two_when_tolerance_equals_the_interval() {
        RateLimiter limiter = limiter(T, T);

        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"), "the third is more than tau ahead");
    }

    /**
     * After the initial burst, requests are spaced by the emission interval: the
     * earliest admission is {@code TAT - tau}, and it admits at exactly that instant.
     */
    @Test
    void spaces_requests_by_the_emission_interval_after_a_burst() {
        RateLimiter limiter = limiter(T, TAU);
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.allowRequest("alice"));
        }

        clock.setInstant(START.plusMillis(99));
        assertFalse(limiter.allowRequest("alice"), "TAT - tau is 100 ms");
        clock.setInstant(START.plusMillis(100));
        assertTrue(limiter.allowRequest("alice"), "exactly TAT - tau");

        clock.setInstant(START.plusMillis(199));
        assertFalse(limiter.allowRequest("alice"));
        clock.setInstant(START.plusMillis(200));
        assertTrue(limiter.allowRequest("alice"));
    }

    /** A quiet client falls behind the schedule and earns a fresh burst. */
    @Test
    void a_long_idle_resets_to_a_fresh_burst() {
        RateLimiter limiter = limiter(T, TAU);
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.allowRequest("alice"));
        }

        clock.advance(Duration.ofSeconds(10));

        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.allowRequest("alice"), "fresh burst, request " + i);
        }
        assertFalse(limiter.allowRequest("alice"));
    }

    /**
     * Rejected requests must not move the schedule: a burst followed by hammering then
     * still admits exactly at {@code TAT - tau}.
     */
    @Test
    void rejected_requests_do_not_advance_the_schedule() {
        RateLimiter limiter = limiter(T, TAU);
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.allowRequest("alice"));
        }

        for (int i = 0; i < 1_000; i++) {
            assertFalse(limiter.allowRequest("alice"), "rejected attempt " + i);
        }

        clock.setInstant(START.plusMillis(99));
        assertFalse(limiter.allowRequest("alice"));
        clock.setInstant(START.plusMillis(100));
        assertTrue(limiter.allowRequest("alice"), "the schedule was unaffected by the rejections");
    }

    /** A stream paced at exactly the emission interval is never throttled. */
    @Test
    void sustained_pacing_at_the_interval_is_never_throttled() {
        RateLimiter limiter = limiter(T, Duration.ZERO);

        for (int i = 0; i <= 10; i++) {
            clock.setInstant(START.plusMillis(100L * i));
            assertTrue(limiter.allowRequest("alice"), "paced request " + i + " should be allowed");
        }
    }

    /** A new client's very first request is always allowed. */
    @Test
    void a_new_client_is_allowed_immediately() {
        clock.setInstant(START.plusMillis(700));
        RateLimiter limiter = limiter(T, TAU);

        assertTrue(limiter.allowRequest("alice"));
    }

    /** Schedules are per client: one client's advance must not affect another. */
    @Test
    void keeps_per_client_schedules_independent() {
        RateLimiter limiter = limiter(T, Duration.ZERO);

        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"), "alice is paced");
        assertTrue(limiter.allowRequest("bob"), "bob has his own schedule");
        assertFalse(limiter.allowRequest("bob"));
    }

    /** A null, empty, or whitespace-only client id is rejected up front. */
    @Test
    void rejects_null_or_blank_client_id() {
        RateLimiter limiter = limiter(T, TAU);

        assertThrows(IllegalArgumentException.class, () -> limiter.allowRequest(null));
        assertThrows(IllegalArgumentException.class, () -> limiter.allowRequest(""));
        assertThrows(IllegalArgumentException.class, () -> limiter.allowRequest("   "));
    }

    /**
     * Bad construction arguments are rejected at construction time. Note that a
     * burst tolerance of zero is <em>valid</em> (strict pacing) and must be accepted.
     */
    @Test
    void rejects_invalid_construction_arguments() {
        assertThrows(IllegalArgumentException.class, () -> limiter(Duration.ZERO, TAU));
        assertThrows(IllegalArgumentException.class, () -> limiter(Duration.ofSeconds(-1), TAU));
        assertThrows(IllegalArgumentException.class, () -> limiter(T, Duration.ofMillis(-1)));
        assertThrows(NullPointerException.class, () -> limiter(T, null));
        assertThrows(NullPointerException.class, () -> limiter(null, Duration.ZERO));
    }

    /** A null clock is rejected (it would leave the limiter with no time source). */
    @Test
    void rejects_a_null_clock() {
        assertThrows(NullPointerException.class, () -> new GcraRateLimiter(T, TAU, null));
    }

    /** The two-argument convenience constructor works against the real system clock. */
    @Test
    void two_arg_constructor_defaults_to_the_system_clock() {
        // One emission interval per hour: no chance of a second one mid-test.
        RateLimiter limiter = new GcraRateLimiter(Duration.ofHours(1), Duration.ZERO);

        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));
    }
}
