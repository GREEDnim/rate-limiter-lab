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

A write-up is a **guide that teaches the algorithm, not a spec sheet**. It must
answer **how it works** and **why** — not just state the rule. Write for someone
learning the algorithm, in roughly this order:

- **Goal** — what the limiter guarantees, in plain language.
- **Why this algorithm exists** — the gap it fills versus the algorithms before it.
- **How it works** — the important part: the mental model, how the state evolves,
  and *why* the steps produce the guarantee. Derive the key properties (e.g. why the
  burst is `capacity`, or `floor(τ/T) + 1`) instead of asserting them. Diagrams and
  worked intuition are welcome.
- **The exact rule (spec)** — the precise formula / state / parameters, kept
  separate from the explanation.
- **Trade-off**, **Complexity**, **Where it lives**, **How to run the tests**.

Rules:

- Do **not** include the solution code; the learner writes it. A pseudocode snippet
  that states the rule is fine, but it does not replace the explanation.
- Prefer prose and derivation over terse bullet lists of facts. If a section is a
  list of rules with no reasoning, it is a spec sheet — rewrite it.
- Lead the test section with a scoped
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

## Commits

- **One commit per rate limiter.** A limiter's implementation, its tests, its
  write-up, and its row in the README problem list go in a single commit. Never mix
  two limiters in one commit.
- Repo-wide changes (this file, the README layout) may stand alone.

## Skills

- `code` — implement exactly what the user asks and nothing else, without
  touching or running tests. Load it with `@code`.
