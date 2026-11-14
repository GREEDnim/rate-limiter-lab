package com.lld.ratelimiter.tokenbucket;

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
 * Functional behaviour (the spec) for a token-bucket rate limiter.
 *
 * <p>The bucket holds up to {@code capacity} tokens, refills continuously at
 * {@code refillTokens / refillPeriod}, and each request spends one token. A client's
 * bucket starts full. Every test drives an injected {@link MutableClock} so time —
 * and therefore the refill — is exact, and time only ever moves forward.
 */
class TokenBucketRateLimiterTest {

    private static final Instant START = Instant.parse("2024-01-01T00:00:00Z");

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
    }

    /** Builds a limiter that reads the test-controlled {@link #clock}. */
    private RateLimiter limiter(int capacity, int refillTokens, Duration refillPeriod) {
        return new TokenBucketRateLimiter(capacity, refillTokens, refillPeriod, clock);
    }

    /**
     * A fresh bucket is full, so a client may burst up to {@code capacity} at once and
     * is rejected once the bucket is empty (no time has passed to refill it). Both
     * outcomes are asserted, so an always-allow or always-reject implementation fails.
     */
    @Test
    void allows_a_burst_up_to_capacity_then_rejects() {
        RateLimiter limiter = limiter(5, 5, Duration.ofSeconds(1));

        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.allowRequest("alice"), "burst request " + i + " should be allowed");
        }
        assertFalse(limiter.allowRequest("alice"), "the bucket is empty");
        assertFalse(limiter.allowRequest("alice"), "still empty; no time has passed");
    }

    /** A brand-new client starts with a full bucket regardless of the wall time. */
    @Test
    void a_new_client_starts_with_a_full_bucket() {
        clock.setInstant(START.plusMillis(700));
        RateLimiter limiter = limiter(3, 3, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));
    }

    /**
     * Refill is spread over time: with 5 tokens per second, a drained bucket regains
     * exactly one token every 200 ms.
     */
    @Test
    void refills_one_token_per_tick() {
        RateLimiter limiter = limiter(5, 5, Duration.ofSeconds(1));
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.allowRequest("alice"));
        }

        for (int tick = 1; tick <= 3; tick++) {
            clock.advance(Duration.ofMillis(200)); // one token's worth
            assertTrue(limiter.allowRequest("alice"), "tick " + tick + ": refilled one token");
            assertFalse(limiter.allowRequest("alice"), "tick " + tick + ": only one was available");
        }
    }

    /** The bucket never refills above {@code capacity}, no matter how long it idles. */
    @Test
    void caps_refill_at_capacity() {
        RateLimiter limiter = limiter(3, 3, Duration.ofSeconds(1));
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.allowRequest("alice"));
        }
        assertFalse(limiter.allowRequest("alice"));

        clock.advance(Duration.ofSeconds(10)); // far more than enough to refill

        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.allowRequest("alice"), "capped at capacity, request " + i);
        }
        assertFalse(limiter.allowRequest("alice"), "the cap is exactly capacity, no more");
    }

    /**
     * Sub-token time is not lost: half a refill period buys half a token, and two
     * halves make a whole one.
     */
    @Test
    void accumulates_partial_refills() {
        RateLimiter limiter = limiter(1, 1, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice")); // spent the only token
        assertFalse(limiter.allowRequest("alice"));

        clock.advance(Duration.ofMillis(500)); // half a token
        assertFalse(limiter.allowRequest("alice"), "half a token is not enough");

        clock.advance(Duration.ofMillis(500)); // the other half
        assertTrue(limiter.allowRequest("alice"), "two halves make a whole token");
        assertFalse(limiter.allowRequest("alice"));
    }

    /**
     * Refill is continuous, not quantised to whole ticks: 250 ms at 10/s is 2.5
     * tokens, which admits two requests and leaves a half.
     */
    @Test
    void counts_continuous_refill_not_discrete_ticks() {
        RateLimiter limiter = limiter(10, 10, Duration.ofSeconds(1));
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.allowRequest("alice"));
        }

        clock.advance(Duration.ofMillis(250)); // 2.5 tokens
        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"), "only 0.5 of a token is left");

        clock.advance(Duration.ofMillis(250)); // 0.5 + 2.5 = 3.0 tokens
        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));
    }

    /**
     * The sustained rate is exact even when the period does not divide evenly by the
     * token count. 3 tokens per 1000 ms is one every 333.33 ms, so 333 ms is just
     * short of a token and 334 ms has crossed it. (An integer "millis per token"
     * shortcut would wrongly admit at 333 ms.)
     */
    @Test
    void refills_at_the_exact_rate_even_when_it_does_not_divide_evenly() {
        RateLimiter limiter = limiter(3, 3, Duration.ofSeconds(1));
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.allowRequest("alice"));
        }

        clock.advance(Duration.ofMillis(333)); // 0.999 of a token
        assertFalse(limiter.allowRequest("alice"), "333 ms is just short of one token");

        clock.advance(Duration.ofMillis(1)); // 1.002 tokens
        assertTrue(limiter.allowRequest("alice"), "334 ms has crossed one token");
        assertFalse(limiter.allowRequest("alice"));
    }

    /**
     * Rejected requests must not spend tokens: hammering while empty leaves the
     * refill untouched, so the client recovers exactly on schedule.
     */
    @Test
    void rejected_requests_do_not_consume_tokens() {
        RateLimiter limiter = limiter(1, 1, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice")); // bucket empty

        clock.setInstant(START.plusMillis(500)); // half a token
        for (int i = 0; i < 1_000; i++) {
            assertFalse(limiter.allowRequest("alice"), "attempt " + i);
        }

        clock.setInstant(START.plusMillis(1000)); // the other half -> one whole token
        assertTrue(limiter.allowRequest("alice"), "recovers on schedule despite the rejections");
        assertFalse(limiter.allowRequest("alice"));
    }

    /** Buckets are per client: one client's spending must not affect another's. */
    @Test
    void keeps_per_client_buckets_independent() {
        RateLimiter limiter = limiter(1, 1, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"), "alice is empty");
        assertTrue(limiter.allowRequest("bob"), "bob has his own full bucket");
        assertFalse(limiter.allowRequest("bob"));
    }

    /** A client that goes quiet refills back to a full bucket and may burst again. */
    @Test
    void a_quiet_client_can_burst_again() {
        RateLimiter limiter = limiter(2, 2, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));

        clock.advance(Duration.ofSeconds(5));

        assertTrue(limiter.allowRequest("alice"));
        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));
    }

    /** Boundary value {@code capacity == 1}: a single token, refilled once a period. */
    @Test
    void capacity_of_one_allows_one_then_refills() {
        RateLimiter limiter = limiter(1, 1, Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));

        clock.advance(Duration.ofSeconds(1));

        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));
    }

    /** A null, empty, or whitespace-only client id is rejected up front. */
    @Test
    void rejects_null_or_blank_client_id() {
        RateLimiter limiter = limiter(1, 1, Duration.ofSeconds(1));

        assertThrows(IllegalArgumentException.class, () -> limiter.allowRequest(null));
        assertThrows(IllegalArgumentException.class, () -> limiter.allowRequest(""));
        assertThrows(IllegalArgumentException.class, () -> limiter.allowRequest("   "));
    }

    /** Bad capacity, refill amount, and period are rejected at construction time. */
    @Test
    void rejects_invalid_construction_arguments() {
        assertThrows(IllegalArgumentException.class, () -> limiter(0, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> limiter(-1, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> limiter(1, 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> limiter(1, -1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> limiter(1, 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> limiter(1, 1, Duration.ofSeconds(-1)));
        assertThrows(NullPointerException.class,
                () -> new TokenBucketRateLimiter(1, 1, null, clock));
    }

    /** A null clock is rejected (it would leave the limiter with no time source). */
    @Test
    void rejects_a_null_clock() {
        assertThrows(NullPointerException.class,
                () -> new TokenBucketRateLimiter(1, 1, Duration.ofSeconds(1), null));
    }

    /** The three-argument convenience constructor works against the real system clock. */
    @Test
    void three_arg_constructor_defaults_to_the_system_clock() {
        // 1 token per minute: no meaningful refill mid-test.
        RateLimiter limiter = new TokenBucketRateLimiter(1, 1, Duration.ofMinutes(1));

        assertTrue(limiter.allowRequest("alice"));
        assertFalse(limiter.allowRequest("alice"));
    }
}
