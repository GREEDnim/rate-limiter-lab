package com.lld.ratelimiter.store;

import java.time.Duration;

/**
 * The seam for <em>shared</em> rate-limit state.
 *
 * <p>Every limiter so far kept its counters in the JVM, which is only correct for a
 * single process. This interface pulls that state out so many processes can share one
 * limit and every change is atomic across them. It exposes only the <b>atomic
 * primitives</b> the algorithms need — the policy (which key, which limit) stays in the
 * limiter.
 *
 * <p>Implementations: an in-memory store for tests ({@link InMemoryRateLimitStore}),
 * and a Redis store in production (one Lua script per method; see the problem write-up).
 */
public interface RateLimitStore {

    /**
     * Atomically increment the counter at {@code key} and return the new value.
     *
     * <p>The TTL is applied <b>only when the key is created</b> (the first write), not on
     * every increment — otherwise a fixed window would never close. The increment and the
     * TTL must be a single atomic step, so a crash can never leave a key without an
     * expiry.
     *
     * @param key the counter key; must be non-null and non-blank
     * @param ttl how long the key should live; must be non-null and positive
     * @return the value after incrementing (first write returns {@code 1})
     */
    long incrementAndGet(String key, Duration ttl);

    /**
     * The store's clock — the single source of "now" that every process shares, so they
     * all compute the same window.
     *
     * @return the current time in milliseconds since the epoch
     */
    long nowMillis();
}
