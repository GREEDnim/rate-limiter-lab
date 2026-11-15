package com.lld.ratelimiter.store;

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
 * Functional behaviour (the spec) for the store-backed fixed-window limiter. It should
 * behave exactly like problem 01's limiter, but its counter lives in the shared store
 * and its window comes from the <em>store's</em> clock.
 */
class StoreFixedWindowRateLimiterTest {

    private static final Instant START = Instant.parse("2024-01-01T00:00:00Z");

    private MutableClock clock;
    private RateLimitStore store;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        store = new InMemoryRateLimitStore(clock);
    }

    private RateLimiter limiter(int maxRequests, Duration window) {
        return new StoreFixedWindowRateLimiter(maxRequests, window, store);
    }

    /**
     * The core rule: the first {@code maxRequests} calls in a window are allowed and the
     * rest rejected. Both outcomes are asserted, so an always-allow or always-reject
     * implementation fails.
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
     * The window is driven by the <em>store's</em> clock: when the store's time crosses a
     * window boundary the key changes and the budget resets.
     */
    @Test
    void grants_a_fresh_budget_when_the_store_clock_rolls_over() {
        RateLimiter limiter = limiter(2, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));

        clock.advance(Duration.ofSeconds(1)); // the store's clock moves to the next window

        assertTrue(limiter.allowRequest("alice"), "a new window means a fresh budget");
        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));
    }

    /**
     * Windows are epoch-aligned: a request at 700 ms is in the window `[0, 1000)`, and the
     * budget resets at the 1000 ms boundary — <em>not</em> one window after the first
     * request. (This is what distinguishing the key `clientId:windowIndex` buys.)
     */
    @Test
    void windows_are_aligned_to_the_clock_not_to_the_first_request() {
        clock.setInstant(START.plusMillis(700));
        RateLimiter limiter = limiter(1, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice")); // window [0, 1000)
        clock.setInstant(START.plusMillis(999));
        assertFalse(limiter.allowRequest("alice"), "still the same window");

        clock.setInstant(START.plusMillis(1000)); // the next window begins
        assertTrue(limiter.allowRequest("alice"), "a new epoch-aligned window resets the budget");
        clock.setInstant(START.plusMillis(1999));
        assertFalse(limiter.allowRequest("alice"));

        clock.setInstant(START.plusMillis(2000));
        assertTrue(limiter.allowRequest("alice"));
    }

    /** Budgets are per client: one client's count must not affect another. */
    @Test
    void keeps_per_client_budgets_independent() {
        RateLimiter limiter = limiter(1, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"), "alice is out of budget");
        assertTrue(limiter.allowRequest("bob"), "bob has his own budget");
        assertFalse(limiter.allowRequest("bob"));
    }

    /** A client idle for more than a window comes back to a full budget. */
    @Test
    void an_idle_client_gets_a_fresh_budget() {
        RateLimiter limiter = limiter(1, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));

        clock.advance(Duration.ofSeconds(10));

        assertTrue(limiter.allowRequest("alice"));
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
    }

    /** A null store is rejected (there would be nowhere to keep the state). */
    @Test
    void rejects_a_null_store() {
        assertThrows(NullPointerException.class,
                () -> new StoreFixedWindowRateLimiter(1, Duration.ofSeconds(1), null));
    }
}
