# ShopCore — High-Performance E-Commerce Backend

**Parallel Programming Course Project — Spring 2026**

A Spring Boot backend that demonstrates five parallel-programming concepts in a realistic e-commerce setting.

## Live Demo

Swagger UI (deployed on VPS): **https://shopcore.duckdns.org/swagger-ui/index.html**

## What's inside

| Requirement | Implementation |
|---|---|
| Concurrent access & data integrity | JPA optimistic locking (`@Version`) + bounded retry on `OptimisticLockException` — prevents oversell under 50 concurrent threads |
| Resource management & capacity control | Tuned Tomcat thread pool, HikariCP DB pool, and a bounded `ThreadPoolTaskExecutor` with explicit rejection policy |
| Asynchronous queues | RabbitMQ — invoice generation and notifications are published after commit and processed off the request path |
| Batch processing | Spring Batch nightly job that reads completed orders, aggregates per-product sales, and writes to a `daily_sales_summary` table in chunks of 100 |
| Load distribution | Two app instances behind Nginx round-robin reverse proxy; each response carries an `instanceId` to show which instance served the request |

## Stack

- **Java 17 / Spring Boot 3.x** — Web, Data JPA, AMQP, Batch, Validation
- **PostgreSQL 16** — persistence
- **RabbitMQ 3** — async messaging
- **Nginx** — reverse proxy / load balancer
- **Docker Compose** — single-command local setup

## Run locally

```bash
# 1. Start all services
docker compose up --build -d

# 2. API is available at
http://localhost:8005
# Swagger UI
http://localhost:8005/swagger-ui/index.html
```

All configuration numbers (pool sizes, timeouts, thread counts) are justified in [docs/architecture-notes.md](docs/architecture-notes.md).

## Concurrency proof

`docs/req1-proof/` contains output from a 50-thread race against a single-unit product:

- `without-lock.txt` — stock goes negative (oversell demonstrated)
- `with-lock.txt` — exactly one order succeeds, stock ends at 0
