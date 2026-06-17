# Persistence & Migration Baseline — Plan Brief

> Full plan: `context/changes/persistence-migration-baseline/plan.md`

## What & Why

NextSlope has JPA + H2 (local) + Postgres (prod) on the classpath but **no migration tool and no entities** — the prod profile runs `ddl-auto=update` as a deliberate no-op. F-01 wires Flyway (versioned SQL) + entity/repository conventions and **proves they run identically on H2 and Postgres**, so every later data-bearing slice (S-01–S-07) builds on a real, migration-managed schema instead of ad-hoc per-slice schema choices.

## Starting Point

`build.gradle` has data-jpa + H2 + postgresql drivers but no Flyway/Liquibase. `application.properties` (local) has no datasource config (auto H2); `application-prod.properties` sets `ddl-auto=update`. No `db/migration`, no entities, no `application-test`. CI runs only `./gradlew test`. The deployment plan already flagged that SB4 needs the Flyway *starter* and that prod migrations must use Neon's DIRECT (non-pooler) endpoint.

## Desired End State

Flyway applies `V1__create_users.sql` on every boot and test run. `./gradlew test` (locally and in CI) is green, including a Testcontainers Postgres test that runs the migration on the real prod engine and round-trips the repository; the existing context test covers the H2 path. Hibernate is demoted to `validate`. Prod routes Flyway through the DIRECT endpoint while runtime stays pooled. Conventions are documented in `AGENTS.md`.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Migration tool | Flyway (versioned SQL) | Conventional, agent-friendly, simplest; SB4 starter already named in deployment plan | Plan |
| Cross-DB verification | Testcontainers Postgres test in CI + H2 (PG mode) local | Proves migrations on the actual prod engine — strongest "identical" guarantee; Docker is on GitHub runners | Plan |
| One portable SQL set | Single SQL + H2 `MODE=PostgreSQL` | One migration set applies to both engines without vendor folders | Plan |
| First migration (V1) | The real `users`/roles table S-01 needs | Verifying artifact doubles as the next slice's foundation — no throwaway | Plan |
| Conventions concreteness | One real entity + Spring Data repo + slice test | A working canonical example future slices copy | Plan |
| `ddl-auto` going forward | `validate` everywhere | Flyway owns DDL; Hibernate only checks entity↔schema parity | Plan |
| Seed data | Out of scope (schema/DDL only) | Seeding belongs to the slices that need it (S-01/S-03/S-06) | Plan |
| F-01 / S-01 boundary | F-01 defines canonical auth schema; S-01 adds behavior only | Prevents a V1 reshape; S-01 adds columns additively if needed | Plan |
| Flyway prod endpoint | DIRECT (non-pooler) via `SPRING_FLYWAY_URL` | PgBouncer transaction pooling breaks Flyway advisory locks/DDL | Deployment plan |

## Scope

**In scope:** Flyway + Testcontainers deps; profile config (local H2 PG-mode, prod validate + Flyway direct endpoint, `render.yaml` secret); `V1__create_users.sql`; `User` entity + `UserRepository`; dual-engine verification tests; CI confirmation; `AGENTS.md` conventions.

**Out of scope:** Auth behavior (sign-up/in/out, `UserDetailsService`, roles enforcement — S-01); other domain tables; seed data; switching local dev to Postgres; rollback migrations.

## Architecture / Approach

Flyway versioned SQL in `src/main/resources/db/migration`, one portable set for both engines. Local H2 runs `MODE=PostgreSQL`; CI/Testcontainers and prod run real Postgres. Hibernate `validate` makes the migration the single schema source of truth. Prod splits connections: pooled for runtime, DIRECT for Flyway. Verification is automated and dual-engine under the single `./gradlew test` CI already runs.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Tooling & config | Flyway/Testcontainers deps; 3-profile config; DIRECT-endpoint split | Forgetting `validate` lets Hibernate mutate prod schema |
| 2. Migration + entity + tests | `V1` users table, `User`/`UserRepository`, dual-engine verification | Non-portable SQL passing on H2 but failing on Postgres (caught by Testcontainers) |
| 3. CI + docs | Migrations-run-in-CI confirmed; conventions in `AGENTS.md` | Testcontainers needing Docker (present on GitHub runners) |

**Prerequisites:** None (F-01 is the root foundation). Docker available locally + in CI for Testcontainers.
**Estimated effort:** ~1–2 after-hours sessions across 3 small phases.

## Open Risks & Assumptions

- Assumes GitHub-hosted `ubuntu-latest` provides Docker for Testcontainers (it does today).
- Assumes Neon exposes a DIRECT (non-pooler) endpoint for `SPRING_FLYWAY_URL` (per infrastructure.md / deployment plan).
- H2 PostgreSQL mode is not 100% faithful to Postgres; the Testcontainers test is the real-engine backstop for any dialect gap.

## Success Criteria (Summary)

- `./gradlew test` green locally and in CI, with both the H2 context test and the Postgres Testcontainers test passing.
- `./gradlew bootRun` boots with Flyway applying V1 (visible `users` + `flyway_schema_history`).
- A future slice can add its migration + entity by following `AGENTS.md` alone.
