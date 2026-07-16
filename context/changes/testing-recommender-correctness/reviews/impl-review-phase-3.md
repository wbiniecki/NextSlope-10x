<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Recommender Correctness Suite

- **Plan**: context/changes/testing-recommender-correctness/plan.md
- **Scope**: Phase 3 of 5 (Visited/new-only hard-filter edges, Risk #3)
- **Date**: 2026-07-16
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 3 observations
- **Commit under review**: b07ebe1

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Evidence

### Plan Adherence — PASS
Both mandated Phase 3 tests were added to `RecommendationServiceTests.java` exactly as specified, matching the planned profile, stubs, and assertions:
- `allVisitedCandidatesUnderNewOnlyYieldsZeroSurvivorSparseWithRevisitSuggestion` — NEW_ONLY, no region filter, three active resorts, `visitedResortIds` stubbed to all three ids; asserts `isSparse()`, empty cards, and the explanation contains both `"couldn't find any"` and `"allowing revisits"`.
- `emptyVisitedListUnderNewOnlyBehavesLikeNoVisitedResorts` — NEW_ONLY, no region filter, three resorts, `visitedResortIds` explicitly stubbed to `Set.of()`; asserts `isRecommendations()` and all three ids represented.

The asserted strings are the exact substrings produced by the unchanged `RecommendationService.sparseExplanation()` (survivors==0 branch + NEW_ONLY suggestion branch, `RecommendationService.java:93-101`). The third identified edge (REVISIT_OKAY positive inclusion at the unit layer) is intentionally deferred to Phase 1's novelty differential per the plan's Phase 3 Overview — its absence here is by design, not a gap.

### Scope Discipline — PASS
Commit `b07ebe1` touches only `RecommendationServiceTests.java` (+38 lines = the two tests) plus the change folder's own `plan.md`/`change.md` bookkeeping. No production code changed, honoring "What We're NOT Doing". No scope creep — the two other real-scorer tests in the file belong to Phase 2 (`e469cc3`).

### Safety & Quality — PASS
Test-only change. Both tests trace-verify against production logic, exercise the real filter + scorer (only repositories/services mocked — no `Scorer` mock), assert business outcomes that genuinely fail on regression (independently confirmed by the deliberate-break check for 3.3), and are deterministic.

### Architecture — PASS
Plain JUnit 5 + Mockito + AssertJ under `MockitoExtension`, no Spring context — matches the recommender-package convention and keeps PIT fast (Performance Considerations honored).

### Pattern Consistency — PASS
Both tests reuse the class's established scaffolding (`profile(...)`, `givenProfile(...)`, `resort(...)` via mocked `resortRepository`, `cardIds(...)`, real `WeightedDistanceScorer` + `RationaleBuilder`); no new helpers. Naming, stubbing, comment, and AssertJ style match the sibling tests.

### Success Criteria — PASS
- **3.1** `./gradlew test --tests com.nextslope.recommendation.RecommendationServiceTests` → 17 tests, 0 failures (both new tests present in `TEST-...RecommendationServiceTests.xml`).
- **3.2** `./gradlew test` (full suite) → BUILD SUCCESSFUL.
- **3.3** (manual) Deliberate-break check independently verified: with the `NEW_ONLY` visited-filter line commented out, `allVisitedCandidatesUnderNewOnlyYieldsZeroSurvivorSparseWithRevisitSuggestion` fails at `RecommendationServiceTests.java:341`; restored and re-confirmed green with an empty diff on `RecommendationService.java`.

## Findings

### F1 — `containsExactlyInAnyOrder` in the empty-visited-list test

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/test/java/com/nextslope/recommendation/RecommendationServiceTests.java:361
- **Detail**: The test uses `containsExactlyInAnyOrder(1L, 2L, 3L)` — marginally stronger than the plan's "all three are represented", and correctly order-agnostic because this is a membership/no-op-filter edge check, not a ranking check (ranking is covered by the dedicated `containsExactly` tests). A strengthening, not a deviation.
- **Fix**: None required.
- **Decision**: PENDING

### F2 — Chained `.contains(...)` assertion

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Quality
- **Location**: src/test/java/com/nextslope/recommendation/RecommendationServiceTests.java:343
- **Detail**: `assertThat(result.explanation()).contains("couldn't find any").contains("allowing revisits")` chains two substring checks — a standard AssertJ idiom that asserts both the zero-survivor wording and the NEW_ONLY escape in one statement. Correct and readable.
- **Fix**: None required.
- **Decision**: PENDING

### F3 — Partial fixture overlap with sibling tests

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/test/java/com/nextslope/recommendation/RecommendationServiceTests.java:326-362
- **Detail**: The two new tests share NEW_ONLY fixtures with `newOnlyNoveltyExcludesVisitedResorts` and `sparseExplanationSuggestsAllowingRevisitsForNewOnlyUsers`, but each targets a distinct, previously untested edge (zero-survivor-via-visited-exhaustion; explicit empty-set semantics), so the overlap is justified coverage, not duplication.
- **Fix**: None required.
- **Decision**: PENDING
