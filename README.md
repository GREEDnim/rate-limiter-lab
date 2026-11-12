# Rate limiter lab

A Java 21 + JUnit 5 lab for rate limiting algorithms. Each problem is a write-up
that explains the **goal** and the **general approach** — enough to implement it
yourself — plus how to run its tests. You write the code; the tests are the spec.

## Problems

| # | Problem | Write-up |
|---|---|---|
| 01 | Fixed-window rate limiter | [docs/problems/01-fixed-window.md](docs/problems/01-fixed-window.md) |
| 02 | Sliding window log | [docs/problems/02-sliding-window-log.md](docs/problems/02-sliding-window-log.md) |

## Run the tests

```bash
mvn test
```

To run a single problem's tests, use its write-up — it lists the exact
`mvn -Dtest=...` command (for example
`mvn -Dtest=com.lld.ratelimiter.fixedwindow.FixedWindowRateLimiterTest test`).

## Layout

- `docs/problems/` — one write-up per problem (goal + approach + test
  instructions).
- `src/main/java/com/lld/ratelimiter/` — the shared `RateLimiter` seam, plus one
  package per algorithm (e.g. `fixedwindow/`).
- `src/test/java/com/lld/ratelimiter/<algorithm>/` — that algorithm's tests.
- `src/test/java/com/lld/support/MutableClock.java` — a `Clock` tests can advance.
