---
name: code
description: Implement exactly what the user asks and nothing more, without adding, modifying, or running tests. Use during development when the user owns the tests and only wants implementation changes.
---

# Implement only

Use this when the user is developing and wants **implementation changes only**.
The user is the spec; the tests are off-limits during this mode.

## Rules

- Change only the code the user asked for. Do not add speculative features,
  refactors, renames, formatting passes, or "while I was here" improvements.
- Do **not** modify, add, or delete test files, and do not weaken or skip any
  assertion.
- Do **not** run the test suite (or otherwise verify by testing) unless the user
  explicitly asks. Do not run `mvn test` to "check" unprompted.
- Do **not** read the acceptance tests to infer required behaviour — implement
  what the user says.
- If the request is ambiguous, ask one short clarifying question instead of
  guessing.
- Keep changes consistent with `AGENTS.md`.

## When to stop

Stop once the requested change is made. Summarise what you changed and which files
you touched. Do not propose or apply follow-up work unless the user asks.
