<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Three-Resort Recommendation (S-05)

- **Plan**: context/changes/three-resort-recommendation/plan.md
- **Scope**: Full plan (Phases 1–4 of 4)
- **Date**: 2026-06-28
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 3 observations

> Phases 1–3 were already reviewed and triaged (`reviews/impl-review-phase-{1,2,3}.md`); every
> "commit pending" fix from those reviews is confirmed landed in code (resync `@Transactional` on
> `run()`, null-`externalId` throw, batched `findAll`+`saveAll`, `@Primary` scorer, singularized
> single-region rationale, deferred sparse-branch tests). This full-plan sweep focuses on the
> previously-unreviewed Phase 4 (PIT mutation gate + refinement handoff) and cross-phase checks.

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Evidence Verified

- `./gradlew test` — green (full suite).
- `./gradlew pitest` — green; gate `mutationThreshold = 90` (`build.gradle:72`), achieved 94% per the refinement brief.
- CI: the PIT step is **blocking**, in the same `Test` job after `./gradlew test`, on the same `push`/`pull_request` → `main` triggers (`.github/workflows/ci.yml:32-33`).
- CSV: 150 rows, all `Continent=Europe`, 15 countries each ≥3 resorts.
- `refinement-brief.md` enumerates all 6 `ScoringConfig` knobs (weights, three hardness targets, rationale threshold) with defaults + guarding test names that all exist, and names research Open Questions 1–2.
- Scoring math safe: scorer divides only by literal constants; the one variable denominator is zero-guarded in `Resort.getDifficultyMix()`. No NPE in the hot path; all view output via auto-escaped `th:text`; `/recommend` authenticated, CSRF-on, principal-scoped (no IDOR).
- Pattern compliance: `RecommendController` mirrors `VisitedController`; `RecommendationService` matches owner-scoped `@Transactional(readOnly = true)` service conventions.

## Findings

### F1 — findByExternalId is now production-unused (only tests call it)

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/java/com/nextslope/resort/ResortRepository.java:14
- **Detail**: The plan's Phase 1 §2 added `Optional<Resort> findByExternalId(Long)` as the resync upsert seam. The Phase-1 F4 fix then rewrote `resync()` to load the catalog once via `findAll()` and index by `external_id` (ResortSeedLoader.java:81-86), so the finder is no longer called by any production path. It survives only because `ResortSeedLoaderTests` uses it as a test-assertion accessor (6 call sites). Harmless, but a planned seam the chosen implementation outgrew.
- **Fix**: Leave it (legitimately used by tests as a lookup helper) — optionally add a one-line comment that it exists for test reconciliation, not the resync path.
- **Decision**: FIXED (differently) — moved `findByExternalId` out of production `ResortRepository` into a new test-only `ResortTestRepository extends ResortRepository` (`src/test/java/com/nextslope/resort/ResortTestRepository.java`); `ResortSeedLoaderTests` now autowires it. Production repository surface no longer carries an unused finder. Full suite green.

### F2 — Recommendation unit tests execute twice in CI

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria (CI efficiency)
- **Location**: .github/workflows/ci.yml:27,33
- **Detail**: The `Test` job runs `./gradlew test` (full suite incl. `com.nextslope.recommendation.*`) then `./gradlew pitest`, whose `targetTests` (`com.nextslope.recommendation.*`) re-runs those same engine tests under instrumentation. The engine suite is small and fast, so the extra wall-clock is negligible; noted only as a real (cheap) duplication.
- **Fix**: Accept as-is — the two runs serve different purposes (suite gate vs mutation gate). No action recommended.
- **Decision**: SKIPPED — accepted; trivial cost, the two runs serve distinct gates.

### F3 — A zero-slope resort would render a 0/0/0 difficulty card (curation-guarded only)

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (Reliability)
- **Location**: src/main/java/com/nextslope/recommendation/RecommendationService.java:80-91
- **Detail**: `getDifficultyMix()` is zero-guarded against divide-by-zero, but a resort with zero total slopes would surface as a `(0,0,0)`-mix card. Only CSV curation (every seeded row has slope counts) prevents this today — there is no runtime guard in the recommendation path. Latent; not reachable with the curated dataset.
- **Fix**: No code change needed for S-05; if admin CRUD (S-06) ever allows a zero-slope resort, revisit whether such rows should be eligible candidates.
- **Decision**: SKIPPED — not reachable with the curated dataset; carry the awareness into S-06 admin CRUD.
