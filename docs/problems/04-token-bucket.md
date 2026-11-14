# 04 — Token bucket

## Goal

Allow a client a **sustained rate** of `refillTokens` per `refillPeriod`, with
**bursts** up to `capacity`. Each allowed request spends one token from a bucket
that refills continuously. Time comes from an injected `Clock`, and the limiter must
be thread-safe.

Unlike the window algorithms, "N per window" is split into two independent knobs:
a **rate** (how fast the bucket refills) and a **burst** (how big the bucket is).

## Why this algorithm exists

The window family ties the two things together: a limit of "N per window" is both
the sustained rate *and* the maximum burst, and it resets abruptly at a boundary.
Token bucket separates them:

- **`refillTokens / refillPeriod`** is the *average* rate you tolerate;
- **`capacity`** is the *largest burst* you tolerate.

So you can say "10 requests/second, but let a client burst up to 50 after being
quiet" — which is impossible to express with a single window limit. This is why most
API gateways (AWS, Stripe, and friends) are built on it.

## The idea in one picture

A bucket holds up to `capacity` tokens and is refilled at a steady rate. A request
takes one token if one is available; otherwise it is rejected. The bucket level
rises while the client is quiet (up to the cap) and falls as it spends.

```
  tokens
    C ┤●●●●●╲                 ╱╲
      │      ╲               ╱  ╲
      │       ╲             ╱    ╲
    0 ┤        ╲___________╱      ╲______
      └──────────────────────────────────────────► time
           burst      idle: refill     burst
```

The two knobs do different jobs: **`capacity`** caps the height of the burst,
**`refillTokens / refillPeriod`** sets the slope of the refill.

## State

One small record per client:

```
clientId ──► { tokens, lastRefillMillis }
```

- `tokens` — the current bucket level (kept as a fraction, or scaled to an integer,
  so a slow refill still accrues).
- `lastRefillMillis` — when we last topped the bucket up.

## How the refill works (part of the spec)

There is **no timer thread**. On each request, top the bucket up for the time that
has passed, then spend:

```
now        = clock.millis()
tokens     = min(capacity, tokens + (now - lastRefill) × refillTokens / refillPeriod)
lastRefill = now

if tokens ≥ 1:  tokens -= 1  →  ALLOW
else:                            REJECT
```

Two things to notice:

- **Refill is continuous, not tick-based.** Half a `refillPeriod` buys half a
  token; tokens accrue as a fraction so the limiter stays smooth. Keeping the level
  as a `double` (or a scaled integer to avoid drift) is what makes that work.
- **A brand-new client starts with a full bucket** (`capacity` tokens), so its
  first burst is allowed.

## Why it handles bursts (the point of the algorithm)

`capacity` is *credit a client can bank by being quiet*. Spend everything and you
are down to the refill rate; wait, and the bucket fills back to `capacity`, so the
next burst is allowed again. The **rate** never lets sustained traffic exceed the
sustained limit, while the **cap** bounds a single burst.

## Approach

1. Read `now` from the clock.
2. Top up `tokens` by the elapsed time, clamped to `capacity`; record `lastRefill = now`.
3. If `tokens ≥ 1`, decrement and return `true`; otherwise return `false`.
4. Validate construction arguments (`capacity > 0`, `refillTokens > 0`, positive
   `refillPeriod`, non-null `clock`) and reject a null/blank `clientId` up front.
5. **Thread safety:** give each client's bucket its own lock and keep the top-up and
   the spend in **one critical section** — the decision reads and writes
   `tokens` and `lastRefill`, so a split would double-spend.

## Trade-off

- **Bursts are allowed by design** — up to `capacity`. If you need a hard ceiling
  with no burst, a counter is the better tool.
- **O(1) time and O(1) per-client memory** — two fields.
- The refill is a **read-modify-write**, which is the part that gets interesting once
  the bucket lives in a shared store.

## Complexity

With **C = number of distinct clients seen**:

- **Time:** O(1) per `allowRequest`.
- **Space:** O(C) — a couple of fields per client, no per-request storage.

## Where it lives

```
src/main/java/com/lld/ratelimiter/
    RateLimiter.java                  ← the shared seam
    tokenbucket/
        TokenBucketRateLimiter.java   ← this problem's implementation

src/test/java/com/lld/ratelimiter/tokenbucket/
    TokenBucketRateLimiterTest.java            ← behaviour
    TokenBucketRateLimiterConcurrencyTest.java ← thread safety
```

## How to run the tests

Run **just this limiter's tests** from the repo root:

```bash
mvn -Dtest='com.lld.ratelimiter.tokenbucket.*Test' test
```

Other options:

```bash
# Every test in the repo
mvn test

# A single class — functional behaviour, or thread safety
mvn -Dtest=com.lld.ratelimiter.tokenbucket.TokenBucketRateLimiterTest test
mvn -Dtest=com.lld.ratelimiter.tokenbucket.TokenBucketRateLimiterConcurrencyTest test
```
