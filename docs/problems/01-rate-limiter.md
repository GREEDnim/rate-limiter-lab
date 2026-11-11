# 01 — Fixed-window rate limiter

## Context

Every public API needs a rate limiter: allow N requests per client per window,
reject the rest. You built one in the `api-gateway` of `linkedin-system` — this
problem is the same idea, designed properly, from scratch.

## Functional requirements

Implement `FixedWindowRateLimiter` so that, per `clientId`:

- The first `maxRequests` calls to `allowRequest` **within one window** return
  `true`.
- Further calls **within the same window** return `false`.
- When the window rolls over, the client gets a fresh budget of `maxRequests`.
- Different clients have **independent** budgets.

## Behaviour details (these are part of the spec)

- **Windows are fixed intervals aligned to the epoch**: the window index is
  `floor(nowMillis / windowMillis)`. A request at 900 ms in a 1 s window still
  expires at 1000 ms — *not* one second after the request. (This is the classic
  "fixed window" definition, and its boundary burst is a known weakness.)
- **Time comes from the injected `Clock`**, never `System.currentTimeMillis()`
  directly. That is what makes this testable. Use the `MutableClock` in the test
  sources to advance time deterministically.
- **Validation**: `maxRequests > 0`; `window` non-null and strictly positive;
  `clock` non-null. Violations throw `IllegalArgumentException` (or
  `NullPointerException` for null arguments).
- **`allowRequest(null)` or a blank `clientId`** throws
  `IllegalArgumentException`.

## Non-functional requirements

- **Thread-safe.** Many threads call `allowRequest` on the same instance, for
  the same client, at the same time. The limiter must never allow more than
  `maxRequests` in a window.
- Prefer O(1) per call. (You don't need to evict inactive clients for this
  exercise, but *say* what you'd do about unbounded memory in the review.)

## The spec is the tests

Run `mvn test`. Everything red is a requirement:

- `FixedWindowRateLimiterTest` — functional behaviour + validation + boundary.
- `FixedWindowRateLimiterConcurrencyTest` — the thread-safety guarantee.

Do not edit the tests to make them pass. If a test seems wrong, say so and
explain why before changing it.

## Extensions (interviewer follow-ups)

1. Refactor so the algorithm is **pluggable** (Strategy): token bucket, sliding
   window log, sliding window counter. This is the real LLD point of the
   problem.
2. Make the client store bounded (evict idle clients).
3. What changes if the limiter must be shared across processes? (Hint: Redis +
   atomic ops — the `INCR`/`EXPIRE` problem you already hit.)

## What you should be able to explain afterwards

- Why fixed window can allow a 2× burst at a boundary.
- Why the `Clock` is injected.
- Which state is shared and how you protected it.
