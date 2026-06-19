<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Persistence & Migration Baseline

- **Plan**: context/changes/persistence-migration-baseline/plan.md
- **Scope**: All phases (1–3 of 3)
- **Date**: 2026-06-18
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 4 warnings, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | WARNING |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | WARNING |

Automated success criteria re-confirmed green this session: `compileJava/compileTestJava`, `NextslopeApplicationTests` (H2), full `./gradlew test`, `UserRepositoryPostgresTests` (Testcontainers Postgres), CI on `main` (user-confirmed). Entity↔schema `validate` parity holds on both engines.

## Findings

### F1 — H2 console disabled by default; dev security chain is dormant

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (Reliability)
- **Location**: src/main/resources/application.properties
- **Detail**: H2 console auto-config is gated by `@ConditionalOnBooleanProperty("spring.h2.console.enabled")` (verified in `spring-boot-h2console-4.0.6.jar`; `matchIfMissing=false`). The property is absent locally, so the console servlet is never registered and the `/h2-console/**` SecurityFilterChain is dead — the console won't load despite the security work.
- **Fix**: Add `spring.h2.console.enabled=true` to `application.properties` (prod already pins it to `false`, so the local override is safe).
- **Decision**: FIXED

### F2 — Unplanned, uncommitted SecurityConfig change dangling in the tree

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Scope Discipline
- **Location**: src/main/java/com/nextslope/config/SecurityConfig.java
- **Detail**: The dev-only `@Profile("!prod")` `/h2-console/**` filter chain is not in the F-01 plan and sits uncommitted. Correctly gated (no prod exposure, no guardrail breach) but a dangling EXTRA, and it was NOT part of what CI validated (CI ran the committed original config).
- **Fix A ⭐ Recommended**: Commit it as its own small change together with the F1 fix, so the H2-console dev tooling lands complete and intentional.
  - Strength: Ships a working, self-contained dev feature; keeps F-01 history clean.
  - Tradeoff: One more commit/change folder.
  - Confidence: HIGH — change is small and self-contained.
  - Blind spot: None significant.
- **Decision**: FIXED via Fix A (commit H2-console work with F1)
- **Fix B**: Revert it and defer the H2-console convenience entirely.
  - Strength: Strictest scope discipline; nothing extra to maintain.
  - Tradeoff: Loses the dev tooling.
  - Confidence: MEDIUM — depends whether the console is still wanted.
  - Blind spot: None significant.

### F3 — Manual check 2.4 marked done, but committed config can't serve the console

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: context/changes/persistence-migration-baseline/plan.md (2.4)
- **Detail**: Progress row 2.4 ("H2 console shows users + flyway_schema_history") is `[x]`, but per F1 the console isn't reachable in the committed config. The fact it intends to prove — V1 applies to H2 — is independently proven by `NextslopeApplicationTests` + `ddl-auto=validate`, so the substance holds; only the console-observation evidence is shaky (rubber-stamp risk).
- **Fix**: Apply F1, re-verify the console shows `USERS` + `FLYWAY_SCHEMA_HISTORY`, then leave 2.4 checked with genuine evidence.
- **Decision**: DISMISSED — SecurityConfig dev chain was present locally when 2.4 was checked; the console observation was genuine. 2.4 stays validly checked.

### F4 — Deprecated Testcontainers String constructor

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (Reliability)
- **Location**: src/test/java/com/nextslope/user/UserRepositoryPostgresTests.java:21
- **Detail**: `new PostgreSQLContainer<>("postgres:16-alpine")` uses the deprecated String constructor (build emits a deprecation note).
- **Fix**: `new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))` (import `org.testcontainers.utility.DockerImageName`).
- **Decision**: FIXED

### F5 — Testcontainers dependency coordinate naming differs from plan

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW
- **Dimension**: Plan Adherence / Pattern Consistency
- **Location**: build.gradle:43-44
- **Detail**: Deps are `org.testcontainers:testcontainers-junit-jupiter` / `testcontainers-postgresql` vs the plan's `junit-jupiter` / `postgresql`. They resolve under SB4 dependency management and CI is green, so intent is met.
- **Fix**: None needed.
- **Decision**: ACKNOWLEDGED — no action; intent met, CI green.

### F6 — H2 console security matcher assumes the default path

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/nextslope/config/SecurityConfig.java:24
- **Detail**: `.securityMatcher("/h2-console/**")` tracks the default `spring.h2.console.path`. If a custom path is ever set, the matcher silently stops covering it. Fine for MVP.
- **Fix**: Optionally add a comment that it tracks the default `spring.h2.console.path`.
- **Decision**: SKIPPED — acceptable for MVP.
