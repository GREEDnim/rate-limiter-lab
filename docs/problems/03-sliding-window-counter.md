# 03 — Sliding window counter

## Goal

Allow at most **N requests per client in any rolling `window`**, but with **O(1)
memory per client** instead of the sliding window log's O(N). It approximates the
exact rolling window from just two aligned-window counters. Time comes from an
injected `Clock`, and the limiter must be thread-safe.

## Why this algorithm exists

Each of the first two problems picked one side of a trade:

- **Fixed window** is cheap — one counter — but lets a client do up to **2N** across
  a boundary.
- **Sliding window log** is exact — no interval ever exceeds N — but stores **one
  timestamp per request**, which is O(N) per client.

The **sliding window counter** buys back the memory by giving up a little accuracy:
near-exact behaviour from just **two counters**. That balance is why it is a common
production default (Cloudflare uses it and reported roughly 0.003% error).

Everything below comes from a single assumption: the requests in the previous
window were spread **evenly** across it.

## The idea in one picture

Keep the count for the **current** aligned window and for the **previous** one. The
trailing window you actually care about straddles both, so combine them —
weighting the previous window by the part of it that still overlaps.

```
                        now - W                                   now
                           v                                       v
   ·≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣≣·
   |<-------------- the trailing window, length W -------------->|

   previous window (k-1)              current window (k)
   |<-------------- W -------------->|<--------------- W --------------->|
                                      ^
                                      curWindowStart

   the trailing window overlaps:
     - the last (W - elapsed) of the previous window
     - the first `elapsed`      of the current window
```

As `elapsed` grows, the previous window's contribution **decays linearly** from
full (at the boundary) to nothing (at the end of the current window).

## How the estimate is derived

This is the heart of the algorithm. Let `elapsed = now % W` be how far into the
current window we are. The trailing window is `(now - W, now]`, and it covers two
regions:

- **all of the current window so far.** Every request counted in `currentCount`
  happened at or after `curWindowStart = now - elapsed`, and `now - elapsed` is at
  or after `now - W`, so all of it sits inside the trailing window. It counts at
  **full weight**.
- **the last `W - elapsed` of the previous window.** That slice is `W - elapsed`
  wide out of the previous window's full width `W`. We don't know *when* inside the
  previous window its requests landed, so we assume they were spread evenly — and
  therefore that the fraction still inside is `(W - elapsed) / W`.

Adding the two regions gives the **estimate** — an approximation of "how many
requests happened in the last `W`":

```
estimate = currentCount + previousCount × (W − elapsed) / W        (elapsed = now % W)

allow iff estimate < N
```

Read the two ends of a window:

- **`elapsed = 0`** (just crossed into the new window): the weight is `1`, so the
  whole previous window still overlaps and its budget carries over in full.
- **`elapsed → W`** (about to leave the window): the weight goes to `0`, so the
  previous window no longer counts at all.

## State

One tiny record per client:

```
clientId ──► { windowIndex, currentCount, previousCount }
```

- `windowIndex` — which slice the counters belong to.
- `currentCount` — requests admitted so far in the current window.
- `previousCount` — requests admitted in the immediately previous window.

## Window transitions (part of the spec)

On each request, compare the stored `windowIndex` with `floor(now / W)`:

- **same window** — leave the counters alone;
- **exactly the next window** — `previousCount = currentCount`, then `currentCount = 0`;
- **two or more windows later (a gap)** — `previousCount = 0`, `currentCount = 0`
  (a window older than the previous one is forgotten);
- **never move backward** (forward-only, as in the earlier problems).

## Approach

On each call:

1. Read `now` from the clock; compute `index = now / W` and `elapsed = now % W`.
2. Shift the counters per the **window transitions** above.
3. Compute the estimate. If it is below `maxRequests`, increment `currentCount` and
   return `true`; otherwise return `false`.
4. Validate construction arguments (`maxRequests > 0`, positive `window`, non-null
   `clock`) and reject a null/blank `clientId` up front.
5. **Thread safety:** give each client's counters their own lock, and keep the
   shift, the estimate, and the increment in **one critical section** — the update
   touches *two* counters, so "atomic per client" means more than a single
   decrement.

## Why it is only an estimate

The uniform-spread assumption is the only approximation. Real traffic is not
uniform, so the counter can be slightly off in either direction:

- if the previous window's requests were bunched at its **start**, they have already
  aged out, but the estimate still credits `(W - elapsed)/W` of them — it
  **under**-admits (safer);
- if they were bunched at its **end**, fewer have aged out, and the estimate
  **over**-admits.

That is the price of O(1) state, and it is small enough in practice to be the usual
production choice.

## Trade-off

- **Approximate, not exact** — the error above.
- **O(1) time and O(1) per-client memory** — the whole point versus the log.
- The update touches two counters, so making it atomic (and later, distributed) is a
  multi-field job rather than a single increment.

## Complexity

With **N = `maxRequests`** and **C = number of distinct clients seen**:

- **Time:** O(1) per `allowRequest`.
- **Space:** O(C) — a fixed few values per client, no per-request storage.

## Where it lives

```
src/main/java/com/lld/ratelimiter/
    RateLimiter.java                           ← the shared seam
    slidingwindowcounter/
        SlidingWindowCounterRateLimiter.java   ← this problem's implementation

src/test/java/com/lld/ratelimiter/slidingwindowcounter/
    SlidingWindowCounterRateLimiterTest.java            ← behaviour
    SlidingWindowCounterRateLimiterConcurrencyTest.java ← thread safety
```

## How to run the tests

Run **just this limiter's tests** from the repo root:

```bash
mvn -Dtest='com.lld.ratelimiter.slidingwindowcounter.*Test' test
```

Other options:

```bash
# Every test in the repo
mvn test

# A single class — functional behaviour, or thread safety
mvn -Dtest=com.lld.ratelimiter.slidingwindowcounter.SlidingWindowCounterRateLimiterTest test
mvn -Dtest=com.lld.ratelimiter.slidingwindowcounter.SlidingWindowCounterRateLimiterConcurrencyTest test
```
