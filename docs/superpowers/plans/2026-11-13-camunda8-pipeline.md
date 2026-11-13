# Camunda 8 Asset Aggregation Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Spring Boot service that aggregates stock data from three providers through a Camunda 8 process and serves the result over REST.

**Architecture:** One Spring Boot app in ports-and-adapters layout. The BPMN process `aggregate-assets` fans out to three provider job workers in parallel, then consolidates into Redis. REST starts a process instance and reads the result from Redis.

**Tech Stack:** Java 21, Maven, Spring Boot 3.5.x, Camunda 8.8.x (`camunda-spring-boot-starter`), Redis, JUnit 5, Testcontainers, WireMock, Docker Compose, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-11-12-camunda8-pipeline-design.md`

## Global Constraints

- Spec §3: `domain` imports no Zeebe, Redis, Spring or HTTP types. Dependencies point inward only.
- Spec §2: providers are Finnhub, Alpha Vantage, Twelve Data. Markets are US and Europe.
- Spec §5: 1 to 10 symbols per request. Invalid input returns `400`. `POST` returns `202`. Unknown or expired id returns `404`. Pending returns `200`.
- Spec §6: Redis keys `agg:{requestId}:raw:{provider}` and `agg:{requestId}:result`, both with TTL.
- Spec §4: process variables carry references only. Raw payloads live in Redis.
- Spec §7: API keys come from environment variables. `.env.example` is committed, `.env` is git-ignored.
- Spec §8: CI and tests need no real API key and no internet. WireMock replaces providers.
- Base package: `dev.assetpipeline`. Java 21 (JDK 21 and Maven 3.9 are installed on the author's machine).
- Spec §4 steps 6 and 7 (consolidate, then store) are implemented as one service task `consolidate-and-store`, so the full result never passes through Zeebe variables.

## Review Focus

- Lowercase or duplicate symbols in a request (`["aapl","AAPL"]`) are normalized and deduplicated before the 10-symbol limit is checked.
- A symbol that no provider returns is omitted from `assets`, and the other symbols still aggregate.
- Raw payload expired in Redis for a provider marked `OK` is treated as `FAILED` for that provider, not as a crash.
- Alpha Vantage rate-limit message returned with HTTP 200 and a JSON note body is classified as transient.
- Zeebe unreachable on `POST /aggregations` returns `503` and leaves no `PENDING` record behind.
- Malformed or non-JSON request body returns `400`, not `500`.

---

## File Structure

```
pom.xml
docker-compose.yml, .env.example, .gitignore, README.md
src/main/java/dev/assetpipeline/
  AssetPipelineApplication.java
  domain/        Quote, Fundamentals, ProviderId, ProviderOutcome, ProviderSnapshot,
                 AggregationStatus, AggregatedAsset, AggregationResult,
                 TransientProviderException, BusinessProviderException,
                 MarketDataProvider, ResultStore, WorkflowStarter
  application/   StartAggregation, GetAggregation, ConsolidateResults
  adapters/in/rest/    AggregationController, ApiExceptionHandler, request/response records
  adapters/in/worker/  ProviderWorkers, ConsolidateWorker
  adapters/out/provider/ FinnhubProvider, AlphaVantageProvider, TwelveDataProvider
  adapters/out/redis/  RedisResultStore
  adapters/out/zeebe/  ZeebeWorkflowStarter
src/main/resources/application.yaml, bpmn/aggregate-assets.bpmn
src/test/java/dev/assetpipeline/...   mirrors main
.github/workflows/ci.yml
```

---

### Task 1: Project skeleton, Compose stack and a verified Zeebe connection

**Files:**
- Create: `pom.xml`, `docker-compose.yml`, `.env.example`, `.gitignore`, `README.md`, `src/main/java/dev/assetpipeline/AssetPipelineApplication.java`, `src/main/resources/application.yaml`
- Test: `src/test/java/dev/assetpipeline/ZeebeConnectionIT.java`

**Interfaces:**
- Produces: a buildable Maven project, a compose stack with Camunda 8.8 (Zeebe and Operate) and Redis, and the application config keys `assetpipeline.providers.<id>.base-url`, `assetpipeline.providers.<id>.api-key`, `assetpipeline.result-ttl` (default `PT1H`), `assetpipeline.provider-timeout` (default `PT10S`).

- [ ] **Step 1: Verify versions against the official docs**

Open the Camunda 8.8 docs (Spring Boot starter getting started, and the Docker Compose self-managed quickstart). Record in the README "Versions" table: exact `camunda-spring-boot-starter` patch version from Maven Central, the compose file or repo to use, the image tag, and the ports for gRPC, REST and Operate. Check the starter's Spring Boot compatibility and align the Spring Boot version in `pom.xml` to it. Expected: README table filled, no value guessed.

- [ ] **Step 2: Create `pom.xml`, the application class and `application.yaml`**

Dependencies: starter, `spring-boot-starter-web`, `spring-boot-starter-validation`, `spring-boot-starter-data-redis`, `spring-boot-starter-test`, Testcontainers (junit-jupiter), WireMock. Java release 21. Configure the Camunda client for self-managed against the compose ports found in Step 1.

- [ ] **Step 3: Create `docker-compose.yml`, `.env.example`, `.gitignore`**

Compose runs Camunda 8.8 (per Step 1), Redis 7, and the app image built from a Dockerfile added in Task 7. `.env.example` lists `FINNHUB_API_KEY`, `ALPHA_VANTAGE_API_KEY`, `TWELVE_DATA_API_KEY`. `.gitignore` covers `.env`, `target/`, IDE files.

- [ ] **Step 4: Write the failing integration test**

`ZeebeConnectionIT.zeebeTopologyIsReachable()` starts the compose-equivalent Camunda container with Testcontainers and asserts the client's topology request returns at least one broker.

- [ ] **Step 5: Run it, then make it pass**

Run: `mvn -q verify -Dit.test=ZeebeConnectionIT`
Expected first run: FAIL (no container wiring). After wiring the container and client config: PASS.

- [ ] **Step 6: Smoke the compose stack by hand**

Run: `docker compose up -d`, then open Operate on the port from Step 1.
Expected: Operate login page loads and Redis answers `PONG`.

- [ ] **Step 7: `git init` and commit**

```bash
git init
git add .
git commit -m "chore: project skeleton, compose stack, zeebe connection test"
```

---

### Task 2: Domain types and the consolidation use case

**Files:**
- Create: all types under `domain/` and `application/ConsolidateResults.java`
- Test: `src/test/java/dev/assetpipeline/application/ConsolidateResultsTest.java`

**Interfaces:**
- Produces (exact):
  - `enum ProviderId { FINNHUB, ALPHA_VANTAGE, TWELVE_DATA }`. Declaration order is the fundamentals priority.
  - `enum ProviderOutcome { OK, FAILED, TIMEOUT }`
  - `enum AggregationStatus { PENDING, COMPLETED, DEGRADED, FAILED }`
  - `record Quote(String symbol, BigDecimal price, String currency, Instant asOf)`
  - `record Fundamentals(String symbol, BigDecimal marketCap, BigDecimal peRatio, BigDecimal dividendYield)` (fields nullable)
  - `record ProviderSnapshot(ProviderId provider, Map<String, Quote> quotes, Map<String, Fundamentals> fundamentals)`
  - `record AggregatedAsset(String symbol, BigDecimal medianPrice, String currency, Fundamentals fundamentals, List<ProviderId> sources)`
  - `record AggregationResult(String requestId, AggregationStatus status, Map<ProviderId, ProviderOutcome> providers, List<AggregatedAsset> assets)`
  - `class TransientProviderException extends RuntimeException`, `class BusinessProviderException extends RuntimeException`
  - `interface MarketDataProvider { ProviderId id(); ProviderSnapshot fetch(List<String> symbols); }` (throws the two exceptions above)
  - `interface ResultStore { void saveRaw(String requestId, ProviderSnapshot s); Optional<ProviderSnapshot> loadRaw(String requestId, ProviderId p); void saveResult(AggregationResult r); Optional<AggregationResult> findResult(String requestId); void delete(String requestId); }`
  - `interface WorkflowStarter { void start(String requestId, List<String> symbols); }`
  - `class ConsolidateResults { ConsolidateResults(ResultStore store); AggregationResult execute(String requestId, List<String> symbols, Map<ProviderId, ProviderOutcome> outcomes); }`

- [ ] **Step 1: Write the failing tests in `ConsolidateResultsTest` using an in-memory fake `ResultStore`**

Names and assertions:
- `priceIsMedianOfAvailableProviders`: prices 100, 102, 110 give `medianPrice == 102`; with two providers (100, 102) the median is `101`.
- `fundamentalsComeFromFirstProviderInPriorityOrder`: FINNHUB and TWELVE_DATA both answer, fundamentals equal FINNHUB's.
- `allOkIsCompleted`, `someFailedOrTimedOutIsDegraded`, `noneOkIsFailed` assert the three status rules from spec §4.
- `symbolMissingFromEveryProviderIsOmitted`: the other symbols still appear (Review Focus 2).
- `okProviderWithExpiredRawCountsAsFailed`: `loadRaw` returns empty for an `OK` provider, the result is `DEGRADED` and that provider's outcome is `FAILED` (Review Focus 3).
- `resultIsSavedToStore`: `findResult(requestId)` returns the returned result.

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q test -Dtest=ConsolidateResultsTest`
Expected: FAIL, compilation errors for missing types.

- [ ] **Step 3: Create the domain types and implement `ConsolidateResults.execute`**

Median over the prices of providers with outcome `OK` and a loadable snapshot containing the symbol. Use `BigDecimal` with scale from the inputs, average of the two middle values for even counts.

- [ ] **Step 4: Run to verify pass**

Run: `mvn -q test -Dtest=ConsolidateResultsTest`
Expected: PASS, all tests.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "feat: domain model and result consolidation"
```

---

### Task 3: Redis result store

**Files:**
- Create: `adapters/out/redis/RedisResultStore.java`
- Test: `src/test/java/dev/assetpipeline/adapters/out/redis/RedisResultStoreIT.java`

**Interfaces:**
- Consumes: `ResultStore`, `ProviderSnapshot`, `AggregationResult` (Task 2). Config `assetpipeline.result-ttl` (Task 1).
- Produces: `class RedisResultStore implements ResultStore` with JSON serialization via Jackson, keys `agg:{requestId}:raw:{PROVIDER}` and `agg:{requestId}:result`.

- [ ] **Step 1: Write the failing tests with a Testcontainers Redis**

- `savedRawSnapshotIsLoadedBack`, `savedResultIsFound`, `unknownRequestIsEmpty`.
- `entriesExpireAfterTtl`: with TTL `PT1S`, `findResult` is empty after 2 seconds.
- `deleteRemovesResultAndRaw`.

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q verify -Dit.test=RedisResultStoreIT`
Expected: FAIL, class not found.

- [ ] **Step 3: Implement `RedisResultStore` over `StringRedisTemplate`**

One Jackson `ObjectMapper` with the Java time module. `delete` removes the result key and the three raw keys.

- [ ] **Step 4: Run to verify pass**

Run: `mvn -q verify -Dit.test=RedisResultStoreIT`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "feat: redis result store with ttl"
```

---

### Task 4: Provider adapters

**Files:**
- Create: `adapters/out/provider/FinnhubProvider.java`, `AlphaVantageProvider.java`, `TwelveDataProvider.java`
- Test: one `*ProviderTest.java` per provider in `src/test/java/dev/assetpipeline/adapters/out/provider/`

**Interfaces:**
- Consumes: `MarketDataProvider`, `ProviderSnapshot`, `Quote`, `Fundamentals`, the two exceptions (Task 2). Config `assetpipeline.providers.<id>.base-url` and `.api-key` (Task 1).
- Produces: three `@Component`s implementing `MarketDataProvider`. Error classification, identical across the three:
  - HTTP 429, 5xx, network timeout, or a 200 body carrying the provider's rate-limit note: `TransientProviderException`.
  - HTTP 401/403 or an unknown-symbol body: `BusinessProviderException`.

- [ ] **Step 1: Check each provider's current free-tier endpoints**

For quote and fundamentals, read the provider's official docs and save one real sample JSON per endpoint under `src/test/resources/providers/<id>/` (keys removed). Expected: six sample files, each used by the tests below.

- [ ] **Step 2: Write failing tests per provider with WireMock**

- `mapsQuoteAndFundamentalsForAllSymbols` using the saved samples.
- `http429IsTransient`, `http503IsTransient`, `http401IsBusiness`.
- For Alpha Vantage only: `rateLimitNoteWithHttp200IsTransient` (Review Focus 4).
- `unknownSymbolIsBusinessError`.

- [ ] **Step 3: Run to verify failure**

Run: `mvn -q test -Dtest='*ProviderTest'`
Expected: FAIL, classes not found.

- [ ] **Step 4: Implement the three providers**

Use `RestClient` with the configured base URL and a request timeout shorter than the BPMN timer. One HTTP call per symbol per endpoint is acceptable at a 10-symbol limit.

- [ ] **Step 5: Run to verify pass**

Run: `mvn -q test -Dtest='*ProviderTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src
git commit -m "feat: finnhub, alpha vantage and twelve data adapters"
```

---

### Task 5: BPMN process, workers and the Zeebe starter

**Files:**
- Create: `src/main/resources/bpmn/aggregate-assets.bpmn`, `adapters/in/worker/ProviderWorkers.java`, `adapters/in/worker/ConsolidateWorker.java`, `adapters/out/zeebe/ZeebeWorkflowStarter.java`
- Test: `src/test/java/dev/assetpipeline/process/AggregateAssetsProcessIT.java`

**Interfaces:**
- Consumes: `MarketDataProvider`, `ResultStore`, `ConsolidateResults`, `WorkflowStarter`, `ProviderOutcome` (Tasks 2 to 4).
- Produces:
  - Process id `aggregate-assets`. Start variables: `requestId: String`, `symbols: List<String>`, `providerTimeout: String` (ISO-8601 duration, from `assetpipeline.provider-timeout`).
  - Job types `fetch-finnhub`, `fetch-alpha-vantage`, `fetch-twelve-data` (retries 3), and `consolidate-and-store`. Each fetch worker completes with `outcome_<PROVIDER>=OK` after `saveRaw`.
  - Per-branch boundary error event (BPMN error code `PROVIDER_REJECTED`) and boundary timer (`= providerTimeout`) each lead to a FEEL script task setting `outcome_<PROVIDER>` to `FAILED` or `TIMEOUT`, then to the join.
  - After the join: `consolidate-and-store`, then an exclusive gateway. Variable `status = "FAILED"` leads to an error end event. Anything else leads to a normal end event.
  - `class ZeebeWorkflowStarter implements WorkflowStarter`.

- [ ] **Step 1: Write the failing process tests with Testcontainers (Camunda) and WireMock providers**

Each test starts an instance through `WorkflowStarter`, waits for completion, and asserts `findResult` and the provider outcomes. Test config sets `provider-timeout` to `PT3S` and the backoff to 1 second.
- `allProvidersOkIsCompleted`
- `slowProviderTimesOutAndResultIsDegraded` (one WireMock stub with a delay above 3 seconds)
- `invalidKeyOnOneProviderIsDegraded` (HTTP 401 becomes a BPMN error)
- `transientErrorIsRetriedThenSucceeds` (503 then 200 on the same stub, asserts two calls)
- `allProvidersFailingIsFailedAndProcessEndsInError`

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q verify -Dit.test=AggregateAssetsProcessIT`
Expected: FAIL, process not deployed.

- [ ] **Step 3: Model `aggregate-assets.bpmn` per the contract above**

Deploy it on startup through the starter's deployment annotation. Open the file in Operate or the Camunda Modeler and confirm the diagram renders.

- [ ] **Step 4: Implement `ProviderWorkers` and `ConsolidateWorker`**

Each fetch worker: call `provider.fetch(symbols)`, `store.saveRaw`, complete with the outcome variable. On `TransientProviderException` fail the job with a retry backoff. On `BusinessProviderException` throw a BPMN error with code `PROVIDER_REJECTED`. Confirm the exact fail-with-backoff and BPMN-error calls against the pinned starter docs from Task 1.

- [ ] **Step 5: Implement `ZeebeWorkflowStarter.start`**

Create the process instance by id with the three variables above.

- [ ] **Step 6: Run to verify pass**

Run: `mvn -q verify -Dit.test=AggregateAssetsProcessIT`
Expected: PASS, all five scenarios.

- [ ] **Step 7: Commit**

```bash
git add src
git commit -m "feat: aggregate-assets process, workers and zeebe starter"
```

---

### Task 6: Application use cases and REST API

**Files:**
- Create: `application/StartAggregation.java`, `application/GetAggregation.java`, `adapters/in/rest/AggregationController.java`, `adapters/in/rest/ApiExceptionHandler.java`
- Test: `StartAggregationTest.java`, `GetAggregationTest.java` (unit), `AggregationControllerTest.java` (`@WebMvcTest`), `AggregationApiIT.java` (end to end)

**Interfaces:**
- Consumes: `ResultStore`, `WorkflowStarter`, `AggregationResult` (Task 2).
- Produces:
  - `class StartAggregation { String execute(List<String> symbols); }` throws `IllegalArgumentException` for invalid input and `WorkflowUnavailableException` (new, in `application`) when the starter fails.
  - `class GetAggregation { Optional<AggregationResult> execute(String requestId); }`
  - `POST /aggregations` body `{"symbols":[...]}` returns `202 {"requestId":"..."}`. `GET /aggregations/{requestId}` returns `{status, symbols, providers, result}`.

- [ ] **Step 1: Write the failing unit tests**

- `normalizesToUppercaseAndDeduplicates`: `["aapl","AAPL"," sap "]` yields symbols `["AAPL","SAP"]` (Review Focus 1).
- `rejectsEmptyListAndMoreThanTenSymbols`, `rejectsSymbolsOutsideThePattern` (`^[A-Z0-9.\-]{1,12}$`).
- `savesPendingBeforeStartingWorkflow`: the fake store holds a `PENDING` result when the fake starter is called.
- `workflowFailureDeletesPendingAndThrows` (Review Focus 5).
- `getReturnsEmptyForUnknownId`.

- [ ] **Step 2: Write the failing controller tests**

- `postReturns202WithRequestId`, `postInvalidReturns400`, `malformedJsonReturns400` (Review Focus 6), `workflowUnavailableReturns503`.
- `getPendingReturns200WithPendingStatus`, `getCompletedReturnsResult`, `getUnknownReturns404`.

- [ ] **Step 3: Run to verify failure**

Run: `mvn -q test -Dtest='StartAggregationTest,GetAggregationTest,AggregationControllerTest'`
Expected: FAIL, classes not found.

- [ ] **Step 4: Implement the use cases, controller and `ApiExceptionHandler`**

Handler maps `IllegalArgumentException` and `HttpMessageNotReadableException` to 400, `WorkflowUnavailableException` to 503, with a JSON body `{"error": "<message>"}`.

- [ ] **Step 5: Write `AggregationApiIT`**

Starts the full app with Testcontainers (Camunda, Redis) and WireMock providers. `postThenPollReturnsCompleted` posts two symbols, polls `GET` until the status leaves `PENDING`, and asserts `COMPLETED` with two assets.

- [ ] **Step 6: Run everything to verify pass**

Run: `mvn -q verify`
Expected: PASS, all unit and integration tests.

- [ ] **Step 7: Commit**

```bash
git add src
git commit -m "feat: aggregation use cases and rest api"
```

---

### Task 7: CI, Dockerfile and README

**Files:**
- Create: `.github/workflows/ci.yml`, `Dockerfile`, `docs/diagrams/` (BPMN export and architecture sketch)
- Modify: `README.md`, `docker-compose.yml` (add the app service)

**Interfaces:**
- Consumes: the whole project.
- Produces: a green CI badge, `docker compose up` running the app, and a README demo.

- [ ] **Step 1: Create `ci.yml`**

Runs on `push` and `pull_request`: Java 21 setup, `mvn -B verify`. Docker is available on GitHub-hosted runners for Testcontainers. Expected: workflow file valid.

- [ ] **Step 2: Create the `Dockerfile` and add the app service to compose**

Multi-stage build: Maven build, then a Java 21 JRE image. Compose passes the three API keys from `.env`.

- [ ] **Step 3: Write the README**

Sections: what it is, architecture diagram, BPMN diagram image, versions table (from Task 1), quickstart with `cp .env.example .env` and `docker compose up`, the `curl` demo for `POST` then `GET`, how to see the instance in Operate, status meanings, design decisions, test commands, CI badge.

- [ ] **Step 4: Run the demo from a clean clone**

Run: clone to a temp folder, `docker compose up`, run the README's `curl` commands.
Expected: `GET` returns `COMPLETED` or `DEGRADED` with real provider data, and the instance is visible in Operate.

- [ ] **Step 5: Push and confirm CI is green**

Push to the GitHub repository the author creates. Expected: the `ci` workflow passes and the badge in the README turns green.

- [ ] **Step 6: Commit**

```bash
git add .
git commit -m "ci: pipeline, dockerfile and readme"
```
