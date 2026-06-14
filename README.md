# Aegis — a Reliability Layer for AI-powered backends

A small, deliberately focused Java service that demonstrates the patterns a
**Backend Reliability** team owns, applied to a realistic problem: protecting an
**AI-powered work-item triage endpoint** from the unpredictable, bursty traffic
that AI agents generate.

It is built **spec-first** (`SPEC.md`) and **agent-assisted** (`PROMPTS.md`), so
it shows both halves of "AI at work": building *with* AI, and building features
that *use* AI — guarded by real reliability engineering.

```
client / AI agent
      │
      ▼
 ┌─────────────────────────────────────────────┐
 │  RateLimitFilter   (Redis token bucket, Lua) │  R1
 └─────────────────────────────────────────────┘
      │
      ▼
 ┌──────────────┐   cache-aside     ┌────────────┐
 │ WorkItem API │ ────────────────▶ │   Redis    │  R4
 │              │ ◀──── miss ─────  └────────────┘
 │              │ ───────────────────────────────▶ Postgres (HikariCP)  R5
 │              │
 │   Triage     │  @CircuitBreaker @Retry @Bulkhead @TimeLimiter   R2,R3
 │              │ ───────────────────────────────▶ LLM (Anthropic)
 │              │ ◀── fallback (graceful degrade) ─┘
 └──────────────┘
      │ Micrometer → /actuator/prometheus → Prometheus → Grafana    R6
      │                                      (RED dashboard)
```

## Run it

Requirements: Docker, JDK 21, Maven.

```bash
# 1. Start Redis + Postgres + Prometheus + Grafana
docker compose up -d

# 2. Run the app (Flyway applies V1/V2/V3 migrations on startup)
mvn spring-boot:run

# (optional) enable the real LLM; without this it uses the fallback path
export ANTHROPIC_API_KEY=sk-...
```

Then open the monitoring stack:

- **Grafana** — http://localhost:3000 (login `admin` / `admin`, or override via
  `GRAFANA_ADMIN_PASSWORD`). The **"Aegis — RED Metrics"** dashboard is
  auto-provisioned: Rate, Errors, Duration, plus triage source (llm vs fallback)
  and circuit-breaker state.
- **Prometheus** — http://localhost:9090/targets (the `aegis` target should be `UP`).

Generate some load (the demo script below), then watch the panels move.

```bash
# run the unit + slice tests (no Docker required for these)
mvn test
```

## Demo script (this is also your live-coding talking track)

```bash
# A cached read (first call hits Postgres, second is served from Redis)
curl -s localhost:8080/api/v1/work-items/1 | jq

# AI triage. Without an API key this returns source:"fallback" (graceful degrade)
curl -s -X POST localhost:8080/api/v1/work-items/1/triage \
     -H 'X-Tenant-Id: tenant-42' | jq

# Idempotency: same key never re-calls the LLM
curl -s -X POST localhost:8080/api/v1/work-items/1/triage \
     -H 'X-Tenant-Id: tenant-42' -H 'Idempotency-Key: abc-123' | jq

# Trip the rate limiter: fire 40 quick requests, watch for HTTP 429
for i in $(seq 1 40); do
  curl -s -o /dev/null -w "%{http_code} " -X POST \
    localhost:8080/api/v1/work-items/1/triage -H 'X-Tenant-Id: tenant-42'
done; echo

# Watch the circuit breaker + RED metrics
curl -s localhost:8080/actuator/prometheus | grep -E 'aegis_triage|resilience4j'
```

## What each piece proves (design talking points)

**Rate limiter (`ratelimit/`)** — token bucket in an atomic Redis Lua script.
Be ready to say *why Lua*: the refill+consume must be atomic or two callers race
on the last token. Be ready to compare token bucket vs fixed/sliding window, and
to justify fail-open vs fail-closed.

**Circuit breaker + retry + bulkhead (`ai/TriageService`)** — the breaker stops a
slow LLM from exhausting threads (cascading failure); retry uses exponential
backoff **with jitter** so retries don't synchronize; the bulkhead caps the blast
radius. Retry is only safe because triage is idempotent — say that out loud.

**Cache-aside + stampede protection (`cache/`)** — single-flight lock so only one
caller recomputes on a miss, plus TTL jitter so keys don't expire in lockstep.
Name the failure mode: "thundering herd / cache stampede".

**Durable idempotency (`idempotency/`)** — replays of an `Idempotency-Key` are
served from Redis with a TTL, so an agent's retry never re-calls the LLM, even
across a restart or a different instance. Say why retries need this: agent
traffic is retry-happy, and an in-memory map loses that guarantee on restart.

**Postgres (`workitem/`, `db/migration/`)** — explicit SQL via JdbcTemplate,
composite index on `(status, priority)`, bounded HikariCP pool as a reliability
control. Be ready to run `EXPLAIN ANALYZE` and read the plan.

**Migrations (Flyway)** — schema migration tooling is core backend reliability work.
`V2`/`V3` demonstrate a **zero-downtime column add**: add nullable + backfill
(`V2`), then enforce `DEFAULT` + `NOT NULL` in a later deploy (`V3`). Never add a
`NOT NULL` column in one shot under a rolling deploy.

**Observability (`monitoring/`)** — RED metrics (Rate, Errors, Duration) at
`/actuator/prometheus`, scraped by Prometheus and shown in an auto-provisioned
Grafana dashboard that also charts triage source (llm vs fallback) and
circuit-breaker state — degradation you can *see*, not just assert.

**Graceful degradation** — no API key, or a tripped breaker, still returns a
useful heuristic triage instead of a 500.

**Tests (`src/test/`)** — fast, dependency-free unit tests (Redis/LLM mocked) for
the limiter, idempotency store, cache and triage heuristic, plus a `@WebMvcTest`
slice for the controller. `mvn test` is green without Docker.

## How to extend (good "what would you do next" answers)

- Add multi-window burn-rate alerting on the error-budget for the triage SLO.
- Replace the direct LLM client with an MCP (Model Context Protocol) client.
- Close the idempotency race (two concurrent first-calls) with a short Redis lock.
- Promote the Lua rate-limiter logic to an integration test against a real Redis
  (Testcontainers) to cover the refill/atomicity the unit tests deliberately skip.

## Map to backend reliability skills

| Skill | Where it lives |
|---|---|
| HTTP rate limiters | `ratelimit/RateLimiter`, `token_bucket.lua` |
| Circuit breakers | `ai/TriageService` + `application.yml` |
| Distributed Redis caching | `cache/CacheAsideService`, `idempotency/IdempotencyStore` |
| DB schema migration tools | Flyway `db/migration/` (`V1`–`V3`, zero-downtime) |
| Optimize PostgreSQL | `workitem/`, indexes, HikariCP |
| AI tooling / coding agents | `SPEC.md`, `PROMPTS.md` |
| Observability | Micrometer / Prometheus / Grafana RED dashboard (`monitoring/`) |
| How systems fail & recover | breaker, fallback, stampede protection, durable idempotency |
