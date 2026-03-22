# Distributed Rate Limiter — Token Bucket Algorithm

A thread-safe, in-memory rate limiting library in Java, implementing the Token Bucket algorithm. Built to understand and demonstrate the concurrency patterns used in production API Gateways.

> **Why I built this:** At Airtel, I implemented token bucket rate limiting at our API Gateway to protect 8+ downstream microservices from traffic bursts. This project is me isolating that pattern — understanding it deeply enough to build it from scratch, test it under concurrency, and reason about how to extend it to a distributed system.

---

## What this is (and isn't)

**This is:** A correct, well-tested, in-process implementation of the Token Bucket algorithm in Java. It handles concurrent requests safely, supports multiple independent clients, and correctly models burst capacity vs sustained throughput.

**This is not (yet):** A distributed rate limiter. Token state lives in the JVM heap — two instances of this app would have independent buckets. The [Extending to distributed systems](#extending-to-distributed-systems) section documents exactly how I would solve that with Redis.

---

## How Token Bucket works

```
Bucket capacity: 10 tokens
Refill rate:      2 tokens/sec

t=0s   [██████████]  10 tokens — full
       Request → consume 1 → 9 tokens
       Request → consume 1 → 8 tokens
       ...8 more burst requests...
       [          ]   0 tokens — bucket empty
       Request → DENIED

t=0.5s [█         ]   1 token refilled → next request allowed
t=1s   [██        ]   2 tokens refilled
```

Unlike a **fixed window counter** (resets every N seconds, allows 2× the rate at window boundaries), the token bucket smooths traffic continuously. A client can burst up to `capacity` requests, then is throttled to exactly `refillRate` requests/sec.

---

## Design

### Components

```
Main.java                →  simulates multi-client concurrent traffic (3 demos)
RateLimiterService.java  →  manages one TokenBucket per client (ConcurrentHashMap)
TokenBucket.java         →  core algorithm: check, consume, refill (ReentrantLock)
```

### Request flow

```
Client request
    │
    ▼
RateLimiterService.allowRequest(clientId)
    │
    ├── fetch or create TokenBucket for clientId (computeIfAbsent)
    │
    ▼
TokenBucket.allowRequest()
    │
    ├── acquire ReentrantLock
    ├── calculate tokens refilled since last request
    ├── if tokens >= 1  →  decrement, release lock  →  ALLOW
    └── else            →  release lock             →  DENY
```

### Concurrency model

The critical section is the **check-and-decrement**. Without a lock, two threads can both read `tokens > 0`, both decrement, and both be allowed when only one should be:

```java
// Without lock — WRONG
if (tokens >= 1) {        // Thread A reads true
                          // Thread B reads true  ← race condition
    tokens -= 1;          // both decrement → over-allowance
    return true;
}
```

`ReentrantLock` serialises access per bucket. Locks are **per-client**, not global — so clients never block each other, only concurrent requests from the *same* client contend on the same lock:

```java
lock.lock();
try {
    refill();              // add tokens based on elapsed time
    if (tokens >= 1) {
        tokens -= 1;
        return true;       // allowed
    }
    return false;          // denied
} finally {
    lock.unlock();         // always released, even on exception
}
```

`ConcurrentHashMap` + `computeIfAbsent` in `RateLimiterService` guarantees only one bucket is ever created per client, even under concurrent first-requests.

---

## Tech stack

| | |
|---|---|
| Language | Java 8 |
| Concurrency | `ReentrantLock`, `ConcurrentHashMap` |
| Build | Maven |
| Testing | JUnit 5.8.2 |
| Dependencies | None (no Redis, no Spring — pure Java) |

---

## Running it

```bash
git clone https://github.com/B0272590/distributed-rate-limiter.git
cd distributed-rate-limiter
mvn compile exec:java -Dexec.mainClass="rateLimiter.Main"
```

### Sample output

```
=== Demo 1: Single client — burst then throttle ===

Sending 12 requests instantly (bucket capacity = 10):
  Request  1 → ALLOWED
  Request  2 → ALLOWED
  ...
  Request 10 → ALLOWED
  Request 11 → DENIED ← rate limited
  Request 12 → DENIED ← rate limited

Waiting 1 second for token refill...

Sending 3 more requests after refill:
  Request 13 → ALLOWED
  Request 14 → ALLOWED
  Request 15 → DENIED

=== Demo 2: Multiple clients — independent buckets ===

Exhausting client-A's bucket:
  [client-A] Request 11 → DENIED

client-B makes requests (independent bucket — should all be allowed):
  [client-B] Request  1 → ALLOWED
  [client-B] Request  2 → ALLOWED
  ...

=== Demo 3: Concurrent requests — thread safety ===

  Threads fired simultaneously : 50
  Bucket capacity              : 10
  Allowed                      : 10
  Denied                       : 40
  Over-allowance               : NONE — thread safety verified
```

---

## Tests

```bash
mvn test
```

```
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

| Test | What it verifies |
|---|---|
| `testBurstUpToCapacity` | Allows exactly `capacity` requests on a full bucket |
| `testDeniesWhenEmpty` | Denies requests once bucket is exhausted |
| `testRefillAfterWait` | Tokens refill at configured rate after waiting |
| `testCapacityCapAfterLongIdle` | Bucket never exceeds capacity after long idle |
| `testBurstThenRecover` | Burst exhausts bucket, recovery works after refill |
| `testConcurrentRequests` | 100 threads fire simultaneously — zero over-allowance |
| `testNoDeadlockUnderLoad` | 20 threads × 50 requests each — no deadlock in 5s |
| `testClientIsolation` | Client A exhausting budget does not affect Client B |
| `testNewClientStartsFull` | New client always starts with a full bucket |

The concurrency test is the most important one:

```java
@Test
void testConcurrentRequests() throws InterruptedException {
    int threadCount = 100;
    AtomicInteger allowed = new AtomicInteger(0);

    ExecutorService pool  = Executors.newFixedThreadPool(threadCount);
    CountDownLatch  ready = new CountDownLatch(threadCount);
    CountDownLatch  start = new CountDownLatch(1);
    CountDownLatch  done  = new CountDownLatch(threadCount);

    for (int i = 0; i < threadCount; i++) {
        pool.submit(() -> {
            try {
                ready.countDown();
                start.await();   // all threads wait — then fire simultaneously
                if (bucket.allowRequest()) allowed.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
    }

    ready.await();
    start.countDown();   // release all 100 threads at once
    done.await();
    pool.shutdown();

    // Must never allow more than capacity — not even once
    assertEquals(CAPACITY, allowed.get());
}
```

---

## Project structure

```
distributed-rate-limiter/
├── pom.xml
└── src/
    ├── main/
    │   └── java/
    │       └── rateLimiter/
    │           ├── Main.java
    │           ├── TokenBucket.java
    │           └── RateLimiterService.java
    └── test/
        └── java/
            └── rateLimiter/
                └── TokenBucketTest.java
```

---

## Extending to distributed systems

This implementation is intentionally scoped to a single JVM. Here is exactly how I would extend it:

**The problem:** Two app instances each hold their own `TokenBucket` in memory. A client sending 10 requests split across two instances would be allowed 20 — the global limit is not enforced.

**The solution — Redis as shared token store:**

Replace the in-memory `tokens` field with a Redis key per client. Use a Lua script to make check-and-decrement atomic at the Redis level:

```lua
-- Runs as a single atomic Redis command — no race condition possible
local key         = KEYS[1]
local capacity    = tonumber(ARGV[1])
local refill      = tonumber(ARGV[2])
local now         = tonumber(ARGV[3])

local data        = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens      = tonumber(data[1]) or capacity
local last_refill = tonumber(data[2]) or now

local elapsed     = math.max(0, now - last_refill)
local new_tokens  = math.min(capacity, tokens + elapsed * refill)

if new_tokens >= 1 then
    redis.call('HMSET', key, 'tokens', new_tokens - 1, 'last_refill', now)
    redis.call('EXPIRE', key, 60)
    return 1   -- allowed
else
    return 0   -- denied
end
```

Why Lua? Redis executes Lua scripts atomically — no other command runs between reading and writing the token count. This replaces `ReentrantLock` for cross-instance consistency.

The interface (`RateLimiterService.allowRequest(clientId)`) stays identical — only the storage backend changes. This is the next planned iteration of this project.

---

## Why this project
 
Rate limiting looks simple until you implement it correctly under concurrency. The interesting problems are:

- **Race conditions** — why `if (tokens > 0) tokens--` is wrong without synchronisation, and what actually goes wrong under load
- **Lock granularity** — per-client locks vs a single global lock (clients should never block each other)
- **Refill semantics** — continuous time-based refill vs fixed-interval reset, and why they behave differently under burst traffic
- **Distributed consistency** — why in-process locking is insufficient across instances, and why you need atomic server-side scripts in Redis

These are the same questions I reason about when designing rate limiting at the API Gateway layer at Airtel.

---

## Author

**Diptesh Kumar** — Senior Software Engineer, Bharti Airtel
[krdiptesh@gmail.com](mailto:krdiptesh@gmail.com) · [LinkedIn](https://linkedin.com/in/krdiptesh)
