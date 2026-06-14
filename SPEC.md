# SPEC — Aegis Reliability Layer

> This is the **source of truth** the implementation was built from. In a
> spec-driven workflow you write this first, review it with the team (or an AI
> agent), and only then generate code. The spec — not the code — is what you
> defend in a design review. Code is downstream of an agreed spec.

**The loop this repo follows.** Every change — v1 and v2 alike — went through the
same cycle, and the artifacts for each stage are in the repo:

1. **Specify** — add or amend a requirement here in `SPEC.md` (the contract).
2. **Prompt** — drive the build with a versioned prompt that cites that
   requirement (`PROMPTS.md`).
3. **Generate & review** — produce the code, reject anything that violates a
   trade-off in §5.
4. **Verify** — prove the requirement against the acceptance checklist (§6) and
   an automated test. The spec→code→test links are listed in §7 (Traceability).

If a requirement has no test in §7, it is not "done" — it is a claim.

## 1. Problem

Modern products are shipping AI agents that call backend services on behalf of
users. Agent traffic is **machine-generated**: bursty, retry-happy, and
unpredictable, unlike human click traffic. A single misbehaving agent can stampede a downstream
service or the database. We need a thin **reliability layer** in front of an
AI-powered endpoint that stays healthy regardless of how the agent or the LLM
behaves.

## 2. Goals / Non-goals

**Goals**
- Protect a backend AI endpoint so it never becomes the cause of an incident.
- Demonstrate the core reliability patterns end to end: rate limiting, circuit
  breaking, distributed caching, graceful degradation, idempotency, observability.
- Run fully locally with one command; no secrets required.

**Non-goals**
- Not a production gateway (no auth, no TLS termination, no multi-region).
- Not an LLM framework. The LLM is just the "unreliable downstream".

## 3. Functional requirements

| ID | Requirement |
|----|-------------|
| F1 | `GET /api/v1/work-items/{id}` returns a work item, served via cache-aside. |
| F2 | `POST /api/v1/work-items/{id}/triage` returns an AI-generated triage (risk + next action). |
| F3 | Triage honours an `Idempotency-Key` header: same key never re-calls the LLM. The store is durable (Redis + TTL), so a replay works across restarts and instances. |
| F4 | When the LLM is unavailable, F2 returns a heuristic fallback, not an error. |

## 4. Reliability requirements (the actual point)

| ID | Requirement | Mechanism |
|----|-------------|-----------|
| R1 | Per-tenant request rate is bounded; bursts allowed, sustained rate capped. | Redis token bucket (atomic Lua) |
| R2 | A slow/failing LLM must not exhaust threads or cascade. | Resilience4j circuit breaker + bulkhead + time limiter |
| R3 | Transient LLM errors are retried without synchronized stampede. | Retry with exponential backoff + jitter |
| R4 | A hot cache key expiring must not stampede Postgres. | Single-flight lock + TTL jitter |
| R5 | The DB is protected from connection storms. | Bounded HikariCP pool, fail-fast timeout |
| R6 | Everything is measurable (Rate, Errors, Duration). | Micrometer → Prometheus → Grafana RED dashboard |
| R7 | Idempotent retries stay safe across restarts and instances. | Redis-backed idempotency store with a bounded TTL |

## 5. Key design decisions (and trade-offs)

- **Token bucket over sliding window**: allows legitimate bursts; simpler to
  reason about; O(1) state per key. Trade-off: slightly less precise than a
  sliding-window log.
- **Rate limiter fails OPEN**: if Redis is down we allow traffic rather than
  turn the limiter into the outage. A payments system might choose fail-CLOSED.
  State the trade-off explicitly.
- **Cache-aside over write-through**: source of truth stays in Postgres; cache
  is disposable. Accepts brief staleness (bounded by TTL) for big read savings.
- **JdbcTemplate over JPA**: SQL is explicit so index usage and `EXPLAIN ANALYZE`
  are first-class, not hidden behind an ORM.
- **Idempotency in Redis, not in-memory**: durable across restarts and shared
  across instances, with a TTL bounding the store. Trade-off: one network hop
  per lookup instead of a process-local map; worth it for correctness under
  retries and horizontal scaling.
- **Safe schema change in two migrations**: add the column nullable and backfill
  (`V2`), then enforce `DEFAULT` + `NOT NULL` in a later deploy (`V3`). Never add
  a `NOT NULL` column in one shot under a rolling deploy — the old version would
  insert rows that violate it.
- **Observability provisioned as code**: the Prometheus datasource and the RED
  dashboard are config files in the repo, so the monitoring is reproducible and
  versioned, not clicked together by hand.

## 6. Acceptance / demo checklist

- [ ] With no API key, triage returns `source: "fallback"` and the breaker opens
      after repeated calls.
- [ ] Hammering the endpoint past the bucket returns HTTP 429 with `Retry-After`.
- [ ] Repeating a triage with the same `Idempotency-Key` returns the stored
      result, and the replay still works after the app is restarted (Redis-backed).
- [ ] `/actuator/prometheus` exposes `aegis_triage_*` and HTTP timing metrics.
- [ ] Grafana auto-loads the "Aegis — RED Metrics" dashboard (Rate, Errors,
      Duration) plus triage source (llm vs fallback) and circuit-breaker state.
- [ ] `V2`/`V3` migrations demonstrate a zero-downtime column add (nullable →
      backfill → enforce `NOT NULL`).
- [ ] `mvn test` is green: unit tests for the limiter, idempotency store, cache
      and triage heuristic, plus a `@WebMvcTest` slice for the controller.

## 7. Traceability (spec → code → test)

Every requirement traces to the code that implements it and the test that proves
it. This is the spine of spec-driven development: the spec is not aspirational
prose, it is a contract each row is held to. Where a row is verified by config
review or a manual demo step rather than an automated test, it says so plainly —
hiding that gap would defeat the point.

| Req | Implemented in | Verified by |
|-----|----------------|-------------|
| F1 | `workitem/WorkItemController#getWorkItem`, `cache/CacheAsideService` | `CacheAsideServiceTest` (hit/miss) |
| F2 | `workitem/WorkItemController#triage`, `ai/TriageService` | `WorkItemControllerTest#computesAndStores...`, `TriageServiceTest#happyPath...` |
| F3 | `idempotency/IdempotencyStore`, `WorkItemController#triage` | `IdempotencyStoreTest`, `WorkItemControllerTest#replaysStoredResult...` |
| F4 | `ai/TriageService#fallbackTriage` | `TriageServiceTest#fallbackMaps...` (both branches) |
| R1 | `ratelimit/RateLimiter`, `scripts/token_bucket.lua`, `RateLimitFilter` | `RateLimiterTest` (reply parsing + fail-open). Lua atomicity: integration TODO |
| R2 | `ai/TriageService` (`@CircuitBreaker`/`@Bulkhead`/`@TimeLimiter`), `application.yml` | Fallback path: `TriageServiceTest`. Breaker transitions: manual demo (§6) |
| R3 | `application.yml` (`resilience4j.retry.llm`) | Config review; observable in retries during demo |
| R4 | `cache/CacheAsideService` (single-flight + TTL jitter) | `CacheAsideServiceTest#onMissLoadsFromSourceAndCaches` (lock taken + released) |
| R5 | `application.yml` (HikariCP `maximum-pool-size`, `connection-timeout`) | Config review |
| R6 | `application.yml` (metrics), `monitoring/` (Prometheus + Grafana) | Manual: Grafana RED dashboard renders (§6) |
| R7 | `idempotency/IdempotencyStore` (Redis + TTL) | `IdempotencyStoreTest` (round-trip, miss, corrupt-as-miss) |

> The honest gaps (R1 Lua atomicity, R2 breaker transitions, R3/R5 config-level)
> are exactly the "what would you test next" answers: promote them to
> Testcontainers-backed integration tests against a real Redis/Postgres.

## 8. Versioning

The spec is versioned; requirements are added here **before** the code that
satisfies them.

- **v1** — F1–F4, R1–R6. The core reliability layer: rate limiting, circuit
  breaking, cache-aside, graceful degradation, in-memory idempotency, RED metrics
  exposed at `/actuator/prometheus`.
- **v2** — hardening, specified as an increment to this same contract:
  - **R7 (new)** — idempotency moved from an in-memory map to a Redis-backed
    store with a TTL, so retries stay safe across restarts and instances.
  - **F3 (amended)** — now states the durability guarantee explicitly.
  - **R6 (extended)** — the metrics chain now ends in a provisioned Grafana RED
    dashboard, not just a raw Prometheus endpoint.
  - **Migrations** — `V2`/`V3` demonstrate a zero-downtime column add
    (nullable → backfill → enforce `NOT NULL`).
  - **Tests** — the acceptance checklist (§6) is backed by an automated suite
    and wired into the traceability matrix (§7).
