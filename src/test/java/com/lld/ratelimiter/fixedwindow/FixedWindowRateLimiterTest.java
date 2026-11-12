package com.lld.ratelimiter.fixedwindow;

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
 * Functional behaviour (the spec) for a fixed-window rate limiter.
 *
 * <p>Every test drives an injected {@link MutableClock} so time can be advanced by
 * an exact duration instead of sleeping, which is what makes the window-rollover
 * behaviour deterministic.
 */
class FixedWindowRateLimiterTest {

    private static final Instant START = Instant.parse("2024-01-01T00:00:00Z");

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
    }

    /** Builds a limiter that counts against the test-controlled {@link #clock}. */
    private RateLimiter limiter(int maxRequests, Duration window) {
        return new FixedWindowRateLimiter(maxRequests, window, clock);
    }

    /**
     * The core rule: within a single window, the first {@code maxRequests} calls are
     * allowed and every call after that is rejected. Both the allowance and the
     * rejection are asserted, so an implementation that always allows or always
     * rejects fails.
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
     * When the clock crosses into the next window, the client's budget is refilled:
     * the calls that were rejected in the old window succeed again in the new one.
     */
    @Test
    void grants_a_fresh_budget_after_the_window_rolls_over() {
        RateLimiter limiter = limiter(2, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));

        clock.advance(Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"), "first request of the new window should be allowed");
        assertTrue(limiter.allowRequest("alice"), "second request of the new window should be allowed");
        assertFalse(limiter.allowRequest("alice"));
    }

    /**
     * Budgets are per client: one client exhausting its limit must have no effect on
     * another client's limit.
     */
    @Test
    void keeps_per_client_budgets_independent() {
        RateLimiter limiter = limiter(1, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"), "alice is out of budget");
        assertTrue(limiter.allowRequest("bob"), "bob has his own budget");
        assertFalse(limiter.allowRequest("bob"));
    }

    /**
     * Windows are fixed, epoch-aligned intervals, not "one window starting at the
     * client's first request". A request made 900 ms into a 1 s window is charged to
     * that window and expires at the 1000 ms boundary — not 900 ms later.
     */
    @Test
    void windows_are_aligned_to_the_clock_not_to_the_first_request() {
        clock.setInstant(START.plusMillis(900));
        RateLimiter limiter = limiter(1, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));

        clock.setInstant(START.plusMillis(1000)); // the next window begins

        assertTrue(limiter.allowRequest("alice"), "a new epoch-aligned window resets the budget");
    }

    /**
     * The reset is not a one-time event: the limiter keeps refilling correctly for
     * window after window.
     */
    @Test
    void rolls_over_repeatedly_across_many_windows() {
        RateLimiter limiter = limiter(2, Duration.ofSeconds(1));

        for (int w = 0; w < 5; w++) {
            assertTrue(limiter.allowRequest("alice"), "first of window " + w);
            assertTrue(limiter.allowRequest("alice"), "second of window " + w);
            assertFalse(limiter.allowRequest("alice"), "over limit in window " + w);
            clock.advance(Duration.ofSeconds(1));
        }
    }

    /**
     * They reset lazily on access, so time passing with no traffic is fine: a client
     * that comes back after several windows gets a full budget.
     */
    @Test
    void an_idle_client_gets_a_fresh_budget_when_it_returns() {
        RateLimiter limiter = limiter(1, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));

        clock.advance(Duration.ofSeconds(10)); // many windows pass with no traffic

        assertTrue(limiter.allowRequest("alice"), "budget resets after idling");
    }

    /**
     * Rejected requests must not change anything: they can't bank credit or refund
     * budget, so hammering while throttled leaves the client throttled.
     */
    @Test
    void throttled_requests_do_not_accumulate_credit() {
        RateLimiter limiter = limiter(2, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        for (int i = 0; i < 1_000; i++) {
            assertFalse(limiter.allowRequest("alice"), "must stay throttled at attempt " + i);
        }
        // Still the same window and still no credit banked.
        assertFalse(limiter.allowRequest("alice"));
    }

    /**
     * Boundary value {@code maxRequests == 1}: exactly one request per client per
     * window, resetting for all clients when the window rolls.
     */
    @Test
    void limit_of_one_admits_exactly_one_per_window_per_client() {
        RateLimiter limiter = limiter(1, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("bob"), "bob is unaffected by alice");
        assertFalse(limiter.allowRequest("bob"));

        clock.advance(Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"), "fresh window for alice");
        assertTrue(limiter.allowRequest("bob"), "fresh window for bob");
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
        assertThrows(NullPointerException.class, () -> new FixedWindowRateLimiter(1, null, clock));
    }

    /** A null clock is rejected (it would leave the limiter with no time source). */
    @Test
    void rejects_a_null_clock() {
        assertThrows(NullPointerException.class,
                () -> new FixedWindowRateLimiter(1, Duration.ofSeconds(1), null));
    }

    /** The two-argument convenience constructor works against the real system clock. */
    @Test
    void two_arg_constructor_defaults_to_the_system_clock() {
        // 1 request per minute: no chance of rolling over mid-test.
        RateLimiter limiter = new FixedWindowRateLimiter(1, Duration.ofMinutes(1));

        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));
    }
}
