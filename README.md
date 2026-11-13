# Camunda 8 Asset Aggregation Pipeline

Spring Boot service that aggregates stock market data (US and Europe) from three public providers, in parallel, through a Camunda 8 process. Work in progress: see `docs/superpowers/specs` and `docs/superpowers/plans`.

## Versions

Verified on 2026-11-13 against Maven Central and the official `camunda-distributions` repository.

| Component | Version | Source |
|---|---|---|
| Camunda 8 (Zeebe, Operate, Tasklist) | 8.10.9 (`camunda/camunda` image) | docker-compose 8.10 distribution |
| Camunda client | `io.camunda:camunda-spring-boot-3-starter` 8.10.9 | Maven Central |
| Spring Boot | 3.5.16 | Maven Central |
| Java | 21 | |
| Redis | 7 (alpine) | |

Local ports: Zeebe gRPC `26500`, REST and Operate `8080` (login `demo` / `demo`), health `9600`, Redis `6379`.
