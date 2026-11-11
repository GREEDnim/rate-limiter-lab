# LLD practice

A Java 21 + JUnit 5 repo for **low-level design (LLD)** reps, aimed at Indian
product-company machine-coding rounds (90 minutes, live, no AI).

## The rule

**You write the code.**

Each problem ships with a problem statement and a set of **acceptance tests** —
the spec. The tests are **red** until you implement the design yourself.
Do not look up a reference solution before your attempt; comparing *after* is
fine and is where the learning happens.

Why this rule: LLD is a **skill**, not knowledge. A finished file you didn't
write cannot be defended, and you cannot reproduce it under time pressure.

## How to work a problem

1. Read `docs/problems/<n>-*.md` (the statement + requirements).
2. Run `mvn test` — watch it fail. That failure *is* the requirement list.
3. Design before you type: entities → interfaces → core flow → edge cases.
4. Implement until the tests pass.
5. Add your **own** tests for behaviour the spec didn't cover.
6. Ask for an **interviewer pass**: "why this abstraction?", "make it
   thread-safe", "now add X".

See [docs/METHOD.md](docs/METHOD.md) for the per-problem method.

## Run

```bash
mvn test
```

## Progress

See [PROGRESS.md](PROGRESS.md) for the ordered problem list and status.
