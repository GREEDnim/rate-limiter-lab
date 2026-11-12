package com.lld.ratelimiter.slidingwindowcounter;

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
 * Functional behaviour (the spec) for a sliding-window-counter rate limiter.
 *
 * <p>The decision is the estimate
 * {@code previousCount * (W - elapsed)/W + currentCount < maxRequests}, where
 * {@code W} is the window length and {@code elapsed} is {@code now % W}. Windows are
 * epoch-aligned, and the window counters shift forward as time advances. Every test
 * drives an injected {@link MutableClock} so time is exact, and time only ever moves
 * forward.
 */
class SlidingWindowCounterRateLimiterTest {

    private static final Instant START = Instant.parse("2024-01-01T00:00:00Z");

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
    }

    /** Builds a limiter that counts against the test-controlled {@link #clock}. */
    private RateLimiter limiter(int maxRequests, Duration window) {
        return new SlidingWindowCounterRateLimiter(maxRequests, window, clock);
    }

    /**
     * The core rule with an empty previous window: the first {@code maxRequests}
     * calls are allowed and every call after that is rejected. Both outcomes are
     * asserted, so an always-allow or always-reject implementation fails.
     */
    @Test
    void allows_up_to_the_limit_then_rejects_within_one_window() {
        RateLimiter limiter = limiter(3, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"), "1st of 3 should be allowed");
        assertTrue(limiter.allowRequest("alice"), "2nd of 3 should be allowed");
        assertTrue(limiter.allowRequest("alice"), "3rd of 3 should be allowed");
        assertFalse(limiter.allowRequest("alice"), "4th in the same window must be rejected");
        assertFalse(limiter.allowRequest("alice"), "5th in the same window must be rejected");
    }

    /**
     * With no previous window to carry, being part-way through a window costs
     * nothing: a fresh client still gets its full budget.
     */
    @Test
    void a_fresh_client_mid_window_gets_the_full_limit() {
        clock.setInstant(START.plusMillis(500));
        RateLimiter limiter = limiter(2, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));
    }

    /**
     * At the instant the new window begins ({@code elapsed = 0}) the previous window
     * still overlaps fully, so a previous window that used the whole limit leaves no
     * budget at all.
     */
    @Test
    void a_full_previous_window_carries_at_full_weight_at_the_boundary() {
        RateLimiter limiter = limiter(10, Duration.ofSeconds(1));

        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.allowRequest("alice"), "filling window 0, request " + i);
        }
        assertFalse(limiter.allowRequest("alice"));

        clock.setInstant(START.plusMillis(1000)); // window 1, elapsed = 0
        assertFalse(limiter.allowRequest("alice"),
                "the previous window's 10 still fully overlap the trailing window");
    }

    /**
     * Half-way through the window, the previous window is weighted at 1/2, so about
     * half of the previous budget frees up. Previous = 10, so the base is 5 and the
     * current window may add 5 more (estimate must stay &lt; 10).
     */
    @Test
    void frees_half_the_previous_budget_halfway_through_the_window() {
        RateLimiter limiter = limiter(10, Duration.ofSeconds(1));

        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.allowRequest("alice"));
        }

        clock.setInstant(START.plusMillis(1500)); // window 1, elapsed = 500
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.allowRequest("alice"), "within the decayed budget, request " + i);
        }
        assertFalse(limiter.allowRequest("alice"), "estimate has reached the limit");
    }

    /**
     * Three-quarters through the window the previous window is weighted at 1/4, so
     * 7.5 of the previous 10 remain: the current window may add 8 (estimate &lt; 10).
     */
    @Test
    void frees_three_quarters_at_three_quarters_through_the_window() {
        RateLimiter limiter = limiter(10, Duration.ofSeconds(1));

        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.allowRequest("alice"));
        }

        clock.setInstant(START.plusMillis(1750)); // window 1, elapsed = 750
        for (int i = 0; i < 8; i++) {
            assertTrue(limiter.allowRequest("alice"), "within the decayed budget, request " + i);
        }
        assertFalse(limiter.allowRequest("alice"), "estimate has reached the limit");
    }

    /**
     * A gap of two or more windows means the previous window is forgotten entirely, so
     * the client gets a full budget — no decay-driven carry-over from long ago.
     */
    @Test
    void gap_of_two_windows_drops_the_previous_count() {
        RateLimiter limiter = limiter(3, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));

        clock.setInstant(START.plusMillis(2000)); // skip window 1 entirely
        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));
    }

    /**
     * Only the <em>immediately</em> previous window is carried. Window 0 fills, window
     * 1 partially refills as window 0 decays, and window 2 carries window 1's count —
     * not window 0's.
     */
    @Test
    void only_the_immediately_previous_window_is_carried() {
        RateLimiter limiter = limiter(3, Duration.ofSeconds(1));

        // window 0 fills to 3
        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));

        // window 1 at its start: previous = 3 -> no budget yet
        clock.setInstant(START.plusMillis(1000));
        assertFalse(limiter.allowRequest("alice"));

        // window 1 half-way: base = 3 * 0.5 = 1.5 -> two more (1.5, 2.5), then rejected at 3.5
        clock.setInstant(START.plusMillis(1500));
        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));

        // window 2 at its start: previous = window 1's count (2), not window 0's 3 -> one more, then rejected
        clock.setInstant(START.plusMillis(2000));
        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));
    }

    /**
     * Rejected requests must not change the counters: hammering while throttled must
     * not push the estimate up or bank any credit.
     */
    @Test
    void rejected_requests_do_not_consume_budget() {
        RateLimiter limiter = limiter(3, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));

        clock.setInstant(START.plusMillis(1500)); // base = 3 * 0.5 = 1.5
        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        for (int i = 0; i < 1_000; i++) {
            assertFalse(limiter.allowRequest("alice"), "must stay rejected at attempt " + i);
        }
    }

    /** Budgets are per client: one client's counters must not affect another's. */
    @Test
    void keeps_per_client_budgets_independent() {
        RateLimiter limiter = limiter(1, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"), "alice is out of budget");
        assertTrue(limiter.allowRequest("bob"), "bob has his own budget");
        assertFalse(limiter.allowRequest("bob"));
    }

    /** A client that is idle for more than a window comes back to a full budget. */
    @Test
    void an_idle_client_gets_a_full_budget_when_it_returns() {
        RateLimiter limiter = limiter(1, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));

        clock.advance(Duration.ofSeconds(10)); // a gap of many windows

        assertTrue(limiter.allowRequest("alice"), "budget is full again after idling");
    }

    /**
     * Boundary value {@code maxRequests == 1}: a previous request keeps the client
     * throttled at the boundary but frees one millisecond later as it decays.
     */
    @Test
    void limit_of_one_frees_just_after_the_boundary() {
        RateLimiter limiter = limiter(1, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));

        clock.setInstant(START.plusMillis(1000)); // previous = 1, elapsed = 0 -> estimate 1
        assertFalse(limiter.allowRequest("alice"));

        clock.setInstant(START.plusMillis(1001)); // weight 999/1000 -> estimate 0.999 < 1
        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));
    }

    /** A null, empty, or whitespace-only client id is rejected up front. */
    @Test
    void rejects_null_or_blank_client_id() {
        RateLimiter limiter = limiter(1, Duration.ofSeconds(1));

        assertThrows(IllegalArgumentException.class, () -> limiter.allowRequest(null));
        assertThrows(IllegalArgumentException.class, () -> limiter.allowRequest(""));
        assertThrows(IllegalArgumentException.class, () -> limiter.allowRequest("   "));
    }

    /** Bad limits and windows are rejected at construction time. */
    @Test
    void rejects_invalid_construction_arguments() {
        assertThrows(IllegalArgumentException.class, () -> limiter(0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> limiter(-1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> limiter(1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> limiter(1, Duration.ofSeconds(-1)));
        assertThrows(NullPointerException.class,
                () -> new SlidingWindowCounterRateLimiter(1, null, clock));
    }

    /** A null clock is rejected (it would leave the limiter with no time source). */
    @Test
    void rejects_a_null_clock() {
        assertThrows(NullPointerException.class,
                () -> new SlidingWindowCounterRateLimiter(1, Duration.ofSeconds(1), null));
    }

    /** The two-argument convenience constructor works against the real system clock. */
    @Test
    void two_arg_constructor_defaults_to_the_system_clock() {
        // 1 request per minute: no chance of a window transition mid-test.
        RateLimiter limiter = new SlidingWindowCounterRateLimiter(1, Duration.ofMinutes(1));

        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));
    }
}
