package com.lld.ratelimiter;

import java.time.Clock;
import java.time.Duration;

/**
 * TODO: your implementation.
 *
 * <p>This file is a stub so the acceptance tests compile. Read
 * {@code docs/problems/01-rate-limiter.md} for the full contract, then design and
 * implement it yourself. The public shape below is the API the tests use; do not
 * change it (you may add anything you like internally, and you may add new types).
 *
 * <p>Design questions to answer before you write code:
 * <ul>
 *   <li>What state must you store per client, and what is the key?</li>
 *   <li>Where does "now" come from, and why is it injected?</li>
 *   <li>Which state is shared across threads, and how will you protect it?</li>
 * </ul>
 */
public class FixedWindowRateLimiter implements RateLimiter {

    public FixedWindowRateLimiter(int maxRequests, Duration window) {
        this(maxRequests, window, Clock.systemUTC());
    }

    public FixedWindowRateLimiter(int maxRequests, Duration window, Clock clock) {
        // TODO: validate arguments (maxRequests > 0; window non-null and positive; clock non-null)
        // TODO: store what you need
    }

    @Override
    public boolean allowRequest(String clientId) {
        // TODO: fixed-window counting keyed by clientId, aligned to the injected clock
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
