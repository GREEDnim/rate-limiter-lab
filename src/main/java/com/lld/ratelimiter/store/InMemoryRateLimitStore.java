package com.lld.ratelimiter.store;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A thread-safe, single-JVM {@link RateLimitStore}. This is a <b>test double</b> for the
 * production store (Redis): it gives the same atomic contract so the limiter can be
 * developed and tested without a real store, and it doubles as the local fallback.
 *
 * <p>It is deliberately <em>not</em> distributed — the whole point of the seam is that
 * the limiter does not care. Every increment is atomic per key via
 * {@link ConcurrentHashMap#compute}, mirroring the single atomic command / Lua script
 * the Redis store would use.
 */
public class InMemoryRateLimitStore implements RateLimitStore {

    /** Past this many keys, expired ones are dropped opportunistically (best-effort). */
    private static final int SWEEP_THRESHOLD = 1024;

    private final Clock clock;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public InMemoryRateLimitStore() {
        this(Clock.systemUTC());
    }

    public InMemoryRateLimitStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public long incrementAndGet(String key, Duration ttl) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must be non-null and non-blank");
        }
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be > 0, was " + ttl);
        }
        long now = clock.millis();
        long ttlMillis = Math.max(1L, ttl.toMillis()); // clock resolution is milliseconds

        // compute is atomic per key: the check, the create and the increment can't be
        // split by another thread, so no increment is ever lost. The TTL is fixed when
        // the key is created and never extended.
        Entry updated = entries.compute(key, (k, current) -> {
            if (current == null || current.expiresAt <= now) {
                return new Entry(1, now + ttlMillis);
            }
            // Return a NEW entry rather than mutating the shared one: compute is atomic,
            // but the caller reads the returned value after the lock is released, so a
            // mutable shared entry could be bumped by another thread in between (skipping
            // a value). Immutability keeps each caller's returned value its own.
            return new Entry(current.value + 1, current.expiresAt);
        });

        // Active expiry, like Redis's background cycle. Lazy expiry alone is not enough
        // here: an epoch-aligned window key is never read again after its window passes,
        // so without this the map would grow one dead entry per (client, window).
        if (entries.size() > SWEEP_THRESHOLD) {
            entries.values().removeIf(entry -> entry.expiresAt <= now);
        }
        return updated.value;
    }

    @Override
    public long nowMillis() {
        return clock.millis();
    }

    /** One counter: its value and the instant it expires. Immutable, replaced on write. */
    private static final class Entry {

        private final long value;
        private final long expiresAt;

        Entry(long value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }
}
