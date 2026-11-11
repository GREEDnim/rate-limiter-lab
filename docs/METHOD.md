# Per-problem method

Do this every time, even when it feels slow. The repetition *is* the point.

1. **Restate the problem** in one sentence, out loud or in a comment.
2. **Ask clarifying questions** (what you'd ask the interviewer): scale,
   concurrency, persistence, what is out of scope.
3. **List the entities** and their relationships. Sketch a class diagram.
4. **Define the interfaces** first — the seams. Implementation comes after.
5. **Implement the core flow** so the happy path works.
6. **Handle edge cases**: null/blank input, empty collections, boundaries,
   invalid arguments.
7. **Make it thread-safe** if two callers could touch it at once. Say *which
   state* needs protection and *why*.
8. **Write tests** for behaviour the spec didn't cover.
9. **Extension check** — the interviewer's follow-up: "how would you add X?"
   If adding X means rewriting, your abstraction was wrong.
10. **Notes** — write one paragraph: what you'd improve, what you'd ask for
    more time on. This is what you'd say at the end of the round.

## Time-boxes (shrink them over the weeks)

| Phase | First few problems | Interview pace |
|---|---|---|
| Clarify + design | 30 min | 10 min |
| Implement core | 90 min | 40 min |
| Edge + concurrency + tests | 45 min | 30 min |
| Wrap-up / extensions | 15 min | 10 min |
