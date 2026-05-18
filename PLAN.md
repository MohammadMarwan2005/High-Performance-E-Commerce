# PLAN.md — High-Performance E-Commerce Backend Engine (Phase 1)

> Parallel Programming Course Project — Spring 2026
> **Stack:** Spring Boot 3.x (Java 17+) · PostgreSQL · RabbitMQ · Nginx · Docker Compose
> **Scope of this file:** Phase 1 only — Requirements 1 through 5.

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

## Scoping note Claude must keep in mind (state this if I ask)

Requirement 1 (race condition) **cannot be demonstrated** without a `@Version`
field (optimistic locking) and `@Transactional` (ACID). Those are officially
Phase 2 items (Req 7 & 8), but Req 1 depends on them, so we build a **minimal**
version now and expand the written discussion in Phase 2. This dependency is
intentional and should be called out, not hidden.

---

## Requirements covered in Phase 1

| # | Requirement | Built in step(s) |
|---|-------------|------------------|
| 1 | Concurrent Access & Data Integrity (race condition) | 2, 3 |
| 2 | Resource Management & Capacity Control (pools) | 4 |
| 3 | Asynchronous Queues (invoice/notification off main path) | 5 |
| 4 | Batch Processing (nightly chunked sales job) | 6 |
| 5 | Load Distribution (multi-instance + Nginx) | 7 |

---

## STEPS

### [x] Step 1 — Project skeleton + persistence

**Goal:** A running Spring Boot app with PostgreSQL and the core entities.

- Spring Boot 3.x project: Web, Data JPA, PostgreSQL driver, Validation, Lombok (optional).
- `docker-compose.yml` with a PostgreSQL service.
- Entities:
  - `User` (id, username, password — plain/basic for now).
  - `Product` (id, name, price, `stockQuantity`, **`@Version Long version`**).
  - `Order` (id, user, createdAt, status, total).
  - `OrderItem` (id, order, product, quantity, unitPrice).
- Endpoints: create product, list products, register user.
- `application.yml` with DB config; app boots cleanly.

**I test:** `docker compose up` for DB, run the app, create a product, list it.
**Confirm → commit:** `✅ Step 1 — Project skeleton + persistence`

---

### [x] Step 2 — Checkout service with optimistic locking (Req 1 core)

**Goal:** A correct single-order checkout that decrements stock safely.

- `POST /orders` endpoint → `OrderService.placeOrder(...)`.
- Method is `@Transactional`.
- Stock decrement relies on `Product.@Version` (JPA optimistic locking).
- On `OptimisticLockException` / `ObjectOptimisticLockingFailureException`:
  bounded retry (max 3 attempts), then fail the order cleanly with a clear error.
- Reject orders that exceed available stock (no negative stock ever).
- Code comments mark the **synchronization point** (where the version check matters).

**I test:** Place a normal order; stock decrements; over-quantity order is rejected.
**Confirm → commit:** `✅ Step 2 — Checkout service with optimistic locking (Req 1 core)`

---

### [x] Step 3 — Concurrency proof harness (Req 1 evidence — graded heavily)

**Goal:** Demonstrate the race condition AND its fix with numbers.

- A test/util that launches ~50 concurrent threads, all ordering the **same**
  product that has `stockQuantity = 1`.
- A toggle (flag or two profiles) to run **without** locking and **with** locking.
- Output a clear summary each run: successful orders, final stock, oversell? (yes/no).
- Save both result outputs into `docs/req1-proof/` (e.g. `without-lock.txt`,
  `with-lock.txt`) — this is the interview evidence.

**I test:** Run both modes; without-lock shows oversell, with-lock shows exactly
one success and stock = 0.
**Confirm → commit:** `✅ Step 3 — Concurrency proof harness (Req 1 evidence)`

---

### [x] Step 4 — Resource management & capacity control (Req 2)

**Goal:** Bound parallelism so the system stays stable.

- Configure Tomcat thread pool (`server.tomcat.threads.max`, accept count).
- Configure HikariCP (`spring.datasource.hikari.maximum-pool-size`, timeout).
- Define a bounded `ThreadPoolTaskExecutor` (core, max, queue capacity,
  rejection policy) for app-managed async work.
- `docs/architecture-notes.md`: write the **justification** for every number
  (why DB pool < thread pool, what happens when the queue is full, why no
  unbounded queue).

**I test:** App runs with new settings; justification doc reads clearly.
**Confirm → commit:** `✅ Step 4 — Resource management & capacity control (Req 2)`

---

### [x] Step 5 — Asynchronous queues (Req 3)

**Goal:** Invoice + notification happen off the request path.

- Add RabbitMQ service to `docker-compose.yml`; add Spring AMQP.
- After the order transaction **commits**, publish an `OrderPlaced` message,
  then return the HTTP response immediately.
- Two consumers: (a) invoice generation, (b) notification (logged/mock email).
- Publish-after-commit so a rolled-back order never produces an invoice.

**I test:** Place an order — response returns fast; consumer logs for invoice
and notification appear a moment later.
**Confirm → commit:** `✅ Step 5 — Asynchronous queues (Req 3)`

---

### [x] Step 6 — Batch processing (Req 4)

**Goal:** Nightly chunked daily-sales summary.

- Add Spring Batch.
- Job: Reader = day's orders → Processor = aggregate per product →
  Writer = `daily_sales_summary` table. Chunk size ~100.
- `@Scheduled` trigger (short interval for demo; note cron as the prod setting).

**I test:** Trigger the job; chunk commits appear in logs; summary table is
populated correctly.
**Confirm → commit:** `✅ Step 6 — Batch processing (Req 4)`

---

### [ ] Step 7 — Load distribution (Req 5)

**Goal:** Simulate multi-server distribution with a justified strategy.

- Run two app instances on different ports (e.g. via Docker Compose scaling
  or two services).
- Add an `instanceId` to responses (and/or distinct logs) to show which
  instance served a request.
- Nginx reverse proxy in front, round-robin (or least-connections).
- `docs/architecture-notes.md`: justify the algorithm, why instances must be
  stateless, and how this links to Req 2.

**I test:** Repeated requests alternate across the two instances via Nginx.
**Confirm → commit:** `✅ Step 7 — Load distribution (Req 5)`

---

### [ ] Step 8 — Phase 1 documentation pass

**Goal:** Tie it together for the milestone interview.

- `docs/architecture-notes.md`: finalize — overall design, the Req-1
  dependency on locking/transactions, all justification numbers in one place.
- Ensure synchronization-point comments are present and clear in the code.
- Short `README` section: how to run everything (DB, RabbitMQ, app, Nginx).

**I test:** Fresh read-through — a reviewer could run and understand the system
from the docs alone.
**Confirm → commit:** `✅ Step 8 — Phase 1 documentation pass`

---

## Definition of Done for Phase 1

- [ ] Requirements 1–5 implemented and individually demonstrable.
- [ ] Req 1 proof (without-lock vs with-lock) saved as evidence.
- [ ] Every configuration number has a written justification.
- [ ] Each step committed by me, message `✅ <step name>`, no AI authorship.
- [ ] Everything runs from `docker compose` for the local→server move.
