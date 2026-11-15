# 07 — Shared state behind a store

## Goal

Move the limiter's state **out of the JVM** so a limit holds across many app
instances, and make every change to that shared state **atomic across processes**.
The result is the same fixed-window behaviour as problem 01, but enforced by a
central store instead of a per-process map.

This is the problem where "distributed" actually starts.

## Why this exists

Every algorithm so far kept its counters in a `ConcurrentHashMap` inside the JVM. That
is correct for **one** process — but production runs many copies of a service behind a
load balancer:

```
   node 1 ─┐
   node 2 ─┼─►  ...each with its own map.  A "50/min" limit becomes 50×nodes /min,
   node 3 ─┘     because each node counts only its own requests.
```

The fix is to keep the counter in a **shared store** (Redis, in production). That
solves the counting problem but creates three new ones, and they are the whole point of
this problem:

1. **Every read-modify-write is now a network round trip** and must be **atomic across
   processes** — two nodes incrementing the same counter must not lose an update.
2. **Time is no longer local.** Nodes' clocks disagree; the window index must come from
   one place.
3. **The store can fail.** A limiter in the request path must decide what to do when the
   store is unreachable.

## How it works

### The seam

Pull the shared state behind a tiny interface that exposes the **atomic primitives** the
algorithms need — not the algorithms themselves. For a fixed window there is really only
one:

- `incrementAndGet(key, ttl)` — atomically increment the counter at `key` and return the
  new value, and set `ttl` **when the key is created** (not on every increment, or the
  window would never close).
- `nowMillis()` — the store's clock: the single source of "now" shared by every node.

The store owns **atomicity**. The limiter owns **policy** (what key, what limit, what
window). Keeping that split is what lets the *same* limiter run on an in-memory store in
tests and Redis in production. The in-memory store is **provided here** as a test double;
the exercise is the limiter (and the Redis mapping further down is the production half).

```
  RateLimiter (policy)  ──uses──►  RateLimitStore (atomicity)
                                      ├── InMemoryRateLimitStore   (this repo)
                                      └── RedisRateLimitStore      (production)
```

### The fixed-window operation

The window is epoch-aligned, exactly as in problem 01, so the key includes the window
index:

```
  windowIndex = nowMillis / windowMillis        // nowMillis from the store
  key         = clientId + ":" + windowIndex
  count       = store.incrementAndGet(key, window)   // atomic
  allow iff   count <= maxRequests
```

A new window is a **new key**, so the counter resets without anyone having to reset it;
the `ttl` is just to stop old keys piling up.

### The trap: `INCR` then `EXPIRE` is not atomic

The naive Redis version is two commands:

```
  count = INCR key
  if count == 1: EXPIRE key ttl      // ← a separate command
```

If the process dies **between** the two — or a connection drops — the key exists with
**no expiry**. In a fixed window keyed only by `clientId`, that's a permanent ban for
that client. The fix is to make the increment-and-set-ttl **one atomic operation** (a Lua
script, or `SET key 0 PX ttl NX` before the `INCR`, or `EXPIRE ... NX`). The store's
`incrementAndGet` must guarantee this as a single step; the in-memory version does it
under one lock / `compute`, the Redis version in one script.

### Time from the store

`nowMillis` must come from the store, not the calling node — otherwise two nodes in
different windows split one client's budget across two keys. This is the same "inject the
clock" discipline from problem 01, just moved one level out.

### When the store is down

Decide and document: **fail-open** (allow the request; better for revenue paths) or
**fail-closed** (reject; better for abuse protection). A common answer is fail-open with
the local in-memory limiter as a backstop.

## The exact rule (part of the spec)

The seam:

- `long incrementAndGet(String key, Duration ttl)` — atomic; first write stores `1` and
  sets the TTL; later writes increment and leave the TTL alone.
- `long nowMillis()` — the store's clock.

The store-backed fixed-window limiter:

- constructor `(int maxRequests, Duration window, RateLimitStore store)`;
- the key is `clientId + ":" + (store.nowMillis() / windowMillis)`;
- `count = store.incrementAndGet(key, window)`; allow iff `count <= maxRequests`;
- validate as usual (`maxRequests > 0`, positive `window`, non-null `store`, non-blank
  `clientId`).

## Mapping to Redis (production)

| Seam method | Redis |
|---|---|
| `incrementAndGet(key, ttl)` | one Lua script: `INCR`, and `PEXPIRE` only when the result is `1` |
| `nowMillis()` | `TIME` (server clock), so every node agrees |

```lua
-- KEYS[1] = counter key, ARGV[1] = ttl in milliseconds
local c = redis.call('INCR', KEYS[1])
if c == 1 then
  redis.call('PEXPIRE', KEYS[1], ARGV[1])
end
return c
```

The script runs atomically on the server, so the `INCR`/`EXPIRE` race above cannot happen.
This repo exercises the same contract with an in-memory store, so the tests need no Redis.

## Trade-off

- **Correct across nodes**, at the cost of a **round trip per request** and a **new
  dependency** in the hot path.
- The store is now the thing that must be highly available; choose fail-open/closed.
- Keys shard by client, so this scales well — until a *global* key becomes a hot shard
  (a later, learn-only topic).

## Complexity

With **C = number of distinct clients** and **W = window length**:

- **Time:** O(1) per `allowRequest` (one store call).
- **Space:** about **O(C)** keys at a time. Each client has one key for its *current*
  window; older-window keys are dead and are reclaimed by their TTL — by Redis's active
  expiration in production, and by the in-memory store's opportunistic sweep in tests.
  (Without that cleanup they would pile up one per (client, window), which is why the
  key scheme matters for memory.)

## Where it lives

```
src/main/java/com/lld/ratelimiter/
    RateLimiter.java                    ← the shared seam callers use
    store/
        RateLimitStore.java             ← the store seam (given)
        InMemoryRateLimitStore.java     ← provided: the in-memory test double
        StoreFixedWindowRateLimiter.java ← implement: fixed window over the store

src/test/java/com/lld/ratelimiter/store/
    InMemoryRateLimitStoreTest.java
    InMemoryRateLimitStoreConcurrencyTest.java
    StoreFixedWindowRateLimiterTest.java
    StoreFixedWindowRateLimiterConcurrencyTest.java
```

## How to run the tests

Run **just this problem's tests** from the repo root:

```bash
mvn -Dtest='com.lld.ratelimiter.store.*Test' test
```

Other options:

```bash
# Every test in the repo
mvn test

# A single class
mvn -Dtest=com.lld.ratelimiter.store.InMemoryRateLimitStoreTest test
mvn -Dtest=com.lld.ratelimiter.store.StoreFixedWindowRateLimiterTest test
```

## Where next (learn-only)

Once a store exists, the harder distributed algorithms build on it and are worth
*reading* rather than coding here:

- **Distributed token bucket / GCRA** — the refill becomes one atomic read-modify-write
  (a Lua script, or a compare-and-set on GCRA's single timestamp).
- **Local + global limiting** — a per-node lease in front of the global store, to avoid a
  round trip on every request and to avoid a hot key.
