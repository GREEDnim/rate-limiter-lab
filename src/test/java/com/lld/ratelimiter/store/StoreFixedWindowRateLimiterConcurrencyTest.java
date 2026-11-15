package com.lld.ratelimiter.store;

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

import com.lld.ratelimiter.RateLimiter;
import com.lld.support.MutableClock;

/**
 * The thread-safety guarantee of the store-backed limiter: many threads hammering one
 * client through one shared store must still never admit more than the limit per window.
 */
class StoreFixedWindowRateLimiterConcurrencyTest {

    private static final Instant START = Instant.parse("2024-01-01T00:00:00Z");

    /**
     * The clock is frozen, so all requests land in one window; the shared store must admit
     * <em>exactly</em> the limit — not more (a lost update) and not less.
     */
    @Test
    void never_allows_more_than_the_limit_under_concurrent_load() throws Exception {
        MutableClock clock = new MutableClock(START);
        RateLimitStore store = new InMemoryRateLimitStore(clock);
        int limit = 50;
        int threads = 8;
        int requestsPerThread = 200;

        RateLimiter limiter = new StoreFixedWindowRateLimiter(limit, Duration.ofSeconds(1), store);
        int allowed = hammer(limiter, "alice", threads, requestsPerThread);

        assertEquals(limit, allowed,
                "exactly " + limit + " requests must be allowed, no more and no less");
    }

    /**
     * Repeats the guarantee across windows: after each round the store's clock advances a
     * full window, so the next round starts with a fresh budget — while still under load.
     */
    @Test
    void admits_exactly_the_limit_in_each_window_as_the_store_clock_advances() throws Exception {
        MutableClock clock = new MutableClock(START);
        RateLimitStore store = new InMemoryRateLimitStore(clock);
        int limit = 40;
        int threads = 8;
        int perThread = 200;
        int windows = 4;

        RateLimiter limiter = new StoreFixedWindowRateLimiter(limit, Duration.ofSeconds(1), store);

        for (int w = 0; w < windows; w++) {
            int allowed = hammer(limiter, "alice", threads, perThread);
            assertEquals(limit, allowed, "window " + w + " must admit exactly the limit");
            clock.advance(Duration.ofSeconds(1));
        }
    }

    /** Two clients hammered together through the store keep independent budgets. */
    @Test
    void different_clients_have_independent_budgets_under_load() throws Exception {
        MutableClock clock = new MutableClock(START);
        RateLimitStore store = new InMemoryRateLimitStore(clock);
        int limit = 30;
        int threads = 8;
        int perThread = 100;

        RateLimiter limiter = new StoreFixedWindowRateLimiter(limit, Duration.ofSeconds(1), store);
        AtomicInteger aliceAllowed = new AtomicInteger();
        AtomicInteger bobAllowed = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            boolean isAlice = t % 2 == 0;
            String client = isAlice ? "alice" : "bob";
            AtomicInteger counter = isAlice ? aliceAllowed : bobAllowed;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (limiter.allowRequest(client)) {
                            counter.incrementAndGet();
                        }
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

        assertEquals(limit, aliceAllowed.get(), "alice's budget must be exhausted independently");
        assertEquals(limit, bobAllowed.get(), "bob's budget must be exhausted independently");
    }

    /**
     * Fires {@code threads} threads at the same client, each attempting
     * {@code perThread} requests, released together by a latch, and returns how many were
     * admitted in total.
     */
    private static int hammer(RateLimiter limiter, String client, int threads, int perThread)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (limiter.allowRequest(client)) {
                            allowed.incrementAndGet();
                        }
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
        return allowed.get();
    }
}
