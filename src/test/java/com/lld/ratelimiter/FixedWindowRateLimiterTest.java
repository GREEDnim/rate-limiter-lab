package com.lld.ratelimiter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lld.support.MutableClock;

/**
 * Acceptance tests (the spec) for a fixed-window rate limiter.
 * Do not edit these to make them pass — they are the requirements.
 */
class FixedWindowRateLimiterTest {

    private static final Instant START = Instant.parse("2024-01-01T00:00:00Z");

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
    }

    private RateLimiter limiter(int maxRequests, Duration window) {
        return new FixedWindowRateLimiter(maxRequests, window, clock);
    }

    @Test
    void allows_up_to_the_limit_then_rejects_within_one_window() {
        RateLimiter limiter = limiter(3, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"), "1st of 3 should be allowed");
        assertTrue(limiter.allowRequest("alice"), "2nd of 3 should be allowed");
        assertTrue(limiter.allowRequest("alice"), "3rd of 3 should be allowed");
        assertFalse(limiter.allowRequest("alice"), "4th in the same window must be rejected");
        assertFalse(limiter.allowRequest("alice"), "5th in the same window must be rejected");
    }

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

    @Test
    void keeps_per_client_budgets_independent() {
        RateLimiter limiter = limiter(1, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"), "alice is out of budget");
        assertTrue(limiter.allowRequest("bob"), "bob has his own budget");
        assertFalse(limiter.allowRequest("bob"));
    }

    @Test
    void windows_are_aligned_to_the_clock_not_to_the_first_request() {
        // The window is a fixed 1s interval aligned to the epoch. A request made
        // 900ms into a window expires at the 1000ms boundary, not 900ms later.
        clock.setInstant(START.plusMillis(900));
        RateLimiter limiter = limiter(1, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));

        clock.setInstant(START.plusMillis(1000)); // the next window begins

        assertTrue(limiter.allowRequest("alice"), "a new epoch-aligned window resets the budget");
    }

    @Test
    void rejects_null_or_blank_client_id() {
        RateLimiter limiter = limiter(1, Duration.ofSeconds(1));

        assertThrows(IllegalArgumentException.class, () -> limiter.allowRequest(null));
        assertThrows(IllegalArgumentException.class, () -> limiter.allowRequest(""));
        assertThrows(IllegalArgumentException.class, () -> limiter.allowRequest("   "));
    }

    @Test
    void rejects_invalid_construction_arguments() {
        assertThrows(IllegalArgumentException.class, () -> limiter(0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> limiter(-1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> limiter(1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> limiter(1, Duration.ofSeconds(-1)));
        assertThrows(NullPointerException.class, () -> new FixedWindowRateLimiter(1, null, clock));
    }
}
