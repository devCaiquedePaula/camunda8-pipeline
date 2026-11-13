# Progress

Plan: `docs/superpowers/plans/2026-11-13-camunda8-pipeline.md`. Spec: `docs/superpowers/specs/2026-11-12-camunda8-pipeline-design.md`.

Last updated: 2026-11-13. Full suite: 29 unit tests and 11 integration tests, all passing (`mvn -B verify`, needs Docker).

| Task | Status |
|---|---|
| 1. Project skeleton, Compose stack, Zeebe connection test | done |
| 2. Domain types and `ConsolidateResults` | done |
| 3. Redis result store | done |
| 4. Provider adapters (Finnhub, Alpha Vantage, Twelve Data) | done |
| 5. BPMN process `aggregate-assets`, workers, Zeebe starter | done |
| 6. Use cases and REST API | next |
| 7. CI, Dockerfile, README | pending |

## Decisions that differ from the first plan

- Camunda 8.10.9 with `camunda-spring-boot-3-starter` and Spring Boot 3.5.16 (the plan assumed 8.8).
- Compose runs one `orchestration` container (Zeebe, Operate, Tasklist, H2 storage), no Elasticsearch, no Connectors.
- The all-providers-failed path ends in a plain end event, not an error end event, to avoid an incident in Zeebe. The `FAILED` status is stored in Redis.
- A provider throws a business error only when none of the requested symbols is known to it; partially unknown symbols are omitted.
- `Fundamentals.dividendYield` is a fraction (0.0276 = 2.76%); `marketCap` is in currency units.
- Twelve Data returns quotes only (no fundamentals).
- `finnhub/*.json` test samples are hand-written from the documented shape. Replace them with real captures when a Finnhub key is available (see `src/test/resources/providers/README.md`).

## Resuming

1. Start Docker Desktop, then `docker compose up -d` if you want the stack running by hand.
2. Run `mvn -B verify` to confirm everything is green.
3. Continue with Task 6 of the plan. One task at a time; stop after each task, validate, push.
