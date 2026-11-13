# Camunda 8 Asset Aggregation Pipeline: Design Spec

Date: 2026-11-12
Status: draft, pending author review

## 1. Purpose and success criteria

A portfolio project that shows backend orchestration skills with Camunda 8 to European tech leads and recruiters. It also serves as hands-on practice for the Camunda 8 Developer certification.

The service aggregates market data for a list of stock symbols from several public providers, in parallel, and exposes the consolidated result through a REST API. A Camunda 8 (Zeebe) process orchestrates the work.

Success means:
- A reviewer clones the repository, runs `docker compose up`, and completes the `curl` demo from the README without creating any external account beyond free API keys.
- The BPMN process visibly handles parallelism, retries, timeouts, business errors and partial results in Operate.
- Unit and integration tests pass in GitHub Actions without real API keys or internet access.
- The README shows the architecture and the BPMN diagram and explains the main decisions.

## 2. Scope

In scope (first version):
- Java / Spring Boot application containing the REST API and the Zeebe job workers.
- Camunda 8 self-managed via Docker Compose (Zeebe and Operate).
- Redis for raw provider payloads and consolidated results.
- Three market data providers for the US and European markets: Finnhub, Alpha Vantage, Twelve Data.
- Unit tests, integration tests with Testcontainers and WireMock, and a GitHub Actions pipeline.

Out of scope (possible later versions):
- Observability stack (Prometheus, Grafana), Kubernetes deployment.
- Authentication, UI, durable database storage.
- Integration with the AnalisaInvest project.
- Brazilian market (B3) providers.

## 3. Architecture

One Spring Boot application with ports-and-adapters structure. Dependencies point inward only.

- **domain**: pure types (`Asset`, `Quote`, `Fundamentals`, `AggregatedAsset`) and ports: `MarketDataProvider`, `ResultStore`, `WorkflowStarter`. No Zeebe, Redis or HTTP imports.
- **application**: use cases `StartAggregation`, `GetAggregation`, `ConsolidateResults`.
- **adapters in**: REST controller; Zeebe job workers. Workers are thin: read job variables, call a use case, complete the job, fail it, or throw a BPMN error.
- **adapters out**: one client per provider; Redis adapter implementing `ResultStore`; Zeebe adapter implementing `WorkflowStarter`.

BPMN decides when each step runs. Code decides what each step does. Replacing a provider or the store means writing one new adapter.

## 4. Process `aggregate-assets`

1. Start: the REST adapter creates a process instance with `requestId` and `symbols`.
2. A parallel gateway splits into three branches, one per provider. Each branch is a service task handled by a job worker.
3. Transient failures (network timeout, HTTP 429, HTTP 5xx): the worker fails the job and Zeebe retries with a configured retry count and backoff.
4. Non-retryable business failures (invalid API key, unknown symbol): the worker throws a BPMN error, caught by a boundary error event that marks the branch as failed.
5. A boundary timer event on each branch cuts off a slow provider and continues without it.
6. The parallel join leads to a single `consolidate-and-store` service task that merges and normalizes the provider responses and stores the consolidated result in Redis with a TTL, so the full result never passes through process variables.
7. An exclusive gateway ends the process normally, or with an error end event when the status is `FAILED`.

Result policy:
- At least one provider answered with all branches ok: `COMPLETED`.
- At least one provider answered but some branches failed or timed out: `DEGRADED`.
- No provider answered: the process ends with an error and the status is `FAILED`.

Consolidation rule (assumption, adjustable): the price per symbol is the median of the available providers' prices. Fundamentals come from the first available provider in a fixed priority order. The response lists the status of each provider.

Process variables carry references only (`requestId`, provider outcomes). Raw payloads live in Redis, not in Zeebe.

## 5. REST API

- `POST /aggregations` with `{"symbols": ["AAPL", "SAP"]}`. Returns `202` with `{"requestId": "<id>"}`. Limits: 1 to 10 symbols (free-tier friendly). Invalid input returns `400`.
- `GET /aggregations/{requestId}` returns `{status, symbols, providers, result}`. `status` is `PENDING`, `COMPLETED`, `DEGRADED` or `FAILED`. A pending request returns `200`. An unknown or expired id returns `404`.

## 6. Redis data

- `agg:{requestId}:raw:{provider}`: raw provider payload, with TTL.
- `agg:{requestId}:result`: consolidated result and status, with TTL.

## 7. Infrastructure and configuration

- `docker-compose.yml` starts Camunda 8 self-managed (Zeebe, Operate), Redis and the app.
- API keys come from environment variables. The repository contains `.env.example`; `.env` is git-ignored.
- Exact Camunda 8, Spring Boot and Java versions are pinned in the implementation plan after checking the official documentation and the official compose files at that time.

## 8. Testing

- Unit tests: use cases with fake ports, no Spring context.
- Integration tests: Testcontainers (Zeebe, Redis) with WireMock standing in for the providers.
- Process scenarios: all providers ok; one provider times out (`DEGRADED`); one provider returns a business error (`DEGRADED`); all providers fail (`FAILED`).
- CI: GitHub Actions runs build and tests on every push; a status badge appears in the README.

## 9. Repository layout (target)

```
camunda8-pipeline/
  docker-compose.yml
  .env.example
  README.md
  docs/            design spec, plan, diagrams
  src/main/java/   domain, application, adapters
  src/main/resources/bpmn/aggregate-assets.bpmn
  src/test/java/
  .github/workflows/ci.yml
```

## 10. Risks

- Free-tier rate limits and differing response schemas across providers: mitigated by one adapter per provider and WireMock-based tests.
- Camunda 8 client and Zeebe APIs change between minor versions: mitigated by pinning versions and following the official docs at implementation time.
- Scope growth: observability and Kubernetes are explicitly deferred.
