# 02 — Sliding window log

## Goal

Allow at most **N requests per client in any rolling interval of `window`
length** — not per epoch-aligned window. The limit is **exact**: no interval of
`window` ever admits more than N, so there is no boundary burst. Time comes from
an injected `Clock`, and the limiter must be thread-safe.

## Why not just fixed window

Fixed windows reset on a clock boundary, so a client can spend its whole budget at
the end of one window and the whole budget again at the start of the next — up to
**2N in a very short span**.

```
  fixed window (N = 5)

  ...|---- window 0 ----|---- window 1 ----|...
        [5 used here]     [5 used here]
        └──────────── 10 requests near one boundary ────────────┘

  sliding window (N = 5)
  every request looks back exactly `window`; never more than 5 inside the box
        |<-------- window -------->|
                        [ ... at most 5 ... ]
```

The cost of that exactness is **O(N) memory per client** — one stored timestamp per
allowed request. The sliding window counter next keeps most of the smoothness with
just two integers.

## The idea

Keep the **timestamp of every allowed request** for a client. A new request is
allowed only if fewer than N timestamps fall inside the trailing `window`.
Timestamps that have aged out are dropped, which frees budget gradually as
requests expire.

```
  client "alice" (N = 3, window = 1s), now = 1250 ms

  recorded: [150] [700] [1180]          now ─┐
        evicted: 150, 700 (older than 250ms) │  inside (now-window, now]: 1180
  remaining: [1180]  → 1 < 3 → ALLOW, record 1250
```

## State

Per client: an ordered collection (a deque or a list used as a queue) of the
timestamps of allowed requests. It never holds more than N entries.

```
clientId ──► [ t1, t2, ... ]     (timestamps of allowed requests, oldest first)
```

## Boundary rule (part of the spec)

The window is the **half-open** interval `(now - window, now]`:

- a request made at `now` counts immediately;
- a timestamp exactly `window` old has **expired** — a request made at `t` is
  allowed again at exactly `t + window`.

Half-open keeps the arithmetic clean: a request becomes reusable at exactly
`t + window`, and several calls in the same millisecond are all charged to that one
instant.

## Approach

1. Read `now` from the injected clock.
2. **Evict** every recorded timestamp `t` with `now - t >= window`.
3. If the remaining count is `< maxRequests`, **record `now`** and return `true`;
   otherwise return `false`.
4. **Do not record rejected requests** — they must not consume or extend the
   window.
5. Validate construction arguments (`maxRequests > 0`, positive `window`, non-null
   `clock`) and reject a null/blank `clientId` up front.
6. **Thread safety:** only requests for the same client conflict, so give each
   client's log its own lock (the same lock striping as problem 01). Evict, count,
   and record inside one critical section.

## Trade-off

Exact, but **O(N) memory per client** — one timestamp per allowed request. That
cost is what motivates the sliding window **counter** next, which approximates the
same behaviour with two integers.

## Keep in mind

- The window is **rolling** (anchored to `now`), not epoch-aligned.
- **Time only moves forward.** For a given client, calls arrive with
  non-decreasing timestamps (equal instants are normal — several calls can land in
  one millisecond). You may rely on this; the tests do. In production a wall clock
  can step backward, so decide how you'd handle that.
- Rejected requests are not stored.
- One lock per client; the eviction and the decision must be atomic together.
- The per-client log is capped at N entries, but the **number of clients** is not
  — decide how you'd bound that in production.

## Complexity

With **N = `maxRequests`** and **C = number of distinct clients seen**:

- **Time:** O(1) amortised per `allowRequest`. Each timestamp is appended once and
  evicted at most once over its lifetime, so the eviction cost is spread across
  calls. A single call may evict up to N entries at once (a worst case of O(N)),
  but that is amortised away.
- **Space:** O(C·N) worst case — one timestamp per allowed request, capped at N
  per client. The O(N) per client is the price of being exact, and it is exactly
  the cost the sliding window counter trades away next.

## Where it lives

```
src/main/java/com/lld/ratelimiter/
    RateLimiter.java                       ← the shared seam
    slidingwindowlog/
        SlidingWindowLogRateLimiter.java   ← this problem's implementation

src/test/java/com/lld/ratelimiter/slidingwindowlog/
    SlidingWindowLogRateLimiterTest.java            ← behaviour
    SlidingWindowLogRateLimiterConcurrencyTest.java ← thread safety
```

## How to run the tests

Run **just this limiter's tests** from the repo root:

```bash
mvn -Dtest='com.lld.ratelimiter.slidingwindowlog.*Test' test
```

Other options:

```bash
# Every test in the repo
mvn test

# A single class — functional behaviour, or thread safety
mvn -Dtest=com.lld.ratelimiter.slidingwindowlog.SlidingWindowLogRateLimiterTest test
mvn -Dtest=com.lld.ratelimiter.slidingwindowlog.SlidingWindowLogRateLimiterConcurrencyTest test
```
