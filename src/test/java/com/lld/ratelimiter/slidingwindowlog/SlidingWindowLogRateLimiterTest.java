package com.lld.ratelimiter.slidingwindowlog;

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
 * Functional behaviour (the spec) for a sliding-window-log rate limiter.
 *
 * <p>The window is the half-open interval {@code (now - window, now]}: a request
 * made at {@code t} is allowed again at exactly {@code t + window}. Every test
 * drives an injected {@link MutableClock} so time is exact.
 *
 * <p>These tests only ever move time <em>forward</em> (equal instants allowed) —
 * the same assumption the implementation may make about a client's calls: for one
 * client, {@code now} is non-decreasing. No test steps the clock backward.
 */
class SlidingWindowLogRateLimiterTest {

    private static final Instant START = Instant.parse("2024-01-01T00:00:00Z");

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
    }

    /** Builds a limiter that counts against the test-controlled {@link #clock}. */
    private RateLimiter limiter(int maxRequests, Duration window) {
        return new SlidingWindowLogRateLimiter(maxRequests, window, clock);
    }

    /**
     * The core rule: within one window, the first {@code maxRequests} calls are
     * allowed and every call after that is rejected. Both outcomes are asserted, so
     * an always-allow or always-reject implementation fails.
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
     * The window is rolling, so budget frees <em>one request at a time</em> as
     * individual timestamps age out — not all at once at a boundary.
     */
    @Test
    void frees_budget_one_request_at_a_time_as_timestamps_age_out() {
        RateLimiter limiter = limiter(3, Duration.ofSeconds(1));

        clock.setInstant(START);                                  // recorded {0}
        assertTrue(limiter.allowRequest("alice"));
        clock.setInstant(START.plusMillis(100));                  // recorded {0,100}
        assertTrue(limiter.allowRequest("alice"));
        clock.setInstant(START.plusMillis(200));                  // recorded {0,100,200}
        assertTrue(limiter.allowRequest("alice"));
        clock.setInstant(START.plusMillis(300));                  // 3 still in window
        assertFalse(limiter.allowRequest("alice"));

        clock.setInstant(START.plusMillis(1000));                 // 0 aged out -> {100,200}
        assertTrue(limiter.allowRequest("alice"), "the request at 0 has expired");
        clock.setInstant(START.plusMillis(1100));                 // 100 aged out
        assertTrue(limiter.allowRequest("alice"), "the request at 100 has expired");
        clock.setInstant(START.plusMillis(1200));                 // 200 aged out
        assertTrue(limiter.allowRequest("alice"), "the request at 200 has expired");
    }

    /**
     * The boundary: the window is {@code (now - window, now]}, so a request exactly
     * one window old has expired and is allowed again.
     */
    @Test
    void a_request_exactly_one_window_old_has_expired() {
        RateLimiter limiter = limiter(1, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));
        clock.setInstant(START.plusMillis(999));
        assertFalse(limiter.allowRequest("alice"), "999ms later is still inside the window");
        clock.setInstant(START.plusMillis(1000));
        assertTrue(limiter.allowRequest("alice"), "exactly one window later has expired");
    }

    /**
     * Rejected requests must not be recorded. If they were, a throttled client's
     * window would keep sliding forward and it could never recover.
     */
    @Test
    void rejected_requests_are_not_recorded() {
        RateLimiter limiter = limiter(1, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));                // recorded {0}
        clock.setInstant(START.plusMillis(100));
        assertFalse(limiter.allowRequest("alice"), "rejected and must not be recorded");
        clock.setInstant(START.plusMillis(1000));
        assertTrue(limiter.allowRequest("alice"),
                "only the allowed request at 0 was recorded, and it has now expired");
    }

    /**
     * The window is anchored to the request, not to an epoch boundary. Unlike a
     * fixed window, crossing a "nice" boundary does not reset the budget.
     */
    @Test
    void window_is_rolling_not_aligned_to_epoch_boundaries() {
        clock.setInstant(START.plusMillis(900));
        RateLimiter limiter = limiter(1, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));                // recorded {900}
        clock.setInstant(START.plusMillis(1000));                 // a fixed window would reset here
        assertFalse(limiter.allowRequest("alice"), "900ms is only 100ms ago; still inside the window");
        clock.setInstant(START.plusMillis(1899));
        assertFalse(limiter.allowRequest("alice"), "899ms ago is still inside the window");
        clock.setInstant(START.plusMillis(1900));
        assertTrue(limiter.allowRequest("alice"), "exactly 1s after 900ms has expired");
    }

    /** Budgets are per client: one client's exhausted log must not affect another. */
    @Test
    void keeps_per_client_budgets_independent() {
        RateLimiter limiter = limiter(1, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"), "alice is out of budget");
        assertTrue(limiter.allowRequest("bob"), "bob has his own budget");
        assertFalse(limiter.allowRequest("bob"));
    }

    /** A client that disappears long enough comes back to a full budget. */
    @Test
    void an_idle_client_gets_a_fresh_budget_when_it_returns() {
        RateLimiter limiter = limiter(1, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));

        clock.advance(Duration.ofSeconds(10)); // far longer than the window

        assertTrue(limiter.allowRequest("alice"), "budget frees after idling");
    }

    /** The exact limit holds for window after window, with no drift. */
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

    /** Boundary value {@code maxRequests == 1}: at most one request per window. */
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

    /** A longer window: the limit is about the length, not the number of calls. */
    @Test
    void honours_a_longer_window() {
        RateLimiter limiter = limiter(2, Duration.ofMinutes(1));

        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));

        clock.advance(Duration.ofSeconds(59));
        assertFalse(limiter.allowRequest("alice"), "still inside the minute");
        clock.advance(Duration.ofSeconds(1)); // 60s after the first two
        assertTrue(limiter.allowRequest("alice"), "first request has aged out");
    }

    /**
     * Several calls at the exact same instant all land in that instant, and the
     * whole batch expires together exactly one window later.
     */
    @Test
    void calls_at_the_same_instant_share_that_instant() {
        RateLimiter limiter = limiter(3, Duration.ofSeconds(1)); // all at t = 0

        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));

        clock.setInstant(START.plusMillis(999));
        assertFalse(limiter.allowRequest("alice"), "still inside the window");

        clock.setInstant(START.plusMillis(1000));
        assertTrue(limiter.allowRequest("alice"), "the t=0 batch has expired");
        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));
    }

    /**
     * A steady stream paced at exactly the sustained rate (here 2 per second, i.e.
     * one every 500 ms) is never throttled: each request arrives just as an older
     * one ages out.
     */
    @Test
    void sustains_a_steady_stream_at_the_sustained_rate() {
        RateLimiter limiter = limiter(2, Duration.ofSeconds(1));

        for (int i = 0; i <= 6; i++) {
            clock.setInstant(START.plusMillis(500L * i));
            assertTrue(limiter.allowRequest("alice"), "request #" + i + " should be allowed");
        }
    }

    /** A gap longer than the window frees every recorded request at once. */
    @Test
    void a_long_gap_frees_the_whole_budget_at_once() {
        RateLimiter limiter = limiter(3, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));

        clock.advance(Duration.ofSeconds(5));

        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));
    }

    /**
     * Rejected requests must not bank credit or extend the window: a client that
     * hammers while throttled recovers exactly one window after its last
     * <em>allowed</em> request, not later.
     */
    @Test
    void hammering_while_throttled_does_not_bank_credit() {
        RateLimiter limiter = limiter(1, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));                // recorded {0}
        for (int i = 0; i < 1_000; i++) {
            assertFalse(limiter.allowRequest("alice"), "rejected attempt " + i);
        }

        clock.setInstant(START.plusMillis(999));
        assertFalse(limiter.allowRequest("alice"));
        clock.setInstant(START.plusMillis(1000));
        assertTrue(limiter.allowRequest("alice"),
                "recovers exactly one window after the allowed request");
    }

    /**
     * Each client has its own rolling window, anchored to that client's own
     * requests — one client's window does not move another's.
     */
    @Test
    void each_client_has_its_own_rolling_window() {
        RateLimiter limiter = limiter(1, Duration.ofSeconds(1));

        clock.setInstant(START);                                  // alice at 0
        assertTrue(limiter.allowRequest("alice"));
        clock.setInstant(START.plusMillis(500));                  // bob at 500
        assertTrue(limiter.allowRequest("bob"));

        clock.setInstant(START.plusMillis(1000));                 // alice's 0 expired, bob's 500 not yet
        assertTrue(limiter.allowRequest("alice"), "alice's window is anchored to her own request");
        assertFalse(limiter.allowRequest("bob"), "bob's request is still inside his window");

        clock.setInstant(START.plusMillis(1500));                 // bob's 500 has expired
        assertTrue(limiter.allowRequest("bob"));
    }

    /** A large limit admits a large burst, then throttles, then refills wholesale. */
    @Test
    void a_large_limit_admits_a_large_burst_then_throttles() {
        int n = 100;
        RateLimiter limiter = limiter(n, Duration.ofSeconds(1));

        for (int i = 0; i < n; i++) {
            assertTrue(limiter.allowRequest("alice"), "request " + i + " of " + n);
        }
        assertFalse(limiter.allowRequest("alice"));

        clock.advance(Duration.ofSeconds(1));

        for (int i = 0; i < n; i++) {
            assertTrue(limiter.allowRequest("alice"), "refill " + i + " of " + n);
        }
    }

    /** Client ids are distinct keys: different casing is a different client. */
    @Test
    void client_ids_are_distinct() {
        RateLimiter limiter = limiter(1, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("Alice"), "a different id has its own budget");
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
                () -> new SlidingWindowLogRateLimiter(1, null, clock));
    }

    /** A null clock is rejected (it would leave the limiter with no time source). */
    @Test
    void rejects_a_null_clock() {
        assertThrows(NullPointerException.class,
                () -> new SlidingWindowLogRateLimiter(1, Duration.ofSeconds(1), null));
    }

    /** The two-argument convenience constructor works against the real system clock. */
    @Test
    void two_arg_constructor_defaults_to_the_system_clock() {
        // 1 request per minute: no chance of ageing out mid-test.
        RateLimiter limiter = new SlidingWindowLogRateLimiter(1, Duration.ofMinutes(1));

        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));
    }
}
