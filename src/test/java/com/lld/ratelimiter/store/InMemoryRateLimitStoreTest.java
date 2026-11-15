package com.lld.ratelimiter.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lld.support.MutableClock;

/**
 * Functional behaviour (the spec) for the in-memory {@link RateLimitStore}: atomic
 * increments with a TTL set once, and a clock the caller shares.
 */
class InMemoryRateLimitStoreTest {

    private static final Instant START = Instant.parse("2024-01-01T00:00:00Z");

    private MutableClock clock;
    private RateLimitStore store;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        store = new InMemoryRateLimitStore(clock);
    }

    /** The first increment of a key is 1, and it counts up from there. */
    @Test
    void first_increment_is_one_then_counts_up() {
        Duration ttl = Duration.ofSeconds(1);

        assertEquals(1L, store.incrementAndGet("k", ttl));
        assertEquals(2L, store.incrementAndGet("k", ttl));
        assertEquals(3L, store.incrementAndGet("k", ttl));
    }

    /** Different keys have independent counters. */
    @Test
    void distinct_keys_are_independent() {
        Duration ttl = Duration.ofSeconds(1);

        assertEquals(1L, store.incrementAndGet("a", ttl));
        assertEquals(1L, store.incrementAndGet("b", ttl));
        assertEquals(2L, store.incrementAndGet("a", ttl));
    }

    /**
     * The TTL is set when the key is created and is <em>not</em> extended by later
     * increments — so the key expires one TTL after its first write even if it kept
     * being incremented.
     */
    @Test
    void ttl_is_set_once_and_does_not_slide() {
        Duration ttl = Duration.ofSeconds(1);

        assertEquals(1L, store.incrementAndGet("k", ttl)); // expires at t = 1000
        clock.advance(Duration.ofMillis(500));
        assertEquals(2L, store.incrementAndGet("k", ttl)); // same key, no TTL change
        clock.advance(Duration.ofMillis(499)); // t = 999, still alive
        assertEquals(3L, store.incrementAndGet("k", ttl));
        clock.advance(Duration.ofMillis(1)); // t = 1000, the original TTL has elapsed
        assertEquals(1L, store.incrementAndGet("k", ttl), "the key expired and started over");
    }

    /** A long idle also lets the key expire, so the next increment starts at 1 again. */
    @Test
    void a_key_resets_after_its_ttl() {
        Duration ttl = Duration.ofMillis(100);

        assertEquals(1L, store.incrementAndGet("k", ttl));
        assertEquals(2L, store.incrementAndGet("k", ttl));

        clock.advance(Duration.ofMillis(101));
        assertEquals(1L, store.incrementAndGet("k", ttl), "the key had expired");
    }

    /** The store reports the injected clock, so callers share one source of "now". */
    @Test
    void now_millis_reflects_the_injected_clock() {
        assertEquals(START.toEpochMilli(), store.nowMillis());
        clock.advance(Duration.ofSeconds(5));
        assertEquals(START.plusSeconds(5).toEpochMilli(), store.nowMillis());
    }

    /** A null, empty, or whitespace-only key is rejected. */
    @Test
    void rejects_blank_key() {
        Duration ttl = Duration.ofSeconds(1);

        assertThrows(IllegalArgumentException.class, () -> store.incrementAndGet(null, ttl));
        assertThrows(IllegalArgumentException.class, () -> store.incrementAndGet("", ttl));
        assertThrows(IllegalArgumentException.class, () -> store.incrementAndGet("   ", ttl));
    }

    /** A null, zero, or negative TTL is rejected. */
    @Test
    void rejects_invalid_ttl() {
        assertThrows(IllegalArgumentException.class,
                () -> store.incrementAndGet("k", Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> store.incrementAndGet("k", Duration.ofSeconds(-1)));
        assertThrows(NullPointerException.class, () -> store.incrementAndGet("k", null));
    }
}
