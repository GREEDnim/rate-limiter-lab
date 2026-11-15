package com.lld.ratelimiter.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.lld.support.MutableClock;

/**
 * The atomicity guarantee of the store: concurrent increments of the same key must not
 * lose an update. This is the property that makes a store usable as shared state.
 */
class InMemoryRateLimitStoreConcurrencyTest {

    private static final Instant START = Instant.parse("2024-01-01T00:00:00Z");

    /**
     * The clock is frozen and the TTL is long, so the key never expires during the test;
     * every increment must produce a distinct, consecutive value. If any update is lost,
     * the highest value seen will be below the total number of increments.
     */
    @Test
    void concurrent_increments_are_not_lost() throws Exception {
        RateLimitStore store = new InMemoryRateLimitStore(new MutableClock(START));
        Duration ttl = Duration.ofMinutes(1);
        int threads = 8;
        int perThread = 2_000;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicLong highest = new AtomicLong();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        long value = store.incrementAndGet("k", ttl);
                        highest.accumulateAndGet(value, Math::max);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "workers did not finish in time");
        pool.shutdownNow();

        long total = (long) threads * perThread;
        assertEquals(total, highest.get(),
                "every increment must be seen exactly once; the highest value must equal the total");
    }

    /** Concurrent work on different keys does not interfere. */
    @Test
    void concurrent_increments_of_different_keys_are_independent() throws Exception {
        RateLimitStore store = new InMemoryRateLimitStore(new MutableClock(START));
        Duration ttl = Duration.ofMinutes(1);
        int threads = 8;
        int perThread = 1_000;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicLong highestA = new AtomicLong();
        AtomicLong highestB = new AtomicLong();

        for (int t = 0; t < threads; t++) {
            boolean keyA = t % 2 == 0;
            String key = keyA ? "a" : "b";
            AtomicLong highest = keyA ? highestA : highestB;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        highest.accumulateAndGet(store.incrementAndGet(key, ttl), Math::max);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "workers did not finish in time");
        pool.shutdownNow();

        long perKey = (long) (threads / 2) * perThread;
        assertEquals(perKey, highestA.get(), "key a's count");
        assertEquals(perKey, highestB.get(), "key b's count");
    }
}
