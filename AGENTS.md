# Repository Guidelines

NextSlope is a Spring Boot 4 + Thymeleaf web app that recommends three ski resorts based on a user's profile. Solo developer, 3-week after-hours MVP; product scope lives in `@context/foundation/prd.md`.

## Hard Rules

- Single-tier stack **for the application**: server-rendered Thymeleaf with Bootstrap 5 + HTMX via CDN. Do not introduce a JS build step, SPA framework, or Node tier into the app. Full rationale: `@context/foundation/tech-stack.md`. Carve-out: `packages/` is a developer-tooling zone, outside the Gradle build and absent from the deployed artifact — Node/TypeScript there is expected and is not a violation of this rule.
- Java 21 toolchain is pinned in `@build.gradle`; do not bump.
- Remote: `git@github.com:wbiniecki/NextSlope-10x.git` (`main` tracks `origin/main`).
- Recommendation logic must honor the PRD guardrails: always three results or an explicit explanation, truthful rationale, profile and visited-list privacy. See `@context/foundation/prd.md` → "Success Criteria → Guardrails".

## Project Structure

- `src/main/java/com/nextslope/` — Java sources under the single base package.
- `src/main/resources/application.properties` — default/local runtime config (H2 in PostgreSQL-compatibility mode, `ddl-auto=validate`, actuator/health). `application-prod.properties` overrides for Neon (pooled runtime datasource + DIRECT-endpoint Flyway).
- `src/main/resources/db/migration/` — Flyway versioned SQL migrations (`V{n}__description.sql`); `V1__create_users.sql` is the canonical first migration.
- `src/main/resources/templates/`, `src/main/resources/static/` — Thymeleaf views and static assets (create as needed).
- `src/test/java/com/nextslope/` — JUnit 5 tests; class suffix `*Tests` (see `NextslopeApplicationTests.java`).
- `context/foundation/` — PRD, tech-stack hand-off, shape-notes (authoritative product/architecture).
- `context/changes/` — change logs (e.g., bootstrap verification).
- `packages/code-reviewer/` — Node/TypeScript diff-review agent; developer tooling, not shipped. Conventions live in `packages/code-reviewer/AGENTS.md`.
- `.cursor/skills/` — 10x workflow skills you can invoke.

## Build, Test & Development Commands

- `./gradlew bootRun` — start the app locally.
- `./gradlew test` — run the JUnit 5 suite.
- `./gradlew pitest` — run the PIT mutation gate for `com.nextslope.recommendation.*`.
- `./gradlew playwrightInstall` — install Playwright Chromium (+ Linux deps) used by browser e2e.
- `./gradlew e2eTest` — run the browser smoke tier from `src/e2eTest/java/` (headless Chromium via Playwright).
- `./gradlew build` — compile, test, and assemble the boot jar.
- `./gradlew test --tests com.nextslope.NextslopeApplicationTests` — run one test class.
- `./gradlew --version` — verify Gradle wrapper + JDK 21.

## Coding Style & Conventions

- Java 21, base package `com.nextslope`. Build files use tabs (see `@build.gradle`).
- Lombok is wired on `compileOnly` + `annotationProcessor`; prefer it over hand-written boilerplate.
- No formatter or linter is configured yet — use @Controller / @Service / @Repository and constructor injection.
- HTMX partials must be returned as Thymeleaf `th:fragment` snippets from controllers (per `@context/foundation/tech-stack.md`).

## Testing

- JUnit 5 is enabled via `useJUnitPlatform()` in `@build.gradle`. Per-domain Spring Boot test starters (`*-data-jpa-test`, `*-security-test`, `*-webmvc-test`, etc.) are on the classpath — pick the slice that matches the unit rather than booting the full context.
- Routine repository/domain tests should use the lightest viable slice (`@DataJpaTest`); reserve full-context `@SpringBootTest @Testcontainers` tests for cross-engine migration proof or full-wiring checks. `UserRepositoryPostgresTests` is the canonical prod-engine (real Postgres) verification example.
- Access-control / IDOR / role tests use the shared scaffolding in `src/test/java/com/nextslope/support/` (`UserFixtures`, `TwoUserIntegrationTestBase`, `AccessControlAssertions`) — extend it, don't re-derive security setup. Recipe + reference tests: `@context/foundation/test-plan.md` §6.4.
- Browser e2e smoke tests live in the dedicated `e2eTest` source set (`src/e2eTest/java/`) and run with Playwright (`./gradlew e2eTest`); CI provisions Chromium first via `./gradlew playwrightInstall`.
- CI's merge gate is multi-tier: `./gradlew test`, then `./gradlew pitest` (recommendation engine mutation threshold), then Playwright browser smoke (`./gradlew playwrightInstall` + `./gradlew e2eTest`).

### E2E Testing Rules (Playwright-for-Java, `src/e2eTest/`)

- **Seed exemplar.** `HtmxSmokeE2eTests.java` is the seed test (`context/foundation/test-plan.md` §6.6) — model every new e2e test on it; do not scaffold a separate seed. New tests go in `src/e2eTest/java/com/nextslope/e2e/` (Playwright is scoped to `e2eTestImplementation`; e2e classes under `src/test/java` will not compile). Run one class with `./gradlew e2eTest --tests "com.nextslope.e2e.<Class>"` — `./gradlew test` never runs them.
- **Locators.** `page.getByRole(AriaRole.X, new Page.GetByRoleOptions().setName("..."))` / `getByLabel` / `getByText` for every user interaction; `getByTestId` only when accessibility attributes are ambiguous. Id/structural locators (e.g. `#recommend-results .row .col`, `tr[id^='resort-row-']`) are allowed only to *assert* HTMX swap structure — never to click, fill, or navigate.
- **Waits.** Never `page.waitForTimeout()`. `PlaywrightAssertions.assertThat(...)` auto-waits; for readiness/settle races use condition waits via `page.waitForFunction(...)` (see `awaitHtmxReady` / `awaitNextAfterSettle` in the seed). Never assert transient spinner visibility — assert the indicator's structural wiring instead.
- **Isolation & cleanup.** Each test seeds its own users via `UserFixtures` and tears them down FK-safely (visited rows → preference profile → user) in a `finally`-guarded teardown. Never delete seeded resorts — the seed loader only refills an empty table, so later contexts in the same JVM would lose the candidate set. Restore any toggled shared state (e.g. admin active-toggle) in-test.
- **Authentication.** At least one test must log in through the real form — it proves the server-rendered CSRF hidden input (Thymeleaf `th:action`) that MockMvc tests bypass with `.with(csrf())`. Subsequent tests may reuse session state (shared `BrowserContext` or `storageState`); real-form login is this project's accepted convention because the login path is itself part of the risk under test.
- **Assertions.** Assert the business outcome that would fail if the `test-plan.md` risk materialized (e.g. the `window.__e2eMarker` survival check proves an in-place swap, not a reload); verify with a deliberate break before trusting a green test. Name methods after the risk behavior (`visitedToggleSwapsAndHighlightsRowInPlace`), never `test1`.
- **App under test.** Boot via `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@LocalServerPort` on the default profile (in-memory H2 + Flyway + resort seed); build URLs from the injected port (`setBaseURL`). Mock nothing internal — auth, routing, controllers, and DB stay real; mock only expensive/non-deterministic external services.

## Persistence & Migrations

- **Flyway owns the schema.** Versioned SQL lives in `src/main/resources/db/migration/`, named `V{n}__snake_case_description.sql` (e.g., `V1__create_users.sql`). Flyway auto-runs on every app boot and test context start.
- **Forward-only, backward-compatible.** Neon free has no rollback — never edit an applied migration; add a new `V{n+1}__` file. No down/undo scripts.
- **Portable DDL only.** Migrations must parse identically on H2 (PostgreSQL mode, local) and Postgres (Neon/Testcontainers) — e.g., `BIGINT GENERATED BY DEFAULT AS IDENTITY`, `VARCHAR`, `TIMESTAMP`, `UNIQUE`. Avoid Postgres-only types/extensions and H2-only syntax.
- **`ddl-auto=validate` everywhere.** Hibernate never mutates the schema; it only verifies the entity↔schema mapping and fails fast on a mismatch. A new entity requires a matching migration.
- **Entity convention.** One package per domain (`com.nextslope.<domain>`), JPA `@Entity` mapping a Flyway-created table; populate audit columns via Hibernate `@CreationTimestamp`/`@UpdateTimestamp` (not DB defaults) so behavior is identical across engines. Use Lombok.
- **Prod connection split.** Runtime queries use the pooled Neon endpoint (`SPRING_DATASOURCE_URL`); Flyway uses the **DIRECT** (non-pooler) endpoint (`SPRING_FLYWAY_URL`, a required prod secret) — PgBouncer transaction mode breaks Flyway's advisory locks/DDL.
- **Verification standard.** Dual-engine: the H2 `@SpringBootTest` context test (`NextslopeApplicationTests`) proves the local path; `UserRepositoryPostgresTests` (`@Testcontainers`) proves the prod engine. Both run under `./gradlew test` and gate CI.

## Deployment & Configuration

- Target is Render (Free web tier, $0/mo) with an external Neon free Postgres and GitHub Actions auto-deploy-on-merge per `@context/foundation/infrastructure.md`. CI is wired in `.github/workflows/ci.yml` and runs `./gradlew test`, `./gradlew pitest`, `./gradlew playwrightInstall`, and `./gradlew e2eTest` on push/PR to `main`. This private GitHub Free repo does not have technical branch protection; green CI on PRs is a required process control before merge. Rationale: Render is the only candidate with a genuine $0 path, the lowest-regret choice for an MVP that may be discarded. Fly.io is the documented runner-up for cheapest always-on if cold starts become unacceptable; deploy via a multi-stage Dockerfile (no native Java runtime on Render).
- PostgreSQL (Neon free tier, external) and H2 (local) are both on the runtime classpath. Datasource config is wired: local uses H2 (see `application.properties`); prod uses Neon via `SPRING_DATASOURCE_URL` (pooled) + `SPRING_FLYWAY_URL` (DIRECT) Render secrets (see `application-prod.properties` and `render.yaml`). Spring Security is active via `SecurityConfig` (default form-login; every endpoint authenticated except the permit-listed public routes) — real user-backed auth lands in S-01.
