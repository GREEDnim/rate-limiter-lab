# Project instructions

A Java 21 + JUnit 5 lab for **rate limiting algorithms**. Each rate limiter is a
problem: a write-up, an implementation, and acceptance tests. The learner writes
the implementation; the tests are the spec.

## Layout

- One package per algorithm: `com.lld.ratelimiter.<algorithm>` under
  `src/main/java/com/lld/ratelimiter/`.
- The shared seam `com.lld.ratelimiter.RateLimiter` is implemented by every
  algorithm — do not duplicate or change it per algorithm.
- Tests mirror the package under `src/test/java/com/lld/ratelimiter/<algorithm>/`.
- Shared test support lives in `src/test/java/com/lld/support/` (e.g.
  `MutableClock`).
- One write-up per problem at `docs/problems/0N-<algorithm>.md`; keep the problem
  list in `README.md` in sync.

## Writing a problem write-up

`docs/problems/0N-<algorithm>.md` has, in order: **Goal** (what and why), the
**general approach** (concepts, diagrams welcome — do **not** include the solution
code; the learner writes it), and **how to run the tests**. Lead with a scoped
`mvn -Dtest='com.lld.ratelimiter.<algorithm>.*Test' test` command so it keeps
working once more algorithms exist.

## Tests

- Two test classes per algorithm: `<Algorithm>RateLimiterTest` (functional) and
  `<Algorithm>RateLimiterConcurrencyTest` (thread safety).
- Control time with `MutableClock`; never use `Thread.sleep` for time-based
  behaviour, except in a rollover stress test that deliberately races the clock.
- Give every `@Test` a comment saying what it verifies and the failure mode it
  guards against.
- The tests are the spec. Never weaken, delete, or skip an assertion to make an
  implementation pass; if a test looks wrong, raise it instead of papering over it.

## Build and commands

- JDK 21, Maven, JUnit 5.
- All tests: `mvn test`
- One algorithm's tests: `mvn -Dtest='com.lld.ratelimiter.<algorithm>.*Test' test`

## Code conventions

- Java: 4-space indent, `com.lld` import grouping, no wildcard imports.
- Validate constructor arguments up front. Reject a null/blank client id with
  `IllegalArgumentException` and null objects with `NullPointerException`, as the
  spec states.
- Never call `System.currentTimeMillis()` — use the injected `Clock`.
- Keep algorithms independent; prefer the shared `RateLimiter` seam over
  inheritance.

## Skills

- `code` — implement exactly what the user asks and nothing else, without
  touching or running tests. Load it with `@code`.
