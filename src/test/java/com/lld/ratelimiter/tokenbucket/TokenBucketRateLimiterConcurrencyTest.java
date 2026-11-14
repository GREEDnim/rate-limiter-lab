package com.lld.ratelimiter.tokenbucket;

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
 * once, it must never spend more tokens than the bucket actually holds.
 *
 * <p>These complement {@link TokenBucketRateLimiterTest}, which is single-threaded.
 * They add the cases a frozen clock misses: refilling under load, spending a full
 * bucket after a refill, and independent clients.
 */
class TokenBucketRateLimiterConcurrencyTest {

    private static final Instant START = Instant.parse("2024-01-01T00:00:00Z");

    /**
     * The baseline guarantee. The clock is frozen, so there is no refill; hammering a
     * full bucket from many threads must admit <em>exactly</em> the capacity — not
     * more (double-spending the last token / a broken critical section) and not less
     * (over-locking that drops valid requests).
     */
    @Test
    void never_allows_more_than_capacity_under_concurrent_load() throws Exception {
        MutableClock clock = new MutableClock(START);
        int capacity = 50;
        int threads = 8;
        int requestsPerThread = 200;

        RateLimiter limiter = new TokenBucketRateLimiter(capacity, capacity, Duration.ofSeconds(1), clock);
        int allowed = hammer(limiter, "alice", threads, requestsPerThread);

        assertEquals(capacity, allowed,
                "exactly " + capacity + " requests must be allowed, no more and no less");
    }

    /**
     * Repeats the "exactly capacity" guarantee across rounds. After each round the
     * clock advances a full refill period, so the bucket is full again, and the next
     * round must again admit exactly the capacity — while still under load.
     */
    @Test
    void admits_exactly_capacity_in_each_round_after_a_full_refill() throws Exception {
        MutableClock clock = new MutableClock(START);
        int capacity = 40;
        int threads = 8;
        int perThread = 200;
        int rounds = 4;

        RateLimiter limiter = new TokenBucketRateLimiter(capacity, capacity, Duration.ofSeconds(1), clock);

        for (int r = 0; r < rounds; r++) {
            int allowed = hammer(limiter, "alice", threads, perThread);
            assertEquals(capacity, allowed, "round " + r + " must admit exactly the capacity");
            clock.advance(Duration.ofSeconds(1)); // refill the bucket to full
        }
    }

    /**
     * The hard one: the clock rolls over <em>while</em> requests are in flight, so the
     * bucket refills concurrently with spending. The limiter must still never spend
     * more than the tokens that were actually produced (the initial bucket plus the
     * refill over the run).
     *
     * <p>The clock is advanced without pausing the workers, so this is inherently
     * timing-dependent; it asserts the safe upper bound plus a sanity lower bound.
     */
    @Test
    void never_spends_more_than_tokens_produced_while_the_clock_rolls_under_load() throws Exception {
        MutableClock clock = new MutableClock(START);
        int capacity = 25;
        Duration refillPeriod = Duration.ofMillis(20);
        int threads = 8;
        int windows = 10;

        RateLimiter limiter = new TokenBucketRateLimiter(capacity, capacity, refillPeriod, clock);
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

        // Roll the clock while the workers keep calling, so the refill races the spend.
        for (int w = 0; w < windows; w++) {
            Thread.sleep(refillPeriod.toMillis());
            clock.advance(refillPeriod);
        }
        running.set(false);
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "workers did not stop");

        // Tokens produced = the initial full bucket + one capacity for each period that
        // elapsed => at most capacity * (windows + 1).
        int maxPossible = capacity * (windows + 1);
        assertTrue(allowed.get() <= maxPossible,
                "spent " + allowed.get() + " but at most " + maxPossible + " tokens were produced");
        // ...and the upper bound only means something if tokens were actually spent.
        assertTrue(allowed.get() >= capacity,
                "expected at least " + capacity + " admissions across the run, got " + allowed.get());
    }

    /**
     * Concurrency must not blur clients together: with two clients hammered at the
     * same time, each one independently gets exactly its own capacity.
     */
    @Test
    void different_clients_have_independent_buckets_under_load() throws Exception {
        MutableClock clock = new MutableClock(START);
        int capacity = 30;
        int threads = 8;
        int perThread = 100;

        RateLimiter limiter = new TokenBucketRateLimiter(capacity, capacity, Duration.ofSeconds(1), clock);
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

        assertEquals(capacity, aliceAllowed.get(), "alice's bucket must be exhausted independently");
        assertEquals(capacity, bobAllowed.get(), "bob's bucket must be exhausted independently");
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
