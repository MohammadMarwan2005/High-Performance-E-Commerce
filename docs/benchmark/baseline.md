# Baseline Benchmark — "Before" (Req 10a)

This is the **before** half of the Requirement 10 before/after story: the system
measured with **no caching** (the state prior to Step 6). It captures latency,
throughput, and — most importantly — **direct database load**, then names the
bottleneck that Step 6's Redis cache is introduced to fix.

The "after" half (caching enabled) is in [comparison.md](comparison.md).

---

## How this was measured

| Knob | Value | Why |
|---|---|---|
| App under test | a **single** instance, `INSTANCE_ID=bench`, port **8090** | One JVM so Hibernate's statistics counter captures *all* DB load in one place. Two instances behind Nginx would split the counter and muddy the measurement. |
| Run mode | `java -jar` (full tiered JIT) | Same invocation is reused for the cached run, so the only variable between before/after is the cache itself. (The IDE dev process runs `-XX:TieredStopAtLevel=1` and is deliberately **not** used for benchmarking.) |
| Load tool | k6 (`grafana/k6`), `loadtest/stress.js` | Version-controlled load profile from Step 4. |
| Profile | 80% browse `GET /products?page=..&size=20`, 15% hot-product `GET /products/{id}`, 5% checkout `POST /orders` | Read-heavy, realistic e-commerce mix. |
| Load | ramp to **120 VUs** (>100 required by Req 9), 70s total | 20s ramp · 40s hold · 10s ramp-down. |
| Catalog | 5,107 products | Pre-seeded via `POST /admin/products/seed`. |
| Warm-up | one full 70s run discarded before measuring | Warms the JIT and the Postgres buffer cache so the measured numbers are steady-state. |
| DB load source | Hibernate `Statistics` via `GET /admin/db-stats`, reset immediately before the measured run | `prepareStatementCount` = JDBC statements actually sent to Postgres. |

Raw evidence: [`baseline-k6.txt`](baseline-k6.txt) (full k6 output) ·
[`baseline-summary.json`](baseline-summary.json) (machine-readable k6 summary).

---

## Results

### Throughput & latency (k6, client-side)

| Metric | Value |
|---|---|
| Throughput | **614.7 req/s** (43,107 requests, 43,105 iterations in 70s) |
| Latency avg | 1.92 ms |
| Latency p90 / p95 | 2.86 ms / **3.47 ms** |
| Latency max | 29.29 ms |
| browse p95 | 3.53 ms |
| product (by id) p95 | 2.52 ms |

> **About the 5.06% `http_req_failed`:** these are **not errors**. k6 counts the
> `400 Out of stock` responses as "failed" HTTP, but the custom `checkout_failed`
> metric is **0.00%** — every checkout returned an *expected* `201` or `400`, and
> all functional checks passed (43,105/43,105). As the 120 VUs hammer the same
> hot product set, stock legitimately runs out and checkouts correctly refuse to
> oversell. The threshold "crossing" is a measurement artifact of the read-heavy
> stress profile, not a stability or correctness failure.

### Server-side service timings (AOP `@Timed` aspect, delivery req a)

The `PERF.TIMING` aspect measured each service call during the same window:

| Operation | Calls | Avg | Max |
|---|---|---|---|
| `ProductService.findAll` (browse) | 34,362 | **1.314 ms** | 27.82 ms |
| `ProductService.findById` (hot read) | 6,540 | 0.708 ms | 7.69 ms |
| `OrderService.placeOrder` (checkout) | 2,204 | 1.153 ms | 20.27 ms |

These corroborate k6 from inside the JVM: browse is both the **most frequent**
call and the one doing the most DB work per call.

### Direct database load (Hibernate statistics, the headline metric)

| Counter | Baseline (70s) | Per request |
|---|---:|---:|
| `prepareStatementCount` | **80,040** | ~1.86 |
| `queryExecutionCount` | 68,728 | ~1.59 |
| `entityLoadCount` | **698,268** | ~16.2 |
| `connectCount` | 43,117 | ~1.0 |

### Data integrity after the run (Req 9 assertions)

All four `loadtest/integrity_check.sql` assertions returned **0** under 120-VU load:

```
negative_stock        = 0
orphaned_orders       = 0
items_missing_product = 0
inconsistent_totals   = 0
```

No data loss, no half-applied writes, no inconsistency — the system stayed up and
correct throughout.

---

## The bottleneck

**Every product read goes to Postgres on every request — nothing is cached, and
the reads are massively repetitive.**

The numbers localize it precisely:

- **`entityLoadCount` = 698,268** over 43,105 iterations (~16 entity loads per
  request). Decomposed by traffic type:
  - browse: ~34,362 calls × 20 rows/page ≈ **689,000 loads (98.7%)**
  - by-id: ~6,540 calls × 1 row ≈ 6,500 loads (0.9%)
  - checkout reads ≈ the small remainder.
- So **the browse listing is the bottleneck.** And the work is *pure repetition*:
  the stress profile reads pages 0–49 at `size=20` — only **~50 distinct page
  queries** — yet they are re-executed **tens of thousands of times** against the
  database, each one re-running the same `SELECT … LIMIT 20 OFFSET …` and
  re-hydrating the same 20 entities.

This is the textbook read-amplification a cache exists to remove: a small set of
hot, read-mostly results fetched from the database over and over. Product data
changes far less often than it is read (only stock moves, and only on the 5%
checkout path), so the read results are highly cacheable.

### Why this isn't a *latency* problem yet

At 120 VUs latency is still low (p95 3.47 ms) because Postgres serves these tiny
indexed reads from its own buffer cache and the Hikari pool (10 connections)
isn't saturated. The bottleneck is **load**, not latency: 80k statements and ~698k
entity hydrations of *redundant* work that pin a database connection and CPU on
every request. That ceiling is what caps throughput and what would tip into
latency collapse as VUs climb or the catalog/payload grows. Removing the
redundant DB round trips is the headroom win.

---

## The fix (Step 6 → Step 7)

Introduce **Redis distributed caching** on the product reads:

- **By-id reads** (`findById`) → `@Cacheable`, **precisely evicted** on every stock
  change so the authoritative detail read is never stale.
- **Browse pages** (`findAll`) → `@Cacheable` with a **short TTL** (bounded
  staleness), since the ~50 hot page queries are exactly the redundant load above.

Expected effect, re-measured identically in [comparison.md](comparison.md):
`prepareStatementCount` and `entityLoadCount` drop sharply (cache hits never reach
Postgres), and browse latency falls toward cache-hit territory.
