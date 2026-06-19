# Persistence & Migration Baseline Implementation Plan

## Overview

Wire a schema-migration mechanism (Flyway, versioned SQL) and JPA entity/repository conventions for NextSlope, and **prove they run identically against the local database (H2 in PostgreSQL-compatibility mode) and the production database (Neon Postgres, verified via a Testcontainers Postgres integration test in CI)**. This closes the production profile's deliberate "migrations come later" gap (`ddl-auto=update` no-op) so the next slice, S-01 (account-authentication), lands on a real, migration-managed schema. The first migration creates the canonical `users` table S-01 will consume as-is.

## Current State Analysis

What exists today (verified in the codebase as of this plan):

- **No migration tool on the classpath.** `build.gradle` has `spring-boot-starter-data-jpa`, `com.h2database:h2` (`runtimeOnly`), and `org.postgresql:postgresql` (`runtimeOnly`) — but no Flyway or Liquibase. The deployment plan (`context/changes/deployment/deployment-plan.md`) explicitly deferred this and recorded the key constraint: Spring Boot 4 needs the **starter** (`flyway-core` alone is ignored), and migrations must run against Neon's **DIRECT (non-pooler)** endpoint and stay backward-compatible (no rollback on Neon free).
- **The two databases diverge.** `application.properties` (default / local) has **no datasource config at all** — it relies on Spring Boot auto-configuring an in-memory H2 with no **explicit** `ddl-auto` property (embedded-database defaults apply implicitly). `application-prod.properties` sets `spring.jpa.hibernate.ddl-auto=update` (a deliberate no-op while there are no entities) plus Hikari sizing for Neon.
- **No `application-test` profile** exists; tests run on the default profile (auto H2). The only test is `NextslopeApplicationTests.contextLoads()` (`@SpringBootTest`).
- **No domain persistence.** No entities, repositories, or `db/migration` directory. `SecurityConfig` still uses default form-login with no user table.
- **CI** (`.github/workflows/ci.yml`) runs only `./gradlew test --no-daemon` on push/PR to `main`. GitHub-hosted `ubuntu-latest` ships Docker, so Testcontainers works there.
- **Docker image build** (`Dockerfile`) runs `bootJar -x test` — tests are skipped in the image build (no docker-in-docker needed); CI is the test gate.
- **`render.yaml`** declares the web service with `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` as `sync: false` secrets and `autoDeployTrigger: checksPass`.

### Key Discoveries:

- Spring Boot 4 Flyway requires `org.springframework.boot:spring-boot-starter-flyway` (the `flyway-core`-only path is silently ignored) plus `org.flywaydb:flyway-database-postgresql` for the Postgres dialect; H2 support is built into the starter — confirmed against the SB4 migration guide and `spring-boot#47315`.
- Neon's **pooled** endpoint (PgBouncer, transaction mode) breaks Flyway's session-level advisory locks and DDL. Flyway must use the **DIRECT** endpoint — recorded in `deployment-plan.md` Phase 3 (`spring.datasource.url` stays pooled for runtime; Flyway gets its own URL).
- H2 with `;MODE=PostgreSQL` lets one portable SQL migration set apply to both engines; Flyway still detects H2 and parses accordingly, so portable DDL is the contract.
- `@ServiceConnection` on a `PostgreSQLContainer` (from `spring-boot-testcontainers`) auto-wires `spring.datasource.*` to the container — no `@DynamicPropertySource` needed.
- PRD guardrails this slice must respect: determinism, and profile/visited-list privacy (the `users` schema must support per-user ownership later; no cross-user leakage is introduced here).

## Desired End State

- Flyway runs automatically on application startup (all profiles) and on every test boot, applying `V1__create_users.sql`.
- `./gradlew test` passes locally and in CI, including a Testcontainers test that runs the migration against a real Postgres container and exercises the repository — proving the migration + entity mapping work on the production engine.
- The existing `contextLoads` test passes on H2 (PostgreSQL mode), proving the same migration applies to the local engine.
- `spring.jpa.hibernate.ddl-auto=validate` everywhere: Flyway owns DDL, Hibernate only verifies the entity↔schema mapping (a mismatch fails fast at boot/test).
- In production, Flyway connects via the DIRECT Neon endpoint (`SPRING_FLYWAY_URL`), while runtime queries continue on the pooled `SPRING_DATASOURCE_URL`.
- Persistence + migration conventions are documented in `AGENTS.md` so S-01–S-07 follow one pattern.

**Verification:** `./gradlew test` is green with both the H2 context test and the Testcontainers Postgres test passing; CI is green; a fresh `./gradlew bootRun` (default profile) boots with Flyway applying V1 to H2.

## What We're NOT Doing

- **No authentication behavior** — no sign-up/sign-in/sign-out flow, `UserDetailsService`, password-encoder wiring, registration controller, or role enforcement. That is S-01. F-01 only creates the canonical `users` schema + entity + repository and proves the persistence path.
- **No other domain tables** (resorts, profiles, visited) — those land in their owning slices via additive migrations.
- **No seed/reference data** — schema/DDL only. Seeding (bootstrap admin, resort dataset) is deferred to the slices that need it (S-01/S-06/S-03).
- **No switch of local dev to real Postgres** — local stays H2 (PostgreSQL mode) for fast, dependency-free dev; Postgres fidelity is provided by the Testcontainers test.
- **No rollback/undo migrations** — Neon free has no rollback; all migrations are forward-only and backward-compatible.
- **No CI infrastructure overhaul** — the existing `./gradlew test` job is the verification path; we only confirm/annotate it.

## Implementation Approach

Flyway with versioned SQL in `src/main/resources/db/migration`. One portable SQL set targets both engines: locally H2 runs in `MODE=PostgreSQL`; in CI/Testcontainers and prod the same SQL runs on real Postgres. Hibernate is demoted to `ddl-auto=validate` so the migration is the single source of schema truth and the entity mapping is checked against it. Production splits connections: pooled endpoint for the app, DIRECT endpoint for Flyway. Verification is dual-engine and automated: the existing `@SpringBootTest` context test exercises the H2 path; a new `@SpringBootTest @Testcontainers` test exercises the Postgres path and the repository round-trip — both run under the single `./gradlew test` invocation CI already uses.

## Critical Implementation Details

- **Flyway must use Neon's DIRECT (non-pooler) endpoint.** The pooled PgBouncer endpoint (transaction mode) breaks Flyway's session-level advisory locks and DDL execution. In the prod profile, set `spring.flyway.url` (+ user/password) from a separate, **required** `SPRING_FLYWAY_URL` env var with no fallback (an unset value fails fast rather than silently using the pooler); leave `spring.datasource.url` on the pooled endpoint for runtime. Local and test never load the prod profile (local uses the H2 URL; the Postgres test uses Testcontainers `@ServiceConnection`), so no `spring.flyway.url` is needed there — Flyway uses the active datasource.
- **`ddl-auto` must change from `update` to `validate` in prod before V1 ships.** With an entity present, leaving `update` would let Hibernate mutate the Neon schema out from under Flyway. `validate` is mandatory the moment the first entity exists.
- **Portable SQL only.** V1 must use DDL that parses identically on H2 (PostgreSQL mode) and Postgres — e.g., `BIGINT GENERATED BY DEFAULT AS IDENTITY` for the key, `VARCHAR`, `TIMESTAMP`, a `UNIQUE` constraint on email. Avoid Postgres-only types/extensions.

## Phase 1: Migration tooling & cross-engine datasource config

### Overview

Add Flyway + Testcontainers dependencies and reconcile the three profile configurations so one migration set runs on H2 (local), Testcontainers Postgres (test), and Neon (prod) — including the DIRECT-endpoint split for Flyway.

### Changes Required:

#### 1. Build dependencies

**File**: `build.gradle`

**Intent**: Put Flyway on the classpath so migrations auto-run, add the Postgres dialect module, and add the Testcontainers stack used by the Phase 2 verification test.

**Contract**: Add `implementation 'org.springframework.boot:spring-boot-starter-flyway'` and `runtimeOnly 'org.flywaydb:flyway-database-postgresql'` to the main dependencies. Add `testImplementation 'org.springframework.boot:spring-boot-testcontainers'`, `testImplementation 'org.testcontainers:junit-jupiter'`, and `testImplementation 'org.testcontainers:postgresql'` (versions managed by Spring Boot's dependency management — no explicit versions). Do not change the Java toolchain or the Spring Boot version.

#### 2. Local / default profile datasource + JPA + Flyway

**File**: `src/main/resources/application.properties`

**Intent**: Make the local engine behave like Postgres so the same migration applies, and lock Hibernate to validation so Flyway owns DDL.

**Contract**: Add an explicit H2 datasource URL using PostgreSQL compatibility mode and set the validate posture. The URL must keep the schema across the app's lifetime (in-memory is fine for dev) and enable PG mode:

```properties
spring.datasource.url=jdbc:h2:mem:nextslope;MODE=PostgreSQL;DB_CLOSE_DELAY=-1
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.hibernate.ddl-auto=validate
```

(Flyway is enabled by default once the starter is present; `spring.flyway.locations` stays at the default `classpath:db/migration`. The existing H2 console dev dependency continues to work against this URL.)

#### 3. Production profile: validate + Flyway DIRECT endpoint

**File**: `src/main/resources/application-prod.properties`

**Intent**: Stop Hibernate from mutating the prod schema, and route Flyway through Neon's DIRECT endpoint while runtime stays pooled.

**Contract**: Change `spring.jpa.hibernate.ddl-auto` from `update` to `validate`. Add Flyway connection properties routed to the DIRECT endpoint. `spring.flyway.url` has **no fallback** — if `SPRING_FLYWAY_URL` is unset the app must fail fast at startup (unresolved placeholder) rather than silently migrating through the pooled PgBouncer endpoint, which breaks Flyway's advisory locks/DDL. `SPRING_FLYWAY_URL` is therefore a required prod secret:

```properties
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.url=${SPRING_FLYWAY_URL}
spring.flyway.user=${SPRING_DATASOURCE_USERNAME}
spring.flyway.password=${SPRING_DATASOURCE_PASSWORD}
```

#### 4. Render Blueprint: DIRECT-endpoint secret

**File**: `render.yaml`

**Intent**: Declare the new Flyway DIRECT-endpoint secret so the deploy environment can supply Neon's non-pooler URL.

**Contract**: Add `SPRING_FLYWAY_URL` to `envVars` with `sync: false` (value set in the Render dashboard to Neon's DIRECT connection string). This is a **required** secret — the app fails to start in prod if it is missing (see #3). Leave existing vars unchanged.

### Success Criteria:

#### Automated Verification:

- Dependencies resolve and project compiles: `./gradlew compileJava compileTestJava --no-daemon`
- App boots on the default profile with Flyway present (no entity yet → Flyway runs against an empty migration dir without error): `./gradlew test --tests com.nextslope.NextslopeApplicationTests --no-daemon`

#### Manual Verification:

- `render.yaml` lists `SPRING_FLYWAY_URL` as `sync: false`, and the deploy runbook checklist includes a pre-deploy check that the Render dashboard secret is set and points to Neon's DIRECT (non-pooler) connection string.
- Prod profile no longer contains `ddl-auto=update`.

**Implementation Note**: After completing this phase and all automated verification passes, pause for manual confirmation before proceeding.

---

## Phase 2: First migration, entity/repository convention & dual-engine verification

### Overview

Create the canonical `users` schema as `V1`, the matching `User` entity and `UserRepository`, and the dual-engine verification: a Testcontainers Postgres test proving migrate + repository round-trip on the prod engine, with the existing context test proving the H2 path.

### Changes Required:

#### 1. First migration — canonical users table

**File**: `src/main/resources/db/migration/V1__create_users.sql`

**Intent**: Establish the authentication schema S-01 will consume as-is (email/password identity with a role and audit timestamps), using portable DDL valid on both engines.

**Contract**: Create a `users` table with: a generated `BIGINT` identity primary key; `email` (`VARCHAR`, `NOT NULL`, `UNIQUE`); `password_hash` (`VARCHAR`, `NOT NULL`); `role` (`VARCHAR`, `NOT NULL` — stores `USER`/`ADMIN`); `created_at` and `updated_at` (`TIMESTAMP`, `NOT NULL` — no DB default; the entity populates them via Hibernate `@CreationTimestamp`/`@UpdateTimestamp`, see #2). Use `CREATE TABLE` with column/constraint syntax that parses identically on H2 (PostgreSQL mode) and Postgres. Forward-only; no down script.

#### 2. User entity

**File**: `src/main/java/com/nextslope/user/User.java`

**Intent**: Map the `users` table as the canonical persistence entity and set the package convention (`com.nextslope.<domain>`) every later slice follows.

**Contract**: A JPA `@Entity` mapped to `users` with fields matching V1 (id generated `IDENTITY`, email, passwordHash, role, createdAt, updatedAt). Role represented as a string-backed value (enum with `@Enumerated(EnumType.STRING)` or `String`). `createdAt`/`updatedAt` are populated by the entity (Hibernate `@CreationTimestamp` / `@UpdateTimestamp`) so the NOT NULL columns are filled on insert/update without relying on engine-specific DB defaults — keeping behavior identical on H2 and Postgres. Use Lombok per repo convention. No business logic — this is a data-mapping entity. The mapping must satisfy `ddl-auto=validate` against V1.

#### 3. User repository

**File**: `src/main/java/com/nextslope/user/UserRepository.java`

**Intent**: Establish the Spring Data repository convention and provide the lookup S-01 needs.

**Contract**: `interface UserRepository extends JpaRepository<User, Long>` with a derived `Optional<User> findByEmail(String email)`. No custom `@Query` needed.

#### 4. Postgres integration test (prod-engine verification)

**File**: `src/test/java/com/nextslope/user/UserRepositoryPostgresTests.java`

**Intent**: Prove the migration applies and the entity maps correctly on the **production engine** by running against a real Postgres container, and exercise the repository round-trip.

**Contract**: `@SpringBootTest @Testcontainers` test with a `static @Container @ServiceConnection PostgreSQLContainer<?>` (`postgres:16-alpine` or similar). On context start Flyway applies V1 to the container; the test saves a `User` and asserts `findByEmail` returns it. This is the canonical **prod-engine migration verification** example. For routine repository/domain tests in later slices, keep the default convention of using the lightest viable test slice (`@DataJpaTest` when appropriate) and reserve full-context Testcontainers tests for cross-engine migration proof or full wiring checks.

```java
@SpringBootTest
@Testcontainers
class UserRepositoryPostgresTests {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    // @Autowired UserRepository; save + findByEmail round-trip assertion
}
```

#### 5. H2-path coverage

**File**: `src/test/java/com/nextslope/NextslopeApplicationTests.java`

**Intent**: Keep the existing `contextLoads` test as the H2 (PostgreSQL-mode) verification — booting now applies V1 to H2 and validates the `User` mapping.

**Contract**: No code change required; confirm it still passes under the new config. (Optionally assert the `User` entity is mapped, but `contextLoads` + `ddl-auto=validate` already fail on a mismatch.)

### Success Criteria:

#### Automated Verification:

- Full suite passes (both engines): `./gradlew test --no-daemon`
- Postgres migration + repository test passes specifically: `./gradlew test --tests com.nextslope.user.UserRepositoryPostgresTests --no-daemon`
- Hibernate `validate` confirms entity↔schema parity (suite fails if V1 and `User` diverge)

#### Manual Verification:

- `./gradlew bootRun` (default profile) boots cleanly; H2 console shows the `users` table created by Flyway with a `flyway_schema_history` row for V1.
- Migration is forward-only and portable (no Postgres-only or H2-only syntax).

**Implementation Note**: After completing this phase and all automated verification passes, pause for manual confirmation before proceeding.

---

## Phase 3: CI verification path & persistence conventions docs

### Overview

Confirm the Testcontainers test runs in CI on every push/PR (the migrations-run-in-CI verification path the roadmap calls for) and document the persistence + migration conventions so all later slices follow one pattern.

### Changes Required:

#### 1. CI verification

**File**: `.github/workflows/ci.yml`

**Intent**: Ensure the existing test job exercises the Testcontainers Postgres test (Docker is available on `ubuntu-latest`); make the migration-verification intent explicit.

**Contract**: The current `./gradlew test --no-daemon` step already runs the new test under Docker on GitHub-hosted runners — confirm green. Add a clarifying step name/comment noting that this gates schema migrations against real Postgres. No new services block required (Testcontainers manages its own container). Only add changes if a run reveals Docker/daemon gaps.

#### 2. Persistence & migration conventions

**File**: `AGENTS.md`

**Intent**: Record the conventions so S-01–S-07 don't re-derive them: Flyway versioned SQL location and naming, forward-only/backward-compatible rule, `ddl-auto=validate` posture, the DIRECT-endpoint rule for prod migrations, H2-PostgreSQL-mode local parity, and the Testcontainers test pattern as the verification standard.

**Contract**: Add a short "Persistence & Migrations" subsection (under Project Structure or Testing) with 5–7 bullets capturing the above. Reference `V1__create_users.sql` and `UserRepositoryPostgresTests` as canonical examples for migration/prod-engine verification, and explicitly note that routine repository tests should prefer the lightest viable slice (`@DataJpaTest`) unless full-context wiring is the thing being validated. In the same edit, reconcile any stale/contradictory `AGENTS.md` bullets discovered during this slice (for example: CI workflow presence, existing `SecurityConfig`, and current `application.properties` contents) so the file reads as one consistent source of truth.

### Success Criteria:

#### Automated Verification:

- CI run on the change branch is green, including the Testcontainers test: confirm via the Actions run / `gh run watch`.

#### Manual Verification:

- `AGENTS.md` documents migration naming, forward-only rule, validate posture, DIRECT-endpoint rule, and the Testcontainers verification pattern.
- A reader of `AGENTS.md` can add the S-01 follow-up migration without re-asking how persistence works.

**Implementation Note**: After completing this phase and all automated verification passes, pause for manual confirmation.

---

## Testing Strategy

### Unit Tests:

- Repository round-trip (`save` + `findByEmail`) against real Postgres (Testcontainers) — the primary correctness + migration check.

### Integration Tests:

- `@SpringBootTest` context load on H2 (PostgreSQL mode) applying V1 and validating the `User` mapping.
- `@SpringBootTest @Testcontainers` boot applying V1 to Postgres and validating the mapping — the dual-engine "runs identically" proof.

### Manual Testing Steps:

1. `./gradlew test` → both the H2 context test and the Postgres Testcontainers test pass.
2. `./gradlew bootRun` → app boots; H2 console shows `users` + `flyway_schema_history` (V1 applied).
3. Inspect `flyway_schema_history` after a Postgres test run (via container logs / a debug assertion) to confirm V1 checksum recorded.
4. Confirm the prod profile would route Flyway to `SPRING_FLYWAY_URL` (direct endpoint) — config review (no live prod migration in this slice).

## Performance Considerations

Negligible at MVP scale. Flyway adds a one-time startup cost (history-table check + V1). Testcontainers adds container-spin-up time to the test suite (seconds) — acceptable for CI; local runs needing speed can target the H2 context test. Neon autosuspend: the existing `hikari.initialization-fail-timeout=-1` keeps boot resilient while the DB wakes.

## Migration Notes

- Forward-only, backward-compatible migrations only (Neon free has no rollback). Never edit an applied migration; add a new `V{n}__` file.
- Production Flyway runs via Neon's DIRECT (non-pooler) endpoint (`SPRING_FLYWAY_URL`); runtime queries stay on the pooled `SPRING_DATASOURCE_URL`.
- S-01 builds auth behavior on the `users` table; any added auth columns ship as an additive `V2__` migration, not a reshape of V1.

## References

- Change identity: `context/changes/persistence-migration-baseline/change.md`
- Roadmap item: `context/foundation/roadmap.md` → F-01
- Prior deferral + constraints: `context/changes/deployment/deployment-plan.md` (Phase 3 — Flyway/DIRECT-endpoint notes)
- Infra/DB context: `context/foundation/infrastructure.md` (Neon free tier, pooled vs direct)
- Existing config: `src/main/resources/application-prod.properties`, `render.yaml`, `Dockerfile`, `.github/workflows/ci.yml`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Migration tooling & cross-engine datasource config

#### Automated

- [x] 1.1 Dependencies resolve and project compiles: `./gradlew compileJava compileTestJava --no-daemon` — 056b35d
- [x] 1.2 App boots on default profile with Flyway present: `./gradlew test --tests com.nextslope.NextslopeApplicationTests --no-daemon` — 056b35d

#### Manual

- [x] 1.3 `render.yaml` lists `SPRING_FLYWAY_URL` (`sync: false`) and runbook checklist verifies dashboard secret uses Neon DIRECT endpoint — 056b35d
- [x] 1.4 Prod profile no longer contains `ddl-auto=update` — 056b35d

### Phase 2: First migration, entity/repository convention & dual-engine verification

#### Automated

- [x] 2.1 Full suite passes (both engines): `./gradlew test --no-daemon` — 3bcfc15
- [x] 2.2 Postgres migration + repository test passes: `./gradlew test --tests com.nextslope.user.UserRepositoryPostgresTests --no-daemon` — 3bcfc15
- [x] 2.3 Hibernate `validate` confirms entity↔schema parity — 3bcfc15

#### Manual

- [x] 2.4 `./gradlew bootRun` boots; H2 console shows `users` + `flyway_schema_history` (V1) — 3bcfc15
- [x] 2.5 Migration is forward-only and portable (no engine-specific syntax) — 3bcfc15

### Phase 3: CI verification path & persistence conventions docs

#### Automated

- [x] 3.1 CI run green on the change branch, including the Testcontainers test — a43ea2b

#### Manual

- [x] 3.2 `AGENTS.md` documents naming, forward-only rule, validate posture, DIRECT-endpoint rule, Testcontainers pattern — a43ea2b
- [x] 3.3 A reader can add the S-01 follow-up migration from `AGENTS.md` alone — a43ea2b
