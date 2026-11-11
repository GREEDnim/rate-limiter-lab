package com.lld.ratelimiter;

/**
 * The public seam for the rate limiter.
 *
 * <p>Callers only need one question answered: is this request allowed right now?
 * Keeping this interface tiny is deliberate — implementations can change the
 * algorithm (fixed window, token bucket, sliding window) without callers caring.
 */
public interface RateLimiter {

    /**
     * @param clientId identifies the caller being limited; must be non-null and non-blank
     * @return {@code true} if the request is allowed, {@code false} if it is being throttled
     */
    boolean allowRequest(String clientId);
}
