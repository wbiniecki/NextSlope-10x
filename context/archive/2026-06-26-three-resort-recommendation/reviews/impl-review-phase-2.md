<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Three-Resort Recommendation (S-05)

- **Plan**: context/changes/three-resort-recommendation/plan.md
- **Scope**: Phase 2 of 4 (recommendation engine — filters + pluggable scorer + truthful rationale)
- **Date**: 2026-06-28
- **Commit**: 7aa1860
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

The engine is faithful to the plan: hard filters → pluggable `Scorer` (Approach A default) → deterministic `(-score, country, name, id)` ordering → explicit sparse / no-profile branches, with every tunable centralized in `ScoringConfig`. Owner-scoped by `userId`, `@Transactional(readOnly = true)`, no entity leakage, no web/persistence scope creep. Phase 2 automated criteria 2.1–2.5 re-verified green (full suite 36s); manual 2.6–2.8 correctly remain pending human click-through.

### Verified and cleared

- **Scoring math correct**: `alignDiff = 1 − L1/200` (L1 max 200 for two 100-sum mixes), `hardnessIndex = (0.5·med + hard)/100`, `alignExp = 1 − |H − target|`, all in `[0,1]`; the four `WeightedDistanceScorerTests` boundary cases check by hand.
- **Determinism by construction**: ranking sorts on precomputed `score` then `(country, name, id)` (a total order), never `HashSet`/`HashMap` iteration; `visited`/`regionCountries` membership-tested only. Tie-break test proves input-order independence.
- **Truthfulness gate sound**: region (satisfied hard filter) pinned at `1.0` always wins; difficulty/experience compete only above threshold with strict `>` so ties resolve deterministically (region → difficulty → experience); truthful fallback when none qualify.
- **Privacy/scope**: `recommend(Long userId)` principal-scoped (no addressable id), DTOs carry no entities, no Phase-3/4 leakage. `ScoreBreakdown` and `RecommendationConfig` are unlisted-but-justified glue, not drift.

## Findings

### F1 — Sparse-explanation string branches are untested

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria (test coverage)
- **Location**: src/main/java/com/nextslope/recommendation/RecommendationService.java:93-101 / src/test/java/com/nextslope/recommendation/RecommendationServiceTests.java:138-150
- **Detail**: `sparseExplanation()` has three uncovered conditional branches: 0-vs-(1/2) "couldn't find any" message, singular/plural ("resort"/"resorts"), and the NEW_ONLY-vs-region suggestion clause. The single sparse test asserts only `isSparse()` + explanation not-blank, so all three branches survive. These are exactly the mutants the Phase 4 PIT gate (scoped to `com.nextslope.recommendation.*`) is meant to kill — but that gate isn't wired yet, so today they're a coverage hole in branch-heavy, user-facing copy.
- **Fix**: Add two assertions — one NEW_ONLY/0-survivor case (asserts "couldn't find any" + "allowing revisits") and one region/1-survivor case (asserts singular "resort" + "widening your selected region") — or note as a deliberate Phase-4 PIT target.
- **Decision**: SKIPPED — deferred to the Phase-4 PIT gate.

### F2 — A second Scorer impl will break autowiring without a qualifier

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Architecture
- **Location**: src/main/java/com/nextslope/recommendation/RecommendationService.java:32 / src/main/java/com/nextslope/recommendation/WeightedDistanceScorer.java:16
- **Detail**: `RecommendationService` injects a single `Scorer scorer`. The plan's intent is that the refinement session can "swap or retune the algorithm." Today "swap" only works as replace — adding a second `@Component Scorer` alongside `WeightedDistanceScorer` would fail context startup with `NoUniqueBeanDefinitionException`. Not a defect now (one impl), but a sharp edge the refinement session will hit first.
- **Fix**: Either document "swap = replace the @Component" as the contract in the Phase 4 refinement-brief, or mark `WeightedDistanceScorer` `@Primary` now so a parallel impl can be introduced safely.
- **Decision**: FIXED — marked `WeightedDistanceScorer` `@Primary` (commit pending).

### F3 — Region rationale says "one of your selected regions" for a single region

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency (rationale truthfulness)
- **Location**: src/main/java/com/nextslope/recommendation/RationaleBuilder.java:33
- **Detail**: When the user picks exactly one country the clause reads "a strong fit in France, one of your selected regions" — "one of" implies a multi-region selection. Truthful but slightly awkward, and the PRD holds rationale wording to a truthfulness bar. Purely cosmetic.
- **Fix**: Singularize when `regionCountries.size() == 1` (e.g. "a strong fit in your selected region, France"), or leave as-is — cosmetic, optional.
- **Decision**: FIXED — singularized the region clause for single-region selections (commit pending).
