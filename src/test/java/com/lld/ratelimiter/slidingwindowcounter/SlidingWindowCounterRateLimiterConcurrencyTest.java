package com.lld.ratelimiter.slidingwindowcounter;

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
 * <p>These complement {@link SlidingWindowCounterRateLimiterTest}, which is
 * single-threaded. They add the cases a frozen clock misses: shifting the counters
 * forward under load, gaps between windows, and independent clients.
 */
class SlidingWindowCounterRateLimiterConcurrencyTest {

    private static final Instant START = Instant.parse("2024-01-01T00:00:00Z");

    /**
     * The baseline guarantee. The clock is frozen (one window, empty previous
     * window), so hammering from many threads must admit <em>exactly</em> the limit —
     * not more (lost updates / a broken critical section) and not less (over-locking
     * that drops valid requests).
     */
    @Test
    void never_allows_more_than_the_limit_under_concurrent_load() throws Exception {
        MutableClock clock = new MutableClock(START);
        int limit = 50;
        int threads = 8;
        int requestsPerThread = 200;

        RateLimiter limiter = new SlidingWindowCounterRateLimiter(limit, Duration.ofSeconds(1), clock);
        int allowed = hammer(limiter, "alice", threads, requestsPerThread);

        assertEquals(limit, allowed,
                "exactly " + limit + " requests must be allowed, no more and no less");
    }

    /**
     * Repeats the "exactly the limit" guarantee across rounds. After each round the
     * clock jumps two windows, which drops the previous counter entirely, so the next
     * round starts with a full budget — while still under concurrent load.
     */
    @Test
    void admits_exactly_the_limit_in_each_round_after_a_two_window_gap() throws Exception {
        MutableClock clock = new MutableClock(START);
        int limit = 40;
        int threads = 8;
        int perThread = 200;
        int rounds = 4;
        Duration window = Duration.ofSeconds(1);

        RateLimiter limiter = new SlidingWindowCounterRateLimiter(limit, window, clock);

        for (int r = 0; r < rounds; r++) {
            int allowed = hammer(limiter, "alice", threads, perThread);
            assertEquals(limit, allowed, "round " + r + " must admit exactly the limit");
            clock.advance(window.multipliedBy(2)); // gap -> previous counter dropped
        }
    }

    /**
     * The hard one: the clock rolls over <em>while</em> requests are in flight, so the
     * window counters shift concurrently with new requests. The limiter must still
     * never admit more than the limit in any window.
     *
     * <p>The clock is advanced without pausing the workers, so this is inherently
     * timing-dependent; it asserts the safe upper bound (limit per window across the
     * windows that elapsed) plus a sanity lower bound.
     */
    @Test
    void never_exceeds_the_limit_in_any_window_while_the_clock_rolls_under_load() throws Exception {
        MutableClock clock = new MutableClock(START);
        int limit = 25;
        Duration window = Duration.ofMillis(20);
        int threads = 8;
        int windows = 10;

        RateLimiter limiter = new SlidingWindowCounterRateLimiter(limit, window, clock);
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

        // Roll the clock while the workers keep calling, so the counters shift under load.
        for (int w = 0; w < windows; w++) {
            Thread.sleep(window.toMillis());
            clock.advance(window);
        }
        running.set(false);
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "workers did not stop");

        // The run spans about `windows` window-lengths, so at most `windows + 1`
        // non-overlapping windows fit, each admitting at most limit.
        int maxPossible = limit * (windows + 1);
        assertTrue(allowed.get() <= maxPossible,
                "admitted " + allowed.get() + " but at most " + maxPossible
                        + " is possible across " + (windows + 1) + " windows");
        // ...and the upper bound only means something if requests actually got through.
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

        RateLimiter limiter = new SlidingWindowCounterRateLimiter(limit, Duration.ofSeconds(1), clock);
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
