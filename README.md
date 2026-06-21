# ShopCore — High-Performance E-Commerce Backend

**Parallel Programming Course Project — Spring 2026**

A Spring Boot backend that demonstrates parallel-programming and high-throughput
concepts in a realistic e-commerce setting, across two phases.

## Live Demo

Swagger UI (deployed on VPS): **https://shopcore.duckdns.org/swagger-ui/index.html**

## What's inside

### Phase 1 — concurrency, capacity, distribution

| Requirement | Implementation |
|---|---|
| Concurrent access & data integrity | JPA optimistic locking (`@Version`) + bounded retry — prevents oversell under 50 concurrent threads |
| Resource management & capacity control | Tuned Tomcat thread pool, HikariCP DB pool, and a bounded `ThreadPoolTaskExecutor` with explicit rejection policy |
| Asynchronous queues | RabbitMQ — invoice + notifications published after commit, processed off the request path |
| Batch processing | Spring Batch nightly job aggregating per-product sales in chunks of 100 |
| Load distribution | Two app instances behind Nginx round-robin; each response carries `X-Instance-Id` |

### Phase 2 — measurement, locking comparison, caching

| Requirement | Implementation | Where |
|---|---|---|
| (a) AOP performance monitoring | `@Timed` + `TimingAspect` (`@Around`), structured `PERF.TIMING` logs | [architecture-notes §10](docs/architecture-notes.md#10-delivery-requirement-a--aop-performance-monitoring) |
| 7 — Concurrency control | Optimistic vs **pessimistic** (`SELECT … FOR UPDATE`), selectable at runtime | [architecture-notes §8](docs/architecture-notes.md) · `docs/req7-locking/` |
| 8 — Transaction integrity (ACID) | Failure injected mid-transaction; rollback proven under concurrency | [architecture-notes §9](docs/architecture-notes.md) · `docs/req8-acid/` |
| 9 — Stress testing | k6, 120 virtual users, integrity assertions after the run | `loadtest/` · `docs/stress/` |
| 6 — Distributed caching (Redis) | `@Cacheable`/`@CacheEvict` on product reads; two consistency models | [architecture-notes §11](docs/architecture-notes.md#11-requirement-6--distributed-caching-redis) |
| 10 — Benchmarking & bottleneck | Before/after: **−95% DB load, −40% latency, 98.2% cache hit** | **[docs/benchmark/comparison.md](docs/benchmark/comparison.md)** |

## Stack

- **Java 17 / Spring Boot 4.0** — Web MVC, Data JPA, AMQP, Batch, Validation, Cache
- **PostgreSQL 16** — persistence
- **Redis 7** — distributed cache (Req 6)
- **RabbitMQ 3** — async messaging
- **Nginx** — reverse proxy / load balancer
- **k6** — load / stress testing (`loadtest/stress.js`)
- **Docker Compose** — single-command setup

## Run locally

Infrastructure (Postgres, Redis, RabbitMQ, Adminer) has no Compose profile and
starts by default. The load-balanced app instances + Nginx are behind the `prod`
profile.

```bash
# A. Infrastructure only — then run the app natively for development
docker compose up -d                     # postgres + redis + rabbitmq + adminer
./mvnw spring-boot:run                    # app on http://localhost:8080

# B. Full stack: 2 app instances behind Nginx (load-balanced)
docker compose --profile prod up -d --build
# entry point (Nginx)         → http://localhost:8005
# Swagger UI                  → http://localhost:8005/swagger-ui/index.html
```

Seed a catalog for browsing / load testing:

```bash
curl -X POST "http://localhost:8005/admin/products/seed?count=5000"
```

All configuration numbers (pool sizes, timeouts, cache TTLs, eviction policy) are
justified in **[docs/architecture-notes.md](docs/architecture-notes.md)**.

## Run the load test (Req 9) and benchmark (Req 10)

The k6 profile is read-heavy (80% browse / 15% by-id / 5% checkout), ramping to 120
virtual users:

```bash
docker run --rm --network host \
  -e BASE_URL=http://localhost:8005 -e PEAK_VUS=120 \
  -v "$PWD/loadtest:/ld:ro" grafana/k6 run /ld/stress.js
```

Database load for the before/after benchmark is read from Hibernate's statistics
via two admin endpoints, then asserted for integrity:

```bash
curl -X POST http://localhost:8005/admin/db-stats/reset    # zero counters
# … run the load test …
curl http://localhost:8005/admin/db-stats                  # prepareStatementCount, entityLoadCount, …
docker exec -i ecommerce-postgres psql -U postgres -d ecommerce < loadtest/integrity_check.sql
```

> For a clean DB-load measurement the benchmark targets a **single** instance (so
> all Hibernate counters land in one JVM) rather than the two load-balanced ones.
> Full method and results: **[docs/benchmark/](docs/benchmark/)**.

## Proofs & evidence

- **Concurrency (Req 1):** `docs/req1-proof/` — 50-thread race; `without-lock.txt`
  oversells, `with-lock.txt` ends at stock 0 with exactly one winner.
- **Locking comparison (Req 7):** `docs/req7-locking/optimistic-vs-pessimistic.txt`.
- **ACID rollback (Req 8):** `docs/req8-acid/acid-rollback.txt`.
- **Stress (Req 9):** `docs/stress/`.
- **Benchmark before/after (Req 10):** `docs/benchmark/baseline.md` + `comparison.md`.
