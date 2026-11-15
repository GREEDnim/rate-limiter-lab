# 05 — Leaky bucket (note)

A short note, not a coded problem — the reason is at the bottom. First, what it is.

## The picture

A real bucket with a **hole in the bottom**:

```
   request  ──►   ╔═══════════════╗
   (water)        ║     level     ║  capacity C (overflow → reject)
                  ╚═══════╤═══════╝
                          │  leak at a constant rate R
                          ▼
```

- **A request = one litre poured in.**
- **The leak = the throughput you want to sustain** — water always leaves at `R`.
- **Overflow = the request is rejected.**

## A concrete run

Bucket size 10, leak 1 per second:

```
 t = 0    client pours 10 requests at once
          → level 0 → 10                 (all accepted; that's the burst)
 t = 0    an 11th request
          → would be 11 > 10 → OVERFLOW   (rejected)
 t = 1s   one litre leaked out; level 10 → 9
 t = 1s   one more request fits           (accepted; level back to 10)
 t = 2s   leaked again → one more accepted
```

The **outflow is always exactly `R`** (1/s here), and the **bucket size is how big a
burst you absorb** on top of that. A quiet client's bucket drains, so it has room for
a fresh burst later.

## Why "leaky" is the feature, not a bug

The hole is the **output tap**. "Leaking" is how the bucket drains so it can accept
more later — if it never leaked, it would fill once and reject forever. The confusing
name just describes the plumbing.

## The two ways to use it

**1. As a gate ("policing")** — what a boolean limiter does:

> If the bucket would overflow, **reject** the request.

Bursts pass through (the initial 10), then the client is held to the leak rate.

**2. As a shaper (a queue)** — where the name really comes from:

> Put the request **in the bucket**; release it out of the hole at the leak rate.
> Reject only when the bucket can't hold it.

A burst is then **absorbed and smoothed** — 10 requests in, but they leave at
1/second. This is traffic shaping (Linux `tc`, Nginx's `limit_req`).

## The level model (how you'd compute it)

You don't simulate a queue; you track the **water level**:

```
level = max(0, level − elapsed × R)          // it leaked while nobody was looking
if level + 1 ≤ capacity:  level += 1; ALLOW  // room for one more litre
else:                      REJECT
```

A fresh bucket starts at `level = 0` (empty), so it accepts `capacity` requests, then
rejects; the level drains over time and is floored at 0.

## Why there is no coded problem here

In the boolean `allowRequest` form, the leaky bucket is the **mathematical dual** of
the token bucket — `level = capacity − tokens`:

| | Token bucket (`04`) | Leaky bucket |
|---|---|---|
| mental model | tokens to **spend** | water **poured in** |
| starts | **full** (burst available) | **empty** (room available) |
| each request | `tokens −= 1` | `level += 1` |
| over time | `tokens += elapsed × R` | `level −= elapsed × R` |
| admit when | `tokens ≥ 1` | `level + 1 ≤ capacity` |
| reject when | bucket **empty** | bucket **full** |

That's just algebra — the same number seen from the other end — so both produce the
**exact same accept/reject sequence**. Problem 04 already exercises the decision; a
full problem 05 would be the same tests with the signs flipped. The only genuinely
different part is the **shaping** use above (hold and delay requests), and a boolean
`allowRequest` can't express "wait 40 ms and then you may go" — which is why this is a
note.

## Where you meet it

Traffic shaping (`tc`, token-bucket-filter), Nginx's `limit_req`, and any limiter that
*queues* work to smooth a bursty input into a steady output.
