package com.lld.ratelimiter.gcra;

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
 * once, it must never admit more than the schedule allows.
 *
 * <p>These complement {@link GcraRateLimiterTest}, which is single-threaded. They add
 * the cases a frozen clock misses: advancing the schedule under load, bursting again
 * after idle, and independent clients.
 */
class GcraRateLimiterConcurrencyTest {

    private static final Instant START = Instant.parse("2024-01-01T00:00:00Z");

    private static final Duration T = Duration.ofMillis(20);       // emission interval
    private static final Duration TAU = Duration.ofMillis(980);    // burst of 50

    /**
     * The baseline guarantee. The clock is frozen, so the only headroom is the
     * tolerance; hammering from many threads must admit <em>exactly</em> the burst
     * ({@code floor(tau/T) + 1 = 50}) — not more (advancing the schedule on a reject /
     * a broken critical section) and not less (over-locking that drops valid requests).
     */
    @Test
    void never_allows_more_than_the_burst_from_idle_under_concurrent_load() throws Exception {
        MutableClock clock = new MutableClock(START);
        int burst = 50;
        int threads = 8;
        int requestsPerThread = 200;

        RateLimiter limiter = new GcraRateLimiter(T, TAU, clock);
        int allowed = hammer(limiter, "alice", threads, requestsPerThread);

        assertEquals(burst, allowed,
                "exactly " + burst + " requests must be allowed, no more and no less");
    }

    /**
     * After idling, a client falls sufficiently behind the schedule to earn a fresh
     * burst; the next round must again admit exactly the burst, under load.
     */
    @Test
    void admits_the_burst_again_after_idling_under_load() throws Exception {
        MutableClock clock = new MutableClock(START);
        int burst = 50;
        int threads = 8;
        int perThread = 200;
        int rounds = 4;

        RateLimiter limiter = new GcraRateLimiter(T, TAU, clock);

        for (int r = 0; r < rounds; r++) {
            int allowed = hammer(limiter, "alice", threads, perThread);
            assertEquals(burst, allowed, "round " + r + " must admit exactly the burst");
            clock.advance(Duration.ofSeconds(10)); // fall far behind the schedule
        }
    }

    /**
     * The hard one: the clock rolls over <em>while</em> requests are in flight, so the
     * schedule advances concurrently. The limiter must still never admit more than the
     * sustained rate plus the burst over the run.
     *
     * <p>The clock is advanced without pausing the workers, so this is inherently
     * timing-dependent; it asserts the safe upper bound plus a sanity lower bound.
     */
    @Test
    void never_exceeds_the_sustained_bound_while_the_clock_rolls_under_load() throws Exception {
        MutableClock clock = new MutableClock(START);
        int burst = 25;
        Duration tau = T.multipliedBy(burst - 1); // burst of 25
        int threads = 8;
        int steps = 10;

        RateLimiter limiter = new GcraRateLimiter(T, tau, clock);
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

        // Roll the clock one emission interval at a time while the workers keep calling.
        for (int w = 0; w < steps; w++) {
            Thread.sleep(T.toMillis());
            clock.advance(T);
        }
        running.set(false);
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "workers did not stop");

        // Over the run the schedule gained about one emission per step, on top of the
        // initial burst; allow a little slack for boundary timing.
        int maxPossible = burst + steps + 2;
        assertTrue(allowed.get() <= maxPossible,
                "admitted " + allowed.get() + " but at most " + maxPossible
                        + " is possible (burst + elapsed intervals)");
        // ...and the upper bound only means something if requests actually got through.
        assertTrue(allowed.get() >= burst,
                "expected at least " + burst + " admissions across the run, got " + allowed.get());
    }

    /**
     * Concurrency must not blur clients together: with two clients hammered at the
     * same time, each one independently gets its own burst.
     */
    @Test
    void different_clients_have_independent_schedules_under_load() throws Exception {
        MutableClock clock = new MutableClock(START);
        int burst = 50;
        int threads = 8;
        int perThread = 100;

        RateLimiter limiter = new GcraRateLimiter(T, TAU, clock);
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

        assertEquals(burst, aliceAllowed.get(), "alice's schedule must be exhausted independently");
        assertEquals(burst, bobAllowed.get(), "bob's schedule must be exhausted independently");
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
