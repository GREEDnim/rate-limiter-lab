package com.lld.ratelimiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.lld.support.MutableClock;

/**
 * The thread-safety guarantee: no matter how many threads hammer the limiter at
 * once, it must never allow more than the limit within a window.
 *
 * <p>The clock never advances here, so every request lands in the same window and
 * the total number of allowed requests must be exactly the limit.
 */
class FixedWindowRateLimiterConcurrencyTest {

    @Test
    void never_allows_more_than_the_limit_under_concurrent_load() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"));
        int limit = 50;
        int threads = 8;
        int requestsPerThread = 200;

        RateLimiter limiter = new FixedWindowRateLimiter(limit, Duration.ofSeconds(1), clock);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < requestsPerThread; i++) {
                        if (limiter.allowRequest("alice")) {
                            allowed.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(finished.await(10, TimeUnit.SECONDS), "the limiter did not finish under 10s");
        pool.shutdownNow();

        assertEquals(limit, allowed.get(),
                "exactly " + limit + " requests must be allowed, no more and no less");
    }
}
