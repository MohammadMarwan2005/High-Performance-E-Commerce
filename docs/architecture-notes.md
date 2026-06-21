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

## 6. Load distribution — Requirement 5

### Setup

Two identical app instances (`app1`, `app2`) sit behind an Nginx reverse proxy.
The entry point for all traffic is Nginx on port **8000**. Each instance is also
reachable directly (ports 8006/8007) for debugging.

```
client → Nginx :8000 → round-robin → app1 :8006
                                   → app2 :8007
```

Both instances share the same Postgres database and the same RabbitMQ broker.

### Why round-robin?

Nginx's default upstream algorithm is **round-robin**: request 1 → app1,
request 2 → app2, request 3 → app1, and so on.

This works well here because:

- Every request is **stateless** — no session affinity is needed. All
  persistent state lives in Postgres; the HTTP layer carries no per-user
  memory between requests.
- Processing time is similar across requests (no wildly slow outliers), so
  the load stays naturally even. If processing time varied significantly,
  `least_conn` (route to whichever instance has the fewest in-flight
  connections) would be more appropriate.

### Why instances must be stateless

An instance is stateless when **everything that survives the request lives
outside the process**. In this service:

| Concern | Storage |
|---|---|
| Orders, products, users | PostgreSQL (shared) |
| Async messages (invoice, notification) | RabbitMQ (shared) |
| Daily sales summaries | PostgreSQL (shared) |
| HTTP sessions | none — the API is sessionless |
| JVM heap | ephemeral — dies with the process |

Because no meaningful state lives in heap, any instance can serve any request.
Nginx can send request N to app1 and request N+1 to app2, and neither instance
needs to know about the other.

If session state were stored in-process (e.g. an `HttpSession` kept in a
`ConcurrentHashMap`), round-robin would break: app2 would not find the session
created by app1 and the user would see a 401 or lost cart. The standard fix is
an external session store (Redis), but the better architectural choice is to
eliminate server-side sessions entirely — which is what this service does.

### Link to Requirement 2 (pool sizing across instances)

When running two instances, the DB connection count **doubles**:

```
total DB connections = HikariCP pool size × number of instances
                     = 10  ×  2  =  20
```

PostgreSQL's default `max_connections` is 100 (and the dev container uses
that default), so 20 is well within budget. But this number must be tracked
consciously: adding a third instance brings it to 30, a tenth to 100 — at
which point new connection attempts would be refused.

The rule: **always size `maximum-pool-size × max_instances ≤ postgres max_connections`**.
Here that is satisfied with comfortable headroom.

Each instance also runs its own Tomcat thread pool (50 threads) and its own
async executor. Those are per-JVM resources and do not compound at the DB
level, only at the CPU level — which is fine, since the host has multiple cores
and the OS scheduler distributes the load.

### `X-Instance-Id` response header

Every response carries an `X-Instance-Id` header set by
[InstanceIdFilter](../src/main/java/com/ecommerce/E_Commerce/config/InstanceIdFilter.java).
The value is read from the `INSTANCE_ID` environment variable (set to `app1`
or `app2` in docker-compose). Sending several requests through Nginx and
inspecting this header is the observable proof that load is distributed.

---

## 7. Tunings reserved for production (deliberately not done here)

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

---

## 8. Requirement 7 — Concurrency Control: optimistic vs pessimistic

Both locking strategies are implemented for the same stock-decrement operation
and are selectable at runtime via `app.checkout.strategy`
(`optimistic` | `pessimistic`, env var `APP_CHECKOUT_STRATEGY`). Phase 1 shipped
optimistic; Phase 2 adds the pessimistic path and the comparison.

### The two implementations

| | Optimistic (default) | Pessimistic |
|---|---|---|
| Mechanism | `@Version` on `Product` | `SELECT … FOR UPDATE` |
| Code | [OrderCheckoutTransaction](../src/main/java/com/ecommerce/E_Commerce/service/OrderCheckoutTransaction.java) | [PessimisticOrderCheckout](../src/main/java/com/ecommerce/E_Commerce/service/PessimisticOrderCheckout.java) |
| Repository | plain `findById` | [`findByIdForUpdate`](../src/main/java/com/ecommerce/E_Commerce/repository/ProductRepository.java) (`@Lock(PESSIMISTIC_WRITE)`) |
| Lock held? | No — conflict detected at flush (`WHERE … AND version=?`) | Yes — row locked for the whole transaction |
| On conflict | retry (bounded, 3×) | no conflict — contenders **block** on the lock |

### The trade-off

**Optimistic wins under LOW contention.** No lock is taken, so the common case
(two requests rarely touching the same row) pays zero locking cost. The price is
paid only *when* a conflict happens — and then it's a retry. If conflicts are
rare, retries are rare, and throughput is maximal.

**Pessimistic wins under HIGH contention.** When many requests fight over the
*same* row (a flash sale on one product), optimistic locking degrades: every
loser retries, and under heavy contention retries themselves conflict, wasting
work (the "retry storm"). Pessimistic locking serializes access up front — each
contender waits once, then succeeds or fails cleanly. No wasted work, but the
cost is real DB locks held for the transaction's duration, which reduces
parallelism and risks lock waits / deadlocks if not handled.

### Why optimistic is the default here

The realistic workload is many *different* products being bought concurrently
(low per-row contention), with only occasional hotspots. That profile favors
optimistic. Pessimistic is provided for the case the brief asks us to reason
about: sustained contention on a single hot row.

### Deadlock avoidance (pessimistic path)

`PessimisticOrderCheckout` locks an order's products in a **consistent order
(ascending product id)**. Two orders touching products {A, B} both lock A before
B, so a hold-and-wait cycle (A waits for B while B waits for A) is impossible.
This is the standard lock-ordering defense against deadlock.

### Proof

`docs/req7-locking/optimistic-vs-pessimistic.txt` — a 50-thread race on a
stock-1 product under all three modes:

| Mode | Successful orders | Final stock | Oversell |
|---|---|---|---|
| naive (no lock) | 6 | 0 | **YES** |
| optimistic (`@Version` + retry) | 1 | 0 | no |
| pessimistic (`SELECT … FOR UPDATE`) | 1 | 0 | no |

Both locking strategies hold the invariant; they differ in *how* (retry vs
block), which is exactly the trade-off above. Regenerate any time via
`POST /proofs/req1/concurrency` with `{"mode":"both"}`.

---

## 9. Requirement 8 — Transaction Integrity (ACID), all-or-nothing

The composite checkout — **stock decrement + order create** — runs inside a
single `@Transactional` boundary ([OrderCheckoutTransaction](../src/main/java/com/ecommerce/E_Commerce/service/OrderCheckoutTransaction.java)).
ACID atomicity guarantees these two writes either both commit or both roll back;
the system can never persist a stock decrement without its order, or an order
without its stock decrement.

### How it's proven

[AcidIntegrityIT](../src/test/java/com/ecommerce/E_Commerce/acid/AcidIntegrityIT.java)
injects a failure **after** both writes are flushed to the DB but **before** the
transaction commits, then checks the database is byte-for-byte unchanged:

| Scenario | Attempts | Rolled back | Stock (start → end) | Orders (Δ) | Result |
|---|---|---|---|---|---|
| Single failure | 1 | 1 | 100 → 100 | 0 | **INTACT** |
| Concurrent failures | 50 | 50 | 100 → 100 | 0 | **INTACT** |

Evidence: `docs/req8-acid/acid-rollback.txt`. Run with
`./mvnw test -Dtest=AcidIntegrityIT`.

### Why the failure is injected *after* the writes

A rollback test only proves anything if there is committed-looking work to undo.
By flushing the stock decrement and the order insert to the database *first*
(`saveAndFlush`), the injected exception forces the transaction manager to
actively reverse real pending changes — not just abandon an empty transaction.
The "after" timing is what makes it a genuine atomicity proof.

### Link to Requirements 1 and 7

Atomicity (Req 8) and isolation/locking (Req 1 & 7) are complementary ACID
properties: locking decides *who wins* a concurrent race on a row; the
transaction boundary decides that each winner's multi-step work is *all-or-
nothing*. Together they give: exactly one order succeeds, and that order is
fully consistent.

---

## 10. Delivery requirement (a) — AOP performance monitoring

Execution time of the critical operations is measured **without a single line of
timing code inside the services**, using Spring AOP.

- [`@Timed`](../src/main/java/com/ecommerce/E_Commerce/monitoring/Timed.java) — a
  marker annotation that selects join points.
- [`TimingAspect`](../src/main/java/com/ecommerce/E_Commerce/monitoring/TimingAspect.java)
  — an `@Around` advice that wraps every `@Timed` method, measures wall-clock time
  with `System.nanoTime()`, and emits one structured line on a dedicated
  `PERF.TIMING` logger:

  ```
  op=ProductService.findAll durationMs=1.31 outcome=ok
  ```

  The `key=value` format is deliberately greppable so the Step-5/7 benchmark can
  parse it (see the per-op tables in `docs/benchmark/`).

**Why the aspect is ordered `@Order(0)` (outermost).** The product reads are also
wrapped by the Req-6 `@Cacheable` interceptor. If caching were the outer advice, a
cache **hit** would return before the timing advice ran, and the timing numbers
would only ever reflect the slow cache-*miss* path. Making `TimingAspect` the
highest-precedence advice means it wraps the cache lookup too, so a hit is measured
end-to-end — which is exactly why the before/after service timings (findAll
1.31 ms → 0.50 ms) actually show the cache win.

Applied to the critical paths: `ProductService.findAll`, `ProductService.findById`,
`OrderService.placeOrder`, and the `DailySalesJob` batch run.

---

## 11. Requirement 6 — Distributed caching (Redis)

Redis caching is introduced **specifically as the fix** for the bottleneck the
Step-5 baseline named: the same small set of product-read queries re-executed tens
of thousands of times against Postgres (see §12). Config:
[`CacheConfig`](../src/main/java/com/ecommerce/E_Commerce/config/CacheConfig.java).

### Two caches, two consistency models

| Cache | Backs | TTL | Consistency model | Why |
|---|---|---|---|---|
| `productById` | `GET /products/{id}` | 10 min | **Strong** — evicted on every write | The authoritative detail read. Correctness must be exact, and a single id is cheap to evict precisely. |
| `productPages` | `GET /products?page=…` | **5 s** | **Bounded staleness** — TTL only | The browse listing is the real DB-load win, but a listing spans many `(page,size,sort)` permutations; precisely evicting all of them on every stock change is impractical and the write rate would defeat the cache anyway. A short TTL caps staleness to seconds while still collapsing the redundant reads. |

The split is the key design decision: the **authoritative** read (by id) is never
stale; the **listing** read accepts a few seconds of staleness — a standard,
defensible e-commerce pattern (detail pages are exact, category listings are
near-real-time).

### Invalidation strategy (how the cache stays correct on stock change)

- **Checkout** (`OrderService.placeOrder`) decrements stock, then — *after the
  transaction commits* — evicts each purchased product id from `productById`. So a
  detail read right after a sale always reflects the new stock. The checkout path
  itself never reads from the cache: it loads the row fresh under its optimistic
  `@Version` / pessimistic `FOR UPDATE` rule (Req 7), so caching can't weaken the
  oversell guarantee.
- **Create / bulk seed** (`ProductService.create`, `ProductSeedService.seed`) wipe
  `productPages` (`allEntries`) because a new product can appear on any listing.
- **Browse pages** are otherwise left to expire by their 5 s TTL.

**Verified:** a product cached at stock 15, checked out once, is immediately
re-read as a fresh **14** — no stale stock. Under the 120-VU stress run all four
integrity assertions stay at 0.

### Serialization & eviction policy

- Cache values use the cache manager's default **JDK serialization**. `Product`
  implements `Serializable`, which lets both the entity and the
  `PageImpl<Product>` returned by the browse query round-trip cleanly (this avoids
  the well-known Jackson `PageImpl` deserialization problem).
- Redis runs with **`maxmemory 64mb`** and **`allkeys-lru`** (see
  `docker-compose.yml`): under memory pressure the least-recently-used entries are
  dropped first — the correct policy for a hot-set read cache — and Redis can never
  starve the host.

---

## 12. Requirement 10 — Benchmarking & bottleneck analysis

Full write-ups with raw evidence live in `docs/benchmark/`:
[`baseline.md`](benchmark/baseline.md) (before + named bottleneck) and
[`comparison.md`](benchmark/comparison.md) (before/after table + interpretation).

**Method.** A single app instance (so Hibernate's statistics counter captures all
DB load in one place), run from the **same `java -jar` invocation** before and
after, driven by the same k6 profile (`loadtest/stress.js`: 80% browse / 15% by-id
/ 5% checkout, ramped to 120 VUs for 70 s). The only variable between the two runs
is whether caching is on.

**Bottleneck (baseline).** ~698k entity loads in 70 s, **98.7% of them from
browse-page reads** — the same ~50 page queries re-executed thousands of times.
Pure read amplification of read-mostly data: the textbook cache target.

**Result with caching (the headline numbers, in one place):**

| Dimension | Before | After | Change |
|---|---:|---:|---:|
| JDBC statements / 70 s | 80,040 | 6,010 | **−92.5%** |
| Entity loads / 70 s | 698,268 | 18,914 | **−97.3%** |
| Redis hit ratio | — | 98.2% | — |
| Browse latency p95 | 3.53 ms | 2.13 ms | −39.6% |
| Overall latency avg | 1.92 ms | 1.13 ms | −41% |
| `findAll` service time | 1.31 ms | 0.50 ms | −62% |
| Throughput (120 VUs) | 614.7 req/s | 619.8 req/s | +0.8% |

**Reading the result honestly.** Throughput is flat because the load is
*closed-loop*: 120 VUs each looping with ~150 ms think-time, so `req/s ≈ VUs ÷
iteration_duration` and the server isn't the ceiling at this concurrency. The
cache's payoff is the two things that did move — **~40% lower latency** and **~95%
of database work eliminated**. That freed DB capacity is headroom: it is the margin
that lets the system scale to far higher load before the 10-connection Hikari pool
and Postgres CPU (§2) become the limiting factor — i.e. the cache moves the next
bottleneck much further out, without sacrificing correctness (§11).
