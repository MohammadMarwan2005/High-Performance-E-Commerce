# Requirement 9 — Stress Test Summary

**Goal:** prove 100+ concurrent users are served with no crash and no data loss.

## Setup

- Tool: [k6](../../loadtest/stress.js) (`grafana/k6` Docker image)
- Load: **120 virtual users** (ramp 20s → hold 40s → ramp-down 10s)
- Profile: read-heavy — ~80% paginated browse, ~15% by-id reads, ~5% checkouts
- Target: single app instance (clean measurement; same code runs as 2 instances
  behind Nginx in production per Req 5)

## Result (raw: [stress-result.txt](stress-result.txt))

| Metric | Value |
|---|---|
| Total requests | **43,260** |
| Throughput | **617 req/s** |
| Checks passed | **100%** (43,258 / 43,258) |
| Browse p95 latency | 3.96 ms |
| Product-read p95 latency | 2.76 ms |
| Crashes / timeouts | **0** |
| Checkout failures (unexpected) | **0** (`checkout_failed: 0%`) |

> The ~4% `http_req_failed` are checkout `400 Out of stock` responses — correct,
> intended behavior when concurrent buyers deplete a product, **not** errors.
> No oversell occurred (see integrity check #1).

## Data integrity after the run (raw: [integrity-after-stress.txt](integrity-after-stress.txt))

All assertions returned **0 violations**:

| Check | Violations |
|---|---|
| Negative stock | 0 |
| Orphaned orders (no items) | 0 |
| Items referencing a missing product | 0 |
| Orders whose total ≠ sum(items) | 0 |

## Conclusion

The system absorbed 120 concurrent users and 43k requests with **no crash, no
data loss, and no inconsistency** — the bounded pools (Req 2) kept it stable and
the locking + transaction boundaries (Req 1/7/8) kept the data correct under
load. This is the baseline workload re-used in Step 5 (baseline benchmark) and
Step 7 (post-cache re-benchmark).

## Reproduce

```bash
# 1. app running + catalog seeded (POST /admin/products/seed?count=5000)
# 2. run the load test
docker run --rm --network host -e BASE_URL=http://localhost:8080 \
  -v "$PWD/loadtest:/ld" grafana/k6 run /ld/stress.js
# 3. assert integrity
docker exec -i ecommerce-postgres psql -U postgres -d ecommerce -f - < loadtest/integrity_check.sql
```
