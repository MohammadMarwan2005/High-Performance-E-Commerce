# Before / After Benchmark — Redis Caching (Req 10b)

This is the **after** half of Requirement 10. The same load test from
[baseline.md](baseline.md) is re-run with **Step 6 Redis caching enabled**, on the
**same jar invocation, same host, same Postgres, same k6 profile** — the only
variable changed is the cache. That isolation is what makes the delta attributable
to caching and nothing else.

Raw evidence: [`cached-k6.txt`](cached-k6.txt) · [`cached-summary.json`](cached-summary.json)
(baseline counterparts: [`baseline-k6.txt`](baseline-k6.txt) · [`baseline-summary.json`](baseline-summary.json)).

---

## Method (identical to baseline)

Single instance on `:8090` (`java -jar`, full JIT) · k6 `loadtest/stress.js` ·
80% browse / 15% by-id / 5% checkout · ramp to **120 VUs** over 70 s · catalog of
5,107 products · one 70 s warm-up discarded (warms JIT **and** the cache) · Hibernate
`Statistics` reset immediately before the measured window · Redis `CONFIG RESETSTAT`
before the window to measure hit ratio over the run only.

---

## Before / after table

### Database load — the headline (Hibernate statistics, 70 s window)

| Counter | Baseline (no cache) | Cached | Change |
|---|---:|---:|---:|
| `prepareStatementCount` | 80,040 | **6,010** | **−92.5%** |
| `queryExecutionCount` | 68,728 | **1,451** | **−97.9%** |
| `entityLoadCount` | 698,268 | **18,914** | **−97.3%** |
| `connectCount` | 43,117 | **2,909** | **−93.3%** |
| statements **per request** | ~1.86 | **~0.14** | −92% |
| entity loads **per request** | ~16.2 | **~0.43** | −97% |

**Redis effectiveness over the measured window:** 40,595 `keyspace_hits` vs 724
`keyspace_misses` → **98.2% hit ratio**. The browse-page reads that *were* the
bottleneck now overwhelmingly never reach Postgres.

### Latency (k6, client-side)

| Metric | Baseline | Cached | Change |
|---|---:|---:|---:|
| overall avg | 1.92 ms | 1.13 ms | **−41%** |
| overall p95 | 3.47 ms | 2.37 ms | −31.7% |
| **browse** avg | 2.02 ms | 1.11 ms | **−45%** |
| **browse** p95 | 3.53 ms | 2.13 ms | −39.6% |
| **product (by id)** avg | 1.30 ms | 0.86 ms | −33.7% |
| **product (by id)** p95 | 2.52 ms | 1.55 ms | −38.5% |

### Server-side service timings (AOP `@Timed` aspect)

The timing aspect is ordered outermost, so a cache **hit** is still measured —
these averages therefore reflect the real end-to-end service cost, cache included:

| Operation | Baseline avg | Cached avg | Change |
|---|---:|---:|---:|
| `ProductService.findAll` (browse) | 1.314 ms | 0.497 ms | **−62%** |
| `ProductService.findById` (hot read) | 0.708 ms | 0.349 ms | −51% |
| `OrderService.placeOrder` (checkout) | 1.153 ms | 1.266 ms | +10% |

Checkout is *slightly* slower, as expected: it deliberately bypasses the cache
(reads fresh under its lock for correctness) and now also evicts the affected id.
That ~0.1 ms is the small, correct price paid so reads can be cached safely.

### Throughput & integrity

| Metric | Baseline | Cached |
|---|---:|---:|
| Throughput | 614.7 req/s | 619.8 req/s (+0.8%) |
| Iterations (70 s) | 43,105 | 43,495 |
| Integrity assertions | all **0** | all **0** |

(`http_req_failed` ≈ 5% in both runs is the out-of-stock `400`s, not errors —
`checkout_failed` is 0% in both; see baseline.md.)

---

## Interpretation

**1. The bottleneck is gone.** Step 5 named it: the same ~50 browse-page queries
re-executed tens of thousands of times, doing ~698k redundant entity hydrations.
With caching, **~95% of all database work disappears** (statements −92.5%, entity
loads −97.3%) at a **98.2% cache hit ratio**. Postgres now does a small fraction of
the work for the same delivered traffic.

**2. Latency dropped ~40%** because a Redis `GET` over loopback is cheaper than a
Postgres round trip plus hydrating 20 JPA entities — so even an unsaturated server
answers reads faster.

**3. Why throughput is flat (and why that's the expected result).** The load model
is *closed-loop*: 120 virtual users, each looping with ~150 ms of think-time. In
that model `req/s ≈ VUs ÷ iteration_duration`, and `iteration_duration` is
dominated by the client think-time (≈unchanged: 153 ms → 152 ms). At 120 VUs
neither run saturates the server, so throughput is **VU-bound, not server-bound** —
shaving server time can't raise a ceiling the server wasn't setting. The cache's
payoff here is the two things that *did* move: **~40% lower latency** and **~95%
freed database capacity**. That freed capacity is headroom — the system can now
absorb far more concurrent load before Postgres (the 10-connection Hikari pool and
the DB CPU) becomes the limit, which is exactly the bottleneck a higher-VU or
larger-catalog run would have hit first without the cache.

**4. Correctness held throughout.** Both runs pass all four integrity assertions
(no negative stock, no orphaned orders, no dangling items, no inconsistent totals),
and the by-id cache is precisely evicted on every stock change — verified directly:
a product cached at stock 15, checked out once, immediately re-read as a fresh 14.
The win is real and paid for without giving up consistency where it matters.
