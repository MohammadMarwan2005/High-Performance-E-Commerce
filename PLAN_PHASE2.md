# PLAN.md — High-Performance E-Commerce Backend Engine (Phase 2)

> Parallel Programming Course Project — Spring 2026
> **Stack:** Spring Boot 3.x (Java 17+) · PostgreSQL · **Redis** · RabbitMQ · Nginx · **k6/JMeter** · Docker Compose
> **Scope of this file:** Phase 2 only — Requirements 6 through 10 + delivery req (a) AOP.
> **Phase 1 (Requirements 1–5) is already complete.**

---

## ⚠️ WORKFLOW RULES — Claude Code MUST follow these

1. **One step at a time. Stop after each step.**
   After completing a step, STOP and tell me what to test. Do **not** start the
   next step until I have manually tested and explicitly confirmed it works.

2. **I test → I confirm → only then Claude commits.**
   Claude never commits on its own initiative. Claude commits **only after I say
   the step is confirmed working.**

3. **Commit message format is exactly:**
   ```
   ✅ <step name>
   ```
   (the green check, a space, then the step name as written in this file)

4. **Commits are authored by ME, not by Claude.**
   - Do **NOT** pass `--author`.
   - Do **NOT** add `Co-Authored-By` or any Claude/AI trailer.
   - Do **NOT** modify git config.
   - Use the repo's existing user config as-is so the commit is mine.
   - Plain commit only: `git add -A && git commit -m "✅ <step name>"`

5. **After committing, update this file:** change the step's `[ ]` to `[x]`,
   then stop and wait for me before the next step.

6. If a step is ambiguous or a decision is needed, ask me **before** coding —
   do not guess on architecture choices.

---

## Phase 2 context Claude must keep in mind (state this if I ask)

- **Phase 2 is measurement-heavy, not build-heavy.** Only Redis (Req 6) is new
  infrastructure. The rest is proving, measuring, and formalizing.
- **Requirements 7 and 8 are already partially built** in Phase 1 (optimistic
  locking and `@Transactional` were needed for Req 1). Phase 2 *expands and
  formalizes* them — it does not build them from scratch.
- **Deliberate ordering:** we measure first, find the bottleneck, then add
  caching as the *fix*, then re-measure. Caching (Req 6) intentionally comes
  AFTER stress testing so Req 10 becomes a real before/after story. Do not
  reorder this.

---

## Requirements covered in Phase 2

| # | Requirement | Built in step(s) |
|---|-------------|------------------|
| (a) | AOP performance monitoring (delivery req) | 1 |
| 7 | Concurrency Control — optimistic vs pessimistic locking | 2 |
| 8 | Transaction Integrity (ACID) — all-or-nothing | 3 |
| 9 | Stress Testing — 100+ users, no data loss | 4 |
| 10 | Benchmarking & Bottleneck Analysis (before/after) | 5, 7 |
| 6 | Distributed Caching (Redis) — the bottleneck fix | 6 |

---

## STEPS

### [ ] Step 1 — AOP performance-monitoring aspect (delivery req a)

**Goal:** Measure execution time of key operations without touching business code.

- Add Spring AOP.
- An `@Around` aspect that wraps target service methods (by a custom annotation
  e.g. `@Timed`, or a package pointcut) and logs execution time in ms.
- Apply it to the critical paths: checkout, product read, batch job.
- Keep the timing output structured (method name + duration) so it can feed the
  benchmark later.

**I test:** Hit checkout and product endpoints; timing log lines appear with
sensible durations.
**Confirm → commit:** `✅ Step 1 — AOP performance-monitoring aspect`

---

### [x] Step 2 — Concurrency control: optimistic vs pessimistic (Req 7)

**Goal:** Apply and compare both locking strategies on inventory updates.

- Optimistic (`@Version`) already exists from Phase 1 — keep it as the default.
- Add a pessimistic path: a repository query with
  `@Lock(LockModeType.PESSIMISTIC_WRITE)` (`SELECT ... FOR UPDATE`).
- Make it selectable (flag/profile) so both can be demonstrated.
- `docs/architecture-notes.md`: document the trade-off — optimistic wins under
  low contention (no lock waiting, but retries on conflict); pessimistic wins
  under high contention (no wasted retries, but holds DB locks).

**I test:** Both paths place orders correctly; the write-up explains the trade-off.
**Confirm → commit:** `✅ Step 2 — Concurrency control: optimistic vs pessimistic (Req 7)`

---

### [x] Step 3 — ACID transaction integrity demonstration (Req 8)

**Goal:** Prove the composite operation is all-or-nothing.

- The composite (payment + inventory decrement + order create) is already in one
  `@Transactional` boundary from Phase 1.
- Add a failure-injection test: force an exception AFTER stock is decremented but
  BEFORE the order completes.
- Assert: stock is restored (rolled back) and no order/order-items exist.
- Repeat the failure case under concurrent access to show integrity holds under load.
- Save the demonstration output to `docs/req8-acid/`.

**I test:** Run the injected-failure test; DB is unchanged — nothing half-applied,
even under concurrency.
**Confirm → commit:** `✅ Step 3 — ACID transaction integrity demonstration (Req 8)`

---

### [x] Step 4 — Stress-test harness (Req 9)

**Goal:** Prove 100+ concurrent users are served with no crash and no data loss.

- Add a `k6` script (preferred — version-controlled) or JMeter plan.
- **Read-heavy profile**: mostly product browsing, a realistic fraction of
  checkouts (so caching will later show a clear win).
- Ramp to 100+ virtual users.
- After the run, assert data integrity: no negative stock, no orphaned orders,
  order totals consistent.
- Save scripts under `loadtest/` and results under `docs/stress/`.

**I test:** Run the load test to 100+ users; system stays up; integrity asserts pass.
**Confirm → commit:** `✅ Step 4 — Stress-test harness (Req 9)`

---

### [x] Step 5 — Baseline benchmark + bottleneck identification (Req 10a)

**Goal:** Capture the "before" numbers and name the bottleneck.

- Run the Step 4 load test with **no caching** (current state).
- Collect from AOP timings + load tool: p50/p95 latency for product reads and
  checkout, throughput, and DB query count/time.
- Identify the bottleneck (expected: repeated product DB reads under read-heavy load).
- Save the baseline as `docs/benchmark/baseline.md` (numbers + named bottleneck).

**I test:** Baseline numbers are captured and the bottleneck is clearly identified.
**Confirm → commit:** `✅ Step 5 — Baseline benchmark + bottleneck identification (Req 10a)`

---

### [x] Step 6 — Distributed caching with Redis (Req 6 — the fix)

**Goal:** Cache hot products to reduce direct DB queries.

- Add Redis to `docker-compose.yml`; add Spring Data Redis.
- Cache product reads with `@Cacheable`; invalidate with `@CacheEvict` on product
  / stock changes.
- Choose and justify a TTL and eviction policy; document the invalidation strategy
  (especially how the cache stays correct when stock changes).
- This is introduced specifically as the fix for the Step 5 bottleneck.

**I test:** Hot product reads are served from cache; after a stock change the cache
reflects it correctly (no stale stock).
**Confirm → commit:** `✅ Step 6 — Distributed caching with Redis (Req 6)`

---

### [x] Step 7 — Re-benchmark + before/after report (Req 10b)

**Goal:** Prove the improvement with numbers.

- Re-run the identical Step 4 load test **with caching enabled**.
- Produce `docs/benchmark/comparison.md`: a before/after table —
  p50/p95 latency, throughput, DB query count — baseline vs cached.
- Write 2–3 sentences interpreting the result (what improved and why).

**I test:** The before/after table shows a measurable improvement and reads clearly.
**Confirm → commit:** `✅ Step 7 — Re-benchmark + before/after report (Req 10b)`

---

### [x] Step 8 — Final documentation pass

**Goal:** Tie Phase 1 + Phase 2 together for the final evaluation.

- `docs/architecture-notes.md`: finalize the full architecture (AOP, locking
  strategies, ACID boundary, caching, all justification numbers in one place).
- Confirm synchronization-point comments are present and clear in the code.
- `README`: how to run everything (DB, Redis, RabbitMQ, app, Nginx, load test).
- Make sure the AOP write-up and the before/after report are easy to find.

**I test:** Fresh read-through — a reviewer can run and understand the whole
system (both phases) from the docs alone.
**Confirm → commit:** `✅ Step 8 — Final documentation pass`

---

## Definition of Done for Phase 2

- [x] Requirements 6–10 implemented and individually demonstrable.
- [x] AOP performance-monitoring aspect in place (delivery req a).
- [x] Optimistic vs pessimistic trade-off documented (Req 7).
- [x] ACID rollback demonstrated under concurrency (Req 8).
- [x] Stress test proves 100+ users, no data loss (Req 9).
- [x] Before/after benchmark table with a named bottleneck (Req 10).
- [x] Redis caching shown as the measured fix (Req 6).
- [x] Each step committed by me, message `✅ <step name>`, no AI authorship.
- [x] Everything runs from `docker compose` for the local→server move.
