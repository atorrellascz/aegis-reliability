# PROMPTS — building Aegis with a coding agent

> Modern engineering work increasingly means "leveraging AI-powered tools and
> coding agents to accelerate development, analyze architectures, and automate
> repetitive or error-prone tasks." This file is the evidence that you do that
> **with method**: versioned, reviewable prompts tied to the spec — not ad-hoc
> one-liners.
>
> The discipline that matters: an agent amplifies your judgment, it does not
> replace it. You own the spec, the trade-offs, and the review. The agent does
> the typing.

## Workflow

1. Write or amend a requirement in `SPEC.md` (human-owned) — the contract.
2. Use the prompt below that **cites that requirement** to generate the component.
3. Review every diff. Reject anything that violates a trade-off in `SPEC.md` §5.
4. Verify against the acceptance checklist (`SPEC.md` §6) and add the spec→code→test
   link to the traceability matrix (`SPEC.md` §7).
5. Keep prompts versioned so the build is reproducible and auditable.

Each prompt below is labelled with the spec requirement it satisfies, so the
chain **spec → prompt → code → test** is traceable end to end. The prompts are
listed in build order, not requirement order.

---

## P1 — scaffold (architecture-level)

**Spec ref:** R1–R6 (overall skeleton) · **Verifies:** project compiles, wiring in place

```
You are helping build the project described in SPEC.md (attached).
Generate a Spring Boot 3 / Java 21 Maven project skeleton implementing
requirements R1–R6. Use Redis (Lettuce), Postgres (JdbcTemplate), Flyway,
Resilience4j, and Micrometer/Prometheus. Do NOT invent features outside the
spec. List the files you will create and why before writing them.
```

## P2 — the rate limiter (most error-prone piece → most prompt care)

**Spec ref:** R1 · **Verifies:** `RateLimiterTest` (reply parsing + fail-open)

```
Implement requirement R1: a distributed token-bucket rate limiter.
Constraints:
- The refill+consume decision MUST be atomic across instances. Use a single
  Redis Lua script; do not do read-then-write from Java.
- Allow bursts up to `capacity`, sustained `refillPerSec`.
- Expire idle buckets via TTL.
- Fail OPEN if Redis is unreachable (per the spec trade-off).
Return both `allowed` and `tokensRemaining`. Explain the race condition the Lua
script prevents in a comment.
```

## P3 — resilience around the LLM

**Spec ref:** R2, R3, F4 · **Verifies:** `TriageServiceTest` (fallback); breaker transitions via demo (§6)

```
Implement R2 and R3. Wrap the LLM call with Resilience4j: circuit breaker
(50% failure over a 10-call window, 10s open, half-open probes), retry (3
attempts, exponential backoff 200ms x2, with jitter), a 9s time limiter, and a
bulkhead capping concurrent calls. Provide a fallback method that returns a
deterministic heuristic triage so the endpoint degrades gracefully. Explain why
retry is only safe here (idempotent read).
```

## P4 — analysis / review prompt (using the agent to critique, not just write)

**Spec ref:** R4 · **Verifies:** `CacheAsideServiceTest` (hit/miss, lock taken + released)

```
Review my CacheAsideService against requirement R4. Specifically: does the
single-flight lock fully prevent a cache stampede, what happens if the loader
throws while holding the lock, and where could a thundering herd still occur?
Suggest the smallest change that closes the biggest remaining gap.
```

## P5 — incident-analysis prompt (the incident-response workflow)

**Spec ref:** none (process, not a feature) · maps to the incident-response workflow

```
Here is a Postgres slow-query log and the HikariCP pool metrics during an
incident [paste]. Act as an SRE doing a preliminary investigation: list the 3
most likely root causes ranked by probability, the evidence for each, and one
cheap experiment to confirm or rule out each. Do not propose a long-term fix —
that belongs to the owning team.
```

> P5 mirrors a backend reliability engineer's incident duties: *lead preliminary
> investigations, identify likely root causes, assess impact, propose
> mitigations — long-term fixes go to the owning team.* Using an agent this way
> is a strong signal.

---

# v2 — hardening prompts

> v2 closes the gaps the v1 README listed under "how to extend". Same discipline:
> each prompt cites the spec requirement it satisfies and states the trade-off,
> so the diff is reviewable against an agreed contract rather than vibes.

## P6 — durable idempotency (R7)

**Spec ref:** R7, F3 · **Verifies:** `IdempotencyStoreTest`, `WorkItemControllerTest` (replay)

```
Implement R7. Replace the in-memory idempotency map in WorkItemController with a
Redis-backed store (new IdempotencyStore component). Serialize TriageResult to
JSON, store under an "idem:" prefix with a configurable TTL (default 24h). A miss
or an unreadable/corrupt entry must be treated as "not found" and never break the
request path. Keep idempotency best-effort: if Redis write fails, log and proceed
(at worst one extra LLM call), do not fail the user request. State the residual
race (two concurrent first-calls) explicitly rather than hiding it.
```

## P7 — zero-downtime schema change

**Spec ref:** §5 (safe schema change), §6 checklist · **Verifies:** Flyway applies `V2`/`V3` clean on startup

```
Add a required `assignee` column to work_item without downtime under a rolling
deploy. Split it into two Flyway migrations: V2 adds the column NULLABLE and
backfills existing rows; V3 sets the DEFAULT and enforces NOT NULL once no nulls
remain. Explain in comments why doing this in one migration would break the old
app version still running mid-deploy. Update the WorkItem record and the
JdbcTemplate row mapper to read the new column.
```

## P8 — observability you can see (R6)

**Spec ref:** R6 · **Verifies:** Grafana RED dashboard renders, Prometheus target `UP` (§6)

```
Provision a Grafana + Prometheus stack via docker-compose for the existing
Micrometer metrics. Prometheus scrapes the app on the host via
host.docker.internal; Grafana auto-provisions the Prometheus datasource and a RED
dashboard (Rate, Errors, Duration) plus two reliability panels: triage source
(llm vs fallback) and circuit-breaker state. Pin image versions, keep Grafana
behind a login with credentials from the environment (not hardcoded), and enable
percentiles-histogram so histogram_quantile() can compute p95/p99.
```

## P9 — fast, honest tests

**Spec ref:** all of §7 (turns the matrix from claims into proof) · **Verifies:** `mvn test` green

```
Add a unit + slice test suite that runs without Docker. Mock Redis/LLM so tests
are fast and deterministic. Cover: rate-limiter reply parsing AND its fail-open
path; idempotency store JSON round-trip AND corrupt-entry-as-miss; cache-aside
hit (loader skipped) and miss (loads, caches, releases lock); the triage fallback
heuristic; and a @WebMvcTest for the controller proving an Idempotency-Key replay
never calls the LLM or the DB. Where a test deliberately skips coverage (e.g. the
Lua atomicity, which needs a real Redis), say so in a comment — don't imply more
coverage than exists.
```

> The honesty note in P9 matters: an agent will happily write tests that *look*
> thorough. Owning what a test does **not** cover is the reviewer's job, and a
> good prompt asks for it.
