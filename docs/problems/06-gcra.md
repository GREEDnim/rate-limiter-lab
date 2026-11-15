# 06 — GCRA (generic cell rate algorithm)

## Goal

Give a client a **sustained rate with a bounded burst** — the same guarantee as the
token bucket — but using a **single timestamp** of state per client instead of a token
count. Time comes from an injected `Clock`, and the limiter must be thread-safe.

## Why this algorithm exists

The token bucket keeps two values per client (`tokens` and `lastRefill`) and does
fractional arithmetic on every call. GCRA throws the bucket away and keeps one number:
**the time the client is next expected to send**. Two payoffs:

- **one value of state** — cheap, and a single compare-and-set away from being
  distributed;
- it tells you directly **when** the next request may go, which is exactly the
  `Retry-After` you would send a client.

It is what Redis's `CL.THROTTLE` uses and what Stripe and Let's Encrypt run. The name
comes from ATM networks, where "cells" were fixed-size packets.

## How it works

Forget buckets. GCRA tracks one thing per client: `TAT`, the **theoretical arrival
time** — when that client is next *due* to send. Build it up in three steps.

**1. A metronome.** Suppose you want exactly one request every `T` — the *emission
interval* — and no more. Keep the time the next request is due. When a request
arrives: if it is due, allow it and set the next due time to `now + T`; otherwise
reject. That alone paces requests at least `T` apart.

```
  due times:   TAT          TAT+T        TAT+2T
               ●─────────────●─────────────●        ← one slot every T
```

**2. Allow bursts.** Real clients are not metronomes — they may fire several requests
at once. The moment you allow a request, its *next* due time is `T` in the **future**
(`TAT = now + T`), so a client that fires again immediately is arriving **before** that
due time — it is running *ahead* of the schedule. GCRA permits exactly that, up to `τ`
(the *burst tolerance*) early: a request is rejected only when it is **more than `τ`**
ahead, i.e. `now < TAT − τ`.

```
  a request is accepted from here onward:
                     ▲
  ... ───────────────┼────────●─────────●─────────
                  TAT − τ   TAT       TAT+T
                  |<-- τ -->|
```

With `τ = 0` you are back to the strict metronome; a larger `τ` allows a bigger burst.

**3. Handle idleness.** Allowing a request pushes the due time forward by `T`. But if
the client sends *late* — after it was due — you must start the next interval from when
it actually sent, not from the old due time, or a quiet client would bank an unbounded
debt of "owed" requests. That is the `max(now, TAT)`: use whichever is later.

### Why this is "rate + burst"

- `T` sets the **pace**. The due time moves forward one slot per `T`, so the sustained
  rate is `1 / T` per unit time.
- `τ` is **how far ahead of the pace a client may run** — which is exactly its allowed
  burst.

### How big is the burst?

From idle, a client firing as fast as it can gets its first request for free
(`TAT = now`), and every allowed request pushes the due time out by `T`. So after `k`
allowed requests the due time is `now + k·T`, and the next one is still accepted while
it is within `τ` of that:

```
  allowed while   now ≥ (now + k·T) − τ   ⇒   k·T ≤ τ   ⇒   k ≤ τ / T
```

The largest such whole number is `floor(τ / T)`, so the number of requests that fit is
`floor(τ / T) + 1` — **one for free, plus one for each whole `T` of tolerance**.

To allow a burst of `B`, set `τ = (B − 1) × T`. And the earliest the next request may
go is `TAT − τ`, which is your `Retry-After` for free.

## The exact rule (part of the spec)

Two parameters and one value of state per client:

- `T` — the **emission interval** (`> 0`): time between allowed requests at the
  sustained rate.
- `τ` — the **burst tolerance** (`≥ 0`): how far ahead of schedule a client may run.
- `TAT` — the theoretical arrival time, one per client.

```text
on allowRequest at `now`:
    if TAT is unset:   TAT = now                 (the first request sets the schedule)
    if now < TAT − τ:  REJECT                     (too far ahead of schedule)
    else:              ALLOW;  TAT = max(now, TAT) + T
```

## Trade-off

- Same **sustained rate + burst** behaviour as the token bucket, but with one value of
  state and a direct "next allowed" answer.
- The knobs are a little less intuitive: you think in `T` and `τ` rather than "tokens"
  and "refill". They convert as `τ = (B − 1) × T` for a burst of `B`.
- **O(1) time, O(1) per-client memory** — literally one `long` per client.

## Complexity

With **C = number of distinct clients seen**:

- **Time:** O(1) per `allowRequest`.
- **Space:** O(C) — a single timestamp per client.

## Where it lives

```
src/main/java/com/lld/ratelimiter/
    RateLimiter.java             ← the shared seam
    gcra/
        GcraRateLimiter.java     ← this problem's implementation

src/test/java/com/lld/ratelimiter/gcra/
    GcraRateLimiterTest.java            ← behaviour
    GcraRateLimiterConcurrencyTest.java ← thread safety
```

## How to run the tests

Run **just this limiter's tests** from the repo root:

```bash
mvn -Dtest='com.lld.ratelimiter.gcra.*Test' test
```

Other options:

```bash
# Every test in the repo
mvn test

# A single class — functional behaviour, or thread safety
mvn -Dtest=com.lld.ratelimiter.gcra.GcraRateLimiterTest test
mvn -Dtest=com.lld.ratelimiter.gcra.GcraRateLimiterConcurrencyTest test
```
