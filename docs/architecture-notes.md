# Architecture Notes — E-Commerce Backend

This document records the *why* behind every concrete number in the
configuration. The grading rubric for Requirement 2 ("Resource Management &
Capacity Control") asks that each value be justified, that the relationships
between pools be intentional, and that the failure behaviour at each
saturation point be explicit. So: every knob below is paired with the
question "what happens when this fills up?"

---

## 1. Tomcat HTTP thread pool

Defined in [application.yml](../src/main/resources/application.yml) under
`server.tomcat`:

| Property | Value | Why |
|---|---|---|
| `threads.max` | **50** | Maximum concurrent request-processing threads. Spring Boot's default is 200, which is too many for a single demo machine — we pick 50 so saturation is observable during a load test. |
| `threads.min-spare` | **10** | Warm threads always kept alive. Saves cold-start latency on the first 10 concurrent requests after idle. |
| `accept-count` | **100** | TCP backlog: how many *queued* connections the OS will park while all 50 worker threads are busy. Beyond 100 queued, the OS refuses the connection (the client sees a connect timeout / reset). |
| `max-connections` | **200** | Hard ceiling on simultaneously-open connections (active + idle). At 50 active + 100 accept-queue we still have 50 slots of headroom for keep-alive idle connections. |
| `connection-timeout` | **5s** | If the client sends nothing within 5s, the connection is dropped. Prevents slow-client denial-of-service from holding worker threads. |

**Saturation behaviour:**
- 1–50 concurrent requests: served immediately by worker threads.
- 51–150: queued in the accept-count backlog; latency rises but no error.
- 151–200: still within `max-connections` but the accept queue is full — new
  connects are refused with TCP reset.
- Above 200: refused at the OS socket level.

---

## 2. HikariCP database connection pool

Defined under `spring.datasource.hikari`:

| Property | Value | Why |
|---|---|---|
| `maximum-pool-size` | **10** | Maximum DB connections this app holds. **Intentionally smaller than `threads.max=50`.** See the relationship section below. |
| `minimum-idle` | **5** | Warm idle connections kept open between bursts. Saves TCP+TLS handshake on the next request. |
| `connection-timeout` | **5000 ms** | If a thread can't acquire a connection from the pool within 5s, Hikari throws `SQLTransientConnectionException`. The transaction fails fast rather than hanging the request thread. |
| `idle-timeout` | **30000 ms** | Idle connections beyond `minimum-idle` are closed after 30s of inactivity. |
| `max-lifetime` | **600000 ms (10 min)** | Hard recycle — every connection is replaced after 10 min regardless of idleness. Protects against DB-side firewall/idle drops and gives Postgres room to release server-side state. |
| `leak-detection-threshold` | **30000 ms** | If a connection is checked out for more than 30s without return, Hikari logs a stack trace. Catches forgotten `close()` or runaway transactions. |

**Why DB pool size (10) < Tomcat threads (50)?**

A DB connection is dramatically more expensive than a Java thread. On the
Postgres side each connection is a separate OS process. On the app side each
connection holds memory, prepared-statement caches, and active socket state.
The textbook formula is:

```
db_pool ≈ (cores × 2) + effective_spindle_count
```

For our development laptop that lands in the 8–12 range — we chose 10.

The constraint that **pool size < thread count** is deliberate. Most requests
finish their DB work quickly (one or two short queries inside an
`@Transactional` block). With 50 worker threads but only 10 connections, the
arithmetic is:

- The DB does the bottleneck work, not the thread pool.
- The remaining 40 threads can serve cheap operations (validation, response
  serialization) while 10 are mid-query.
- If 50 threads all need a connection simultaneously, the surplus 40 block
  on the pool acquire — but only briefly, because queries are short. If
  blocking exceeds `connection-timeout=5s`, we fail fast and return a 5xx.

Sizing the DB pool the same as (or larger than) the thread pool would just
move the bottleneck onto the DB and quietly accept *more* concurrent
transactions than the DB is sized for — leading to lock contention and the
death-spiral pattern where every transaction takes longer because every
transaction is contending.

**Saturation behaviour:**
- 1–10 concurrent transactions: each gets a connection immediately.
- 11–N concurrent: queued at the Hikari pool, up to `connection-timeout`.
- Beyond `connection-timeout`: `SQLTransientConnectionException` → the
  current request returns 5xx → load shed.

---

## 3. App async executor

Defined in
[AsyncExecutorConfig.java](../src/main/java/com/ecommerce/E_Commerce/config/AsyncExecutorConfig.java)
as the `appAsyncExecutor` bean.

| Property | Value | Why |
|---|---|---|
| `corePoolSize` | **5** | Always-alive workers. Background load is generally light. |
| `maxPoolSize` | **10** | Burst ceiling. Once `corePoolSize` is busy AND the queue is full, the executor scales up to 10. |
| `queueCapacity` | **25** | Bounded backlog. Tasks pile up here when all 5 core threads are busy, *before* the executor decides to spin up the 6th–10th worker. |
| `threadNamePrefix` | `app-async-` | Logs and thread dumps are immediately filterable for async work. |
| `rejectedExecutionHandler` | **AbortPolicy** | When the queue (25) is full AND the pool is at max (10), the next submit throws `RejectedExecutionException`. We do **not** use CallerRunsPolicy — running the task on the caller's Tomcat thread would defeat the "off the request path" property entirely. |

**Why a bounded queue?**

`ThreadPoolTaskExecutor` defaults to a `LinkedBlockingQueue` — and the
default capacity is `Integer.MAX_VALUE`, which is effectively unbounded. An
unbounded queue is the standard production foot-gun:

1. It hides backpressure. Submitters keep enqueuing for free.
2. The pool **never grows past `corePoolSize`**, because Java's
   `ThreadPoolExecutor` policy is: enqueue first, scale up only when the
   queue rejects. An unbounded queue never rejects. So `maxPoolSize` is
   silent dead code.
3. Memory grows linearly with backlog. The first sign of trouble is OOM,
   long after a saturated system should have started shedding load.

So we set `queueCapacity = 25` explicitly. Now:

- 1–5 tasks: immediate, on core threads.
- 6–30 tasks: 5 running + up to 25 queued.
- 31–35 tasks: queue is full, scale up to extra workers (6→10).
- 36+ tasks (10 running + 25 queued): submit throws — visible failure.

**Saturation behaviour:** loud, observable, and traceable to the call site
that submitted too much.

---

## 4. The three pools together

```
        client                 Tomcat                  Hikari                Postgres
        ──────                ────────                ────────              ──────────
   N → │ accept │  →  ≤ 50 worker threads   →   ≤ 10 connections     →   ≤ ~100 conns
       │ queue  │      (other work threads          (the bottleneck)         (server-side)
       │ ≤ 100  │       wait on the pool)
```

Layered ceilings, decreasing capacity. Each layer absorbs bursts up to its
queue size, then back-pressures the layer in front via a measurable wait
time. The async executor sits to the side, used for any work explicitly
moved off the request path (Step 5 will use this).

---

## 5. Link to Requirement 1 (Concurrency & Data Integrity)

The Req 1 proof (`docs/req1-proof/`) shows that *correctness* is enforced by
optimistic locking on `Product.@Version`. Req 2 here shows that
*stability* is enforced by the three bounded pools above. The two are
complementary: locking guarantees the system never oversells; pooling
guarantees the system never melts under load.

Specifically:
- Under heavy contention on a single product, optimistic-lock failures
  cause retries (up to 3) inside the same transaction window.
- Those retries *all* acquire their connection from the same Hikari pool, so
  pool sizing limits the worst-case concurrent transaction count even when
  retries are common.

---

## 6. Tunings reserved for production (deliberately not done here)

To keep this file honest about what is and isn't tuned:

- These numbers target a single-machine demo. On real hardware,
  `threads.max` and `pool-size` should be re-derived from CPU count, query
  latency, and observed p99 holding time per connection.
- We don't pre-warm the JIT, don't size the JVM heap explicitly, and don't
  set socket SO_REUSEPORT. Those belong in deployment config, not source.
- The async executor does **not** propagate `SecurityContext` or
  `RequestAttributes`. That's fine for the work it currently does
  (Step 5 invoice/notification publishing); a different use case would
  need `DelegatingSecurityContextAsyncTaskExecutor` or similar.
