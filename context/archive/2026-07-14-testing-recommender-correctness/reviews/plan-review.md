<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Recommender Correctness Suite

- **Plan**: `context/changes/testing-recommender-correctness/plan.md`
- **Mode**: Deep
- **Date**: 2026-07-16
- **Initial verdict**: REVISE
- **Verdict after triage**: SOUND
- **Findings**: 1 critical, 4 warnings, 0 observations — all fixed

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | PASS |
| Plan Completeness | PASS |

## Grounding

5/5 target paths grounded (3 existing, 2 intentionally new), 6/6 symbols/config keys verified, brief↔plan consistent.

## Findings

### F1 — Progress contract contradicts the phase criteria

- **Severity**: ❌ CRITICAL
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 3 Manual Verification; Phase 4 Success Criteria; Progress 3.3
- **Detail**: Phase 3 says deleting the `NEW_ONLY` filter should leave the `REVISIT_OKAY` test passing, while Progress 3.3 says the same test should fail. The code confirms it cannot fail because `REVISIT_OKAY` uses an empty visited set. Phase 4 also has an Automated Verification bullet (`N/A`) with no matching Progress item, violating the required Success Criteria ↔ Progress mapping.
- **Fix**: Make both Phase 3 locations run the all-visited or novelty differential test and expect failure; turn Phase 4's `N/A` bullet into non-checklist prose.
- **Decision**: FIXED — applied the proposed plan edit

### F2 — Direct composition bypasses the emitted-card seam

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: End-State Alignment
- **Location**: Phase 2 — Rationale truthfulness
- **Detail**: The four new tests call the scorer and rationale builder directly, while the only `RecommendationService` assertion is reduced to `isNotBlank()`. If `RecommendationService.toCard()` later passes the wrong `ScoreBreakdown` to the builder, every planned test can still pass. That leaves the overview's "emitted rationale corresponds to real scoring reasons" promise partially unguarded.
- **Fix**: Keep the four cheap composition cases, and add one service-level test using real scorer + builder that independently asserts the emitted top card's expected rationale axis.
  - Strength: Protects the exact scorer → service → card seam.
  - Tradeoff: Adds one test and a small amount of fixture duplication.
  - Confidence: HIGH — `toCard()` is the only omitted handoff.
  - Blind spot: None significant.
- **Decision**: FIXED — applied the proposed plan edit

### F3 — Region fixture does not guarantee its card delta

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Plan Completeness
- **Location**: Phase 1 — Region fixture
- **Detail**: A two-Austria/two-France pool becomes sparse after filtering to Austria, so its cards are empty and "France absent" is vacuous. With four unfiltered resorts but only three returned cards, the plan also gives no scoring/tie-break constraints guaranteeing both intended France IDs appear. Independent reevaluation confirmed the novelty fixture is already sound because its perfect-match resort is guaranteed a top-three place.
- **Fix**: Use three Austrian resorts plus one French resort with a uniquely higher score; assert normal recommendations in both runs and that the French ID is absent under the Austria filter but present without it.
  - Strength: Proves a real region membership change without sparse short-circuit or top-three ambiguity.
  - Tradeoff: Adds a small fixture table to Phase 1.
  - Confidence: HIGH — `recommend()` returns sparse below three survivors and limits normal results to three cards.
  - Blind spot: None once the exact IDs, names, and mixes are specified.
- **Decision**: FIXED — applied the revised, region-only plan edit after independent reevaluation

### F4 — Phase 3 repeats Phase 1's revisit-positive proof

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Lean Execution
- **Location**: Phase 3 — `revisitOkayIncludesAResort…`
- **Detail**: Phase 1's novelty A/B test already proves the same visited resort is excluded under `NEW_ONLY` and included under `REVISIT_OKAY` at the same service layer. The Phase 3 test adds no distinct failure signal.
- **Fix**: Remove the duplicate Phase 3 test and state that the three edges are covered across Phase 1 plus the two remaining Phase 3 tests.
- **Decision**: FIXED — applied the proposed plan edit

### F5 — PIT ratchet assumes the mutation score improves

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 5 — Mutation-threshold ratchet
- **Detail**: The current achieved score is 94% with a threshold of 90. New tests may close semantic gaps without killing additional mutants, but the phase only describes raising the threshold. It gives the implementer no valid branch when the measured score stays at or below the 94% baseline.
- **Fix**: Define a conditional policy, such as retaining four points of headroom with a floor of 90, and leave 90 unchanged while recording the result if the score does not exceed 94%.
  - Strength: Preserves empirical calibration without inventing an improvement the run did not demonstrate.
  - Tradeoff: The change may finish without a `build.gradle` edit.
  - Confidence: HIGH — PIT only enforces score ≥ threshold.
  - Blind spot: Equivalent-mutant classification could change even when the aggregate percentage stays constant.
- **Decision**: FIXED — user chose a different fix: retain threshold 90 and convert Phase 5 to PIT validation

## Triage Summary

- **Fixed**: F1, F2, F3, F4, F5
- **Skipped**: None
- **Accepted**: None
- **Dismissed**: None
- **Verdict after fixes**: SOUND
