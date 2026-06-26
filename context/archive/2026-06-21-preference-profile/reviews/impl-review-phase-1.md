<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Preference Profile (S-02)

- **Plan**: context/changes/preference-profile/plan.md
- **Scope**: Phase 1 of 3 (Domain & Migration)
- **Date**: 2026-06-26
- **Commit**: b7cb28e
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 5 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Success Criteria Evidence

- `./gradlew compileJava` — pass
- `./gradlew test --tests "com.nextslope.profile.DifficultyBandTests"` — pass (canonical triples, sum to 100)
- `./gradlew test --tests "com.nextslope.profile.*"` — pass (@DataJpaTest round-trip + UNIQUE; Postgres proof)
- `./gradlew test --tests "*Postgres*"` — pass (V3 applies, entity validates on real Postgres)
- `./gradlew test` — full suite green (verified pre-commit)
- Manual: app boots locally via `./gradlew bootRun` (H2 path) — confirmed by user

## Findings

### F1 — EAGER fetch on the region @ElementCollection

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; obvious and narrowly scoped
- **Dimension**: Plan Adherence / Performance
- **Location**: src/main/java/com/nextslope/profile/PreferenceProfile.java:65
- **Detail**: Documented deviation — the plan did not specify a fetch type. Made `FetchType.EAGER` because the non-transactional `@SpringBootTest` Postgres round-trip surfaced a `LazyInitializationException`, and the region set is always read alongside the profile. Harmless for the single-row `findByUserId` path.
- **Fix**: Accept for MVP. Revisit to LAZY + fetch join only if bulk profile queries are introduced later.
- **Decision**: ACCEPTED (benign, documented)

### F2 — FK to users has no ON DELETE clause

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW
- **Dimension**: Data safety
- **Location**: src/main/resources/db/migration/V3__create_preference_profiles.sql:10
- **Detail**: Matches V1/V2 (no cascades anywhere yet). User deletion will FK-violate until S-07 explicitly removes the profile first.
- **Fix**: Intentional per plan — handle in S-07 (delete profile rows before the user, or add ON DELETE CASCADE in a forward migration).
- **Decision**: ACCEPTED (deferred to S-07)

### F3 — Region vocabulary not enforced at the DB layer

- **Severity**: 🔵 OBSERVATION
- **Dimension**: Data safety
- **Location**: src/main/java/com/nextslope/profile/PreferenceProfile.java:65-69
- **Detail**: `regionCountries` accepts arbitrary strings at persistence; out-of-vocabulary rejection is the Phase 2 service's responsibility (plan: app-layer validation, no DB CHECK).
- **Fix**: Enforce in `PreferenceProfileService.save()` (planned Phase 2); do not expose repository writes that bypass it.
- **Decision**: ACCEPTED (deferred to Phase 2)

### F4 — repository findByUserId has no owner binding

- **Severity**: 🔵 OBSERVATION
- **Dimension**: Security
- **Location**: src/main/java/com/nextslope/profile/PreferenceProfileRepository.java:9
- **Detail**: Structural isolation — `/profile` has no id path variable; the controller always resolves the authenticated principal's id. Owner-isolation integration test lands in Phase 3 (planned).
- **Fix**: Ensure the service/controller always passes the authenticated user's id; add the Phase 3 isolation test.
- **Decision**: ACCEPTED (test deferred to Phase 3)

### F5 — no test for FK violation on non-existent user_id

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW
- **Dimension**: Reliability
- **Location**: src/test/java/com/nextslope/profile/PreferenceProfileRepositoryTests.java
- **Detail**: Optional extra; `Resort` has no FK test either. The `UNIQUE(user_id)` test already exercises a constraint-violation path.
- **Fix**: Optionally add a one-line test mirroring the UNIQUE test.
- **Decision**: SKIPPED (optional, low value)
