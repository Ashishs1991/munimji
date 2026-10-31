# Spring Boot shell

Milestone 1. The application starts, reads settings from `.env` through `application.yml`, and exposes a health check. No business data is stored yet.

## What this covers

- Maven Wrapper (`./mvnw`) with Spring Boot 3.5.16, compiled for Java 17 so it runs on the current Java 20 JDK.
- `MunimProperties` binds every `munim.*` value declared in `src/main/resources/application.yml`.
- `spring.config.import` loads an optional `.env` file as properties. A missing file does not stop startup.
- `GET /actuator/health` is the only exposed actuator endpoint, and it does not show details.

## Spec

Repository layout and configuration in `doc/RFC-001.md` sections 3 and 15. No stock, money, or tenant acceptance criteria are implemented here.

## How to check

```bash
./mvnw test
```

`HealthEndpointTest` expects `/actuator/health` to be UP and checks the non-secret defaults. `DotenvBindingTest` writes a temporary `.env`, checks that `META_API_KEY` is bound, and deletes the file. It skips if a local `.env` is already present so it does not overwrite secrets.

## Still open

Database, businesses, and godowns are milestone 2. Copy `.env.example` to `.env` before running against real Meta, Supabase, or WhatsApp accounts.
