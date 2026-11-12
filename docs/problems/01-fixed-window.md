# 01 — Fixed-window rate limiter

## Goal

Allow at most **N requests per client per time window**, and reject the rest.
Each client is limited independently. Windows are **fixed slices of time aligned
to the epoch** — not "one window since the client's first request" — and time
comes from an injected `Clock` so it can be tested. The limiter must be
thread-safe: concurrent callers can never squeeze more than N through, per client,
per window.

## Why this algorithm exists

It is the simplest limiter there is: **one integer per client**, reset on a fixed
schedule. That buys O(1) time and O(1) memory per client, and it makes atomicity
trivial — the whole decision is a single counter.

Its weakness is the **boundary burst**. Because the budget refills all at once at a
clock boundary, a client can spend its whole budget at the very end of one window
and its whole budget again right after the next begins — up to **2N in a moment**,
even though no single rolling window was ever exceeded.

```
   N = 5

   ...|------ window 0 ------|------ window 1 ------|...
             [5 used late]     [5 used early]
             └──────────── up to 10 requests in a short span ───────────┘
```

Notice the windows are **epoch-aligned**: every client's boundaries fall on the
same instants (`0`, `W`, `2W`, …). That is deliberate — shared boundaries are what
reduce each client to "one number", which is also what later makes the distributed
version a single atomic increment.

## The idea in one picture

Cut time into equal, epoch-aligned slices. A client's counter belongs to the slice
it is currently in and gets a fresh budget the moment the clock enters the next
slice.

```
             window 0            window 1            window 2
  time ──────|───────────────────|───────────────────|──────────►
             0 ms                1000 ms             2000 ms

  "alice"      [3 left]            [3 left]            [3 left]     ← refills each window
  "bob"        [1 left]            [1 left]            [1 left]     ← his own budget
```

A request at 900 ms is still in window 0, so alice's window-0 budget expires at
1000 ms — not 900 ms later. That is exactly what "fixed window" means.

## State

One tiny record per client:

```
clientId ──► { windowIndex, remaining }
```

- `windowIndex` — which slice this counter belongs to (so an old counter can be
  detected and thrown away).
- `remaining` — how many requests are left in that slice.

## Approach

**1. Find the window.** Turn the clock into an integer slice:

```text
windowIndex = clock.millis() / windowMillis      (integer division floors)
```

Because it floors, every client shares the same boundaries and they are aligned to
the epoch.

**2. Reset lazily.** There is no timer. When a request arrives, if the client's
stored `windowIndex` is *older* than the current one, refill `remaining` to
`maxRequests`. The counter "rolls over" the first time the client is seen in the
new window.

**3. Decide**, and do it atomically per client:

```
  allowRequest(clientId)
        │
        ├─ compute the current window index from the clock
        │
        ├─ find (or create) this client's counter
        │
        └─ inside that counter's own lock:
             ├─ window moved forward?  → refill the budget
             ├─ budget remaining?      → take one  → ALLOW
             └─ otherwise              → REJECT
```

**4. Atomic per client.** Only calls for the *same* client conflict, so keep the
counters in a map and let each counter be its own lock (a `synchronized` method on
the counter entry). Different clients then never block one another. The refill and
the decrement must sit in the *same* critical section, so a request can't slip
between them.

**5. Move forward only.** The refill condition is "the current window is *newer*".
Under concurrency two threads read the clock a hair apart, so one may arrive
holding a slightly older index; a forward-only check keeps that stale caller from
rewinding the counter and being handed a second budget.

**6. Guard the edges.** Reject bad construction arguments (`maxRequests > 0`,
positive `window`, non-null `clock`) and null/blank `clientId` up front. Default
the clock to the system clock in a convenience constructor, and clamp a
sub-millisecond window so you never divide by zero.

## Keep in mind

- **No background threads or timers** — the window resets on access.
- **One lock per client**, never a single global lock over the whole method.
- The map grows one entry per client id ever seen. That's fine here; in production
  you'd evict idle entries (or move the counter to Redis with a TTL).

## Complexity

With **N = `maxRequests`** and **C = number of distinct clients seen**:

- **Time:** O(1) per `allowRequest` — constant-time window arithmetic, one
  constant-time map lookup, and a single counter update.
- **Space:** O(C) — one tiny node per client (a window index and a counter), and
  no per-request storage. It grows with the number of distinct clients, not with
  request volume.

## Where it lives

```
src/main/java/com/lld/ratelimiter/
    RateLimiter.java                           ← the shared seam (every algorithm implements it)
    fixedwindow/
        FixedWindowRateLimiter.java            ← this problem's implementation

src/test/java/com/lld/ratelimiter/fixedwindow/
    FixedWindowRateLimiterTest.java            ← behaviour
    FixedWindowRateLimiterConcurrencyTest.java ← thread safety
```

One package per algorithm under `com.lld.ratelimiter`; the `RateLimiter` seam is
shared across all of them.

## How to run the tests

Run **just this limiter's tests** from the repo root. The package pattern keeps it
scoped to this limiter even once more limiters are added:

```bash
mvn -Dtest='com.lld.ratelimiter.fixedwindow.*Test' test
```

Other options:

```bash
# A single class — functional behaviour, or thread safety
mvn -Dtest=com.lld.ratelimiter.fixedwindow.FixedWindowRateLimiterTest test
mvn -Dtest=com.lld.ratelimiter.fixedwindow.FixedWindowRateLimiterConcurrencyTest test
```