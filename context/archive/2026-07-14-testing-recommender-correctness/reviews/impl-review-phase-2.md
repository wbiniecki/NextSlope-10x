<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Recommender Correctness Suite

- **Plan**: context/changes/testing-recommender-correctness/plan.md
- **Scope**: Phase 2 of 5 (Rationale truthfulness vs. real scoring, Risk #1)
- **Date**: 2026-07-16
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 2 observations
- **Commit under review**: e469cc3

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

### F1 — Rationale assertions couple to literal builder text

- **Severity**: 🟦 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/test/java/com/nextslope/recommendation/ScorerRationaleTruthfulnessTests.java:91 (and :67, :80, :94)
- **Detail**: The both-sub-threshold case asserts on the literal fallback string `"one of the closest matches to your overall preferences"` and the region-absence checks use the literal `"selected region"`. These substrings are copied from `RationaleBuilder`'s emitted text, so a future reword of the builder copy would require a matching test edit. This is an accepted trade-off: the sibling `RationaleBuilderTests` already asserts on literal builder phrases (e.g. `"the closest available match in your selected regions"`, `"one of your selected regions"`), so the new class is consistent with the established convention, and the axis-label assertions correctly go through `getLabel()` rather than literals.
- **Fix**: None required — matches existing sibling convention. If desired later, extract the fallback/region phrases into shared test constants or expose them from the builder, but only if the copy starts churning.
- **Decision**: PENDING

### F2 — `cardsCarryTheExpectedViewFacts` retains a non-blank rationale smoke check

- **Severity**: 🟦 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/test/java/com/nextslope/recommendation/RecommendationServiceTests.java:299
- **Detail**: The renamed view-facts test keeps `assertThat(top.rationale()).isNotBlank();`. This is intentional and plan-specified — the plan directs keeping the `isNotBlank()` line as a minimal smoke check (a rationale is always produced), a concern distinct from *truthfulness*, which now lives in `ScorerRationaleTruthfulnessTests`. The updated comment documents the narrowed scope and the former over-claim. Noted only so a future reader doesn't mistake the retained line for leftover truthfulness scope.
- **Fix**: None — behaves exactly as the plan intends.
- **Decision**: PENDING

## Evidence

### Plan Adherence — PASS
- **New `ScorerRationaleTruthfulnessTests`**: composes the real `WeightedDistanceScorer` + real `RationaleBuilder` via `ScoringConfig.defaults()`; every breakdown comes from `scorer.score(resort.getDifficultyMix(), profile)` through the `realRationale(...)` helper. A grep for `new ScoreBreakdown` returns zero matches in this class (only the sibling `RationaleBuilderTests` hand-crafts breakdowns). All four fixture-table cases present with independently re-derived alignments (difficulty-wins 1.0/0.80; experience-wins 0.50/1.0; both-sub-threshold 0.33/0.45; region-unbeatable real 1.0 tie) and correct `getLabel()` assertions. Cases 1–3 assert absence of region wording (`doesNotContain("selected region")`); case 4 correctly omits that check.
- **`RecommendationServiceTests`**: `cardsCarryViewFactsAndATruthfulRationale` renamed to `cardsCarryTheExpectedViewFacts` with a narrowed comment and the retained `isNotBlank()` smoke check; new `cardsUseTheRealScorerBreakdownForRationale` uses the real scorer+builder (repos mocked), the difficulty-wins profile + 60/30/10 winner + two lower fillers (independently re-derived scores 0.90 / 0.60 / 0.275), and asserts on the emitted `ResortCard` (`result.cards().get(0)`), not a directly invoked builder.

### Scope Discipline — PASS
- Commit `e469cc3` touches only `plan.md` (Progress ticks) and the two test files — no production code, honoring "What We're NOT Doing". Exactly four `@Test` methods in the new class, exactly one added + one renamed in the service test — no scope creep, no omissions.

### Safety & Quality — PASS
- Pure in-memory unit tests with fictional fixtures; no secrets, no data-safety or reliability surface. Each rationale assertion pairs a positive `contains` with a non-vacuous `doesNotContain` on the competing axis, so no test can pass vacuously. Plain JUnit (no Spring context) keeps the PIT target fast.

### Architecture — PASS
- Correct layering: direct scorer+builder composition for the pure truthfulness contract, service-level test for the `toCard()` handoff. No new abstractions, no boundary violations.

### Pattern Consistency — PASS
- Tabs, package-private `*Tests` class, AssertJ `assertThat`, Mockito scoped to only the three repository/service collaborators with the real scorer+builder, behavior-descriptive method names. Helper duplication across per-file recommender tests is the accepted project convention (shared scaffolding under `src/test/java/com/nextslope/support/` is scoped to access-control tests).

### Success Criteria — PASS
- **2.1/2.2/2.3** `./gradlew test --tests ScorerRationaleTruthfulnessTests --tests RecommendationServiceTests` → BUILD SUCCESSFUL.
- **2.4** Full suite `./gradlew test` → BUILD SUCCESSFUL.
- **2.5–2.7** (manual) Independently verified by sub-agent: no hand-crafted breakdown; labels match real `getLabel()` strings; the handoff guard derives its expected axis from fixture arithmetic and asserts the emitted card — not rubber-stamped.
