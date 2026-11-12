package com.lld.ratelimiter.slidingwindowlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.lld.ratelimiter.RateLimiter;
import com.lld.support.MutableClock;

/**
 * The thread-safety guarantee: no matter how many threads hammer the limiter at
 * once, it must never allow more than the limit within a window.
 *
 * <p>These complement {@link SlidingWindowLogRateLimiterTest}, which is
 * single-threaded. They add the cases a frozen clock misses: ageing timestamps out
 * under load, several windows in sequence, and independent clients.
 */
class SlidingWindowLogRateLimiterConcurrencyTest {

    private static final Instant START = Instant.parse("2024-01-01T00:00:00Z");

    /**
     * The baseline guarantee. The clock is frozen, so every request lands in one
     * window; hammering from many threads must still admit <em>exactly</em> the
     * limit — not more (lost updates / a broken critical section) and not less
     * (over-locking that drops valid requests).
     */
    @Test
    void never_allows_more_than_the_limit_under_concurrent_load() throws Exception {
        MutableClock clock = new MutableClock(START);
        int limit = 50;
        int threads = 8;
        int requestsPerThread = 200;

        RateLimiter limiter = new SlidingWindowLogRateLimiter(limit, Duration.ofSeconds(1), clock);
        int allowed = hammer(limiter, "alice", threads, requestsPerThread);

        assertEquals(limit, allowed,
                "exactly " + limit + " requests must be allowed, no more and no less");
    }

    /**
     * Repeats the "exactly the limit" guarantee across windows in sequence. After
     * each round the clock advances a full window, so every recorded timestamp ages
     * out and the next round starts with a full budget — while still under load.
     */
    @Test
    void admits_exactly_the_limit_in_each_round_as_the_window_advances() throws Exception {
        MutableClock clock = new MutableClock(START);
        int limit = 40;
        int threads = 8;
        int perThread = 200;
        int windows = 4;

        RateLimiter limiter = new SlidingWindowLogRateLimiter(limit, Duration.ofSeconds(1), clock);

        for (int w = 0; w < windows; w++) {
            int allowed = hammer(limiter, "alice", threads, perThread);
            assertEquals(limit, allowed, "round " + w + " must admit exactly the limit");
            clock.advance(Duration.ofSeconds(1)); // every recorded timestamp ages out
        }
    }

    /**
     * The hard one: the clock rolls over <em>while</em> requests are in flight, so
     * timestamps age out concurrently with new requests. The limiter must still
     * never admit more than the limit in any window of that length.
     *
     * <p>The clock is advanced without pausing the workers, so this is inherently
     * timing-dependent; it asserts the safe upper bound (limit per window across the
     * windows that elapsed) rather than an exact count.
     */
    @Test
    void never_exceeds_the_limit_in_any_window_while_the_clock_rolls_under_load() throws Exception {
        MutableClock clock = new MutableClock(START);
        int limit = 25;
        Duration window = Duration.ofMillis(20);
        int threads = 8;
        int windows = 10;

        RateLimiter limiter = new SlidingWindowLogRateLimiter(limit, window, clock);
        AtomicInteger allowed = new AtomicInteger();
        AtomicBoolean running = new AtomicBoolean(true);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (running.get()) {
                        if (limiter.allowRequest("alice")) {
                            allowed.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();

        // Roll the clock while the workers keep calling, so ageing and requests race.
        for (int w = 0; w < windows; w++) {
            Thread.sleep(window.toMillis());
            clock.advance(window);
        }
        running.set(false);
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "workers did not stop");

        // The whole run spans about `windows` window-lengths, so at most
        // `windows + 1` non-overlapping windows fit, each admitting at most limit.
        int maxPossible = limit * (windows + 1);
        assertTrue(allowed.get() <= maxPossible,
                "admitted " + allowed.get() + " but at most " + maxPossible
                        + " is possible across " + (windows + 1) + " windows");
        // ...and, for the upper bound to mean anything, it must actually have let
        // requests through (catches a limiter that rejects everything).
        assertTrue(allowed.get() >= limit,
                "expected at least " + limit + " admissions across the run, got " + allowed.get());
    }

    /**
     * Concurrency must not blur clients together: with two clients hammered at the
     * same time, each one independently gets exactly its own limit.
     */
    @Test
    void different_clients_have_independent_budgets_under_load() throws Exception {
        MutableClock clock = new MutableClock(START);
        int limit = 30;
        int threads = 8;
        int perThread = 100;

        RateLimiter limiter = new SlidingWindowLogRateLimiter(limit, Duration.ofSeconds(1), clock);
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
     * {@code perThread} requests, released together by a latch, and returns how many
     * were admitted in total.
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
