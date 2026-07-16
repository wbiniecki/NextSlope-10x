<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Recommender Correctness Suite

- **Plan**: context/changes/testing-recommender-correctness/plan.md
- **Scope**: Phase 1 of 5 (Single-axis differential tests, Risk #2)
- **Date**: 2026-07-16
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 0 observations
- **Commit under review**: cef8068

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Findings

None. Phase 1 is a clean, test-only change that matches the plan exactly.

## Evidence

### Plan Adherence — PASS
- New class `src/test/java/com/nextslope/recommendation/RecommendationAxisDifferentialTests.java` added exactly as specified, with one differential test per axis: region, novelty, difficulty band, experience level.
- Hard-filter axes (region, novelty) assert a **candidate-set** delta (`doesNotContain`/`contains`); soft-scored axes (difficulty, experience) assert an **ordering** delta (`cardIds(...).get(0)`), matching the plan's "observable that axis's stage can actually produce".
- Real `WeightedDistanceScorer` + `RationaleBuilder` are constructed via `ScoringConfig.defaults()`; only the three repository/service boundaries are mocked — per the plan contract.

### Fixture arithmetic — independently verified
Recomputed each fixture from the real formulas (`alignDiff = 1 − L1/200`, `hardnessIndex = (0.5·med + hard)/100`, `alignExp = 1 − |hardnessIndex − target|`, `score = 0.5·alignDiff + 0.5·alignExp`; targets BEGINNER 0.20 / INTERMEDIATE 0.45 / ADVANCED 0.70). All slope counts sum to 100 so `getDifficultyMix()`'s largest-remainder rounding is exact.
- **Region**: Austrian 10/30/60 → 0.475; Chamonix 60/30/10 → 0.975. `{Austria}` → 3 Austrian survivors (recs), id 4 absent; `{}` → id 4 is unique top scorer, present.
- **Novelty**: id 1 (Chamonix, 0.975) is the visited resort; NEW_ONLY drops it leaving 3 survivors (recs, id 1 absent), REVISIT_OKAY keeps it (id 1 top, present).
- **Difficulty**: top pick id 1 (0.90) @ MOSTLY_EASY vs id 3 (0.85) @ MOSTLY_HARD; alignExp constant across runs.
- **Experience**: top pick id 1 (0.82) @ BEGINNER vs id 2 (0.865) @ ADVANCED; alignDiff constant across runs.
Every assertion matches the computed result, and each test genuinely flips (would fail if its axis were unwired).

### Scope Discipline — PASS
- Commit `cef8068` touches only the new test file plus the change folder's own docs (change.md, plan.md, research.md, plan-brief.md, reviews/plan-review.md). No production code changed, honoring "What We're NOT Doing".

### Safety & Quality — PASS
- Test-only change; no security, performance, reliability, or data-safety surface.

### Architecture — PASS
- Plain JUnit 5 + Mockito + AssertJ, no Spring context — matches the recommender-package convention and keeps PIT fast (Performance Considerations honored).

### Pattern Consistency — PASS
- Mirrors `RecommendationServiceTests` scaffolding (`@Mock` fields, lazy `service()`, `resort(...)` builder helper). The 4-arg `profile(experience, band, novelty, regions)` and `givenCatalog`/`recommendWith` helpers diverge from the sibling's 2-arg `profile(novelty, regions)` / `givenProfile`, but this is a necessary adaptation because differential tests must vary experience and band — not a violation. Helper duplication across the two files matches the existing per-file convention (shared scaffolding under `src/test/java/com/nextslope/support/` is scoped to access-control tests).

### Success Criteria — PASS
- **1.1** `./gradlew test --tests com.nextslope.recommendation.RecommendationAxisDifferentialTests` → 4 tests, 0 failures, 0 errors (verified via `build/test-results/test/TEST-...RecommendationAxisDifferentialTests.xml`).
- **1.2** `./gradlew test` (full suite) → BUILD SUCCESSFUL.
- **1.3** (manual) Confirmed each test uses the real scorer/filter (no `Scorer` mock) and asserts a set/ordering delta, never a copied exact score number — the §6.5 anti-pattern is avoided.
