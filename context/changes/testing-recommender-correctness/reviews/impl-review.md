<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Recommender Correctness Suite

- **Plan**: context/changes/testing-recommender-correctness/plan.md
- **Scope**: Full plan (Phases 1–5 of 5, all complete)
- **Date**: 2026-07-16
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 3 warnings, 1 observation

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | WARNING |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | WARNING |

## Summary

A genuinely high-signal test-only change. Both review sub-agents independently re-derived every
fixture against the engine formulas and confirmed the three test files do what the plan intended:
the differential suite proves each of the four preference axes is wired via observable set/rank
deltas (never copied score doubles), and the truthfulness suite drives the *real*
`WeightedDistanceScorer` end-to-end and never hand-builds a `ScoreBreakdown` — structurally closing
the oracle/tautology gap the plan targeted (Risk #1). Production code, `build.gradle`, CI, and the
PIT threshold (90) are all correctly untouched; `./gradlew test` and `./gradlew pitest` (94%) pass.

Three warnings keep this from a clean APPROVED: one benign-but-declared-out-of-scope docs edit in
Phase 4, an inaccurate manual-verification checkmark tied to it, and one under-verifying test that
names a projection contract it only presence-checks.

## Findings

### F1 — Phase 4 edited §3 Phase 2 Status, which "What We're NOT Doing" forbade

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Scope Discipline
- **Location**: context/foundation/test-plan.md:95 (§3 rollout table, Phase 2 row)
- **Detail**: Commit `df89de6` (Phase 4 docs reconciliation) changed the §3 Phase 2 row's **Status**
  cell `not started` → `change opened` and the **Change folder** cell `—` →
  `context/changes/testing-recommender-correctness/`. The plan's "What We're NOT Doing" explicitly
  states: *"Not touching test-plan.md §3's Phase 2 Status field or §8 Freshness Ledger — those are
  the test-rollout orchestrator's responsibility."* The edit is factually accurate (the change *was*
  opened) and arguably improves the doc, but it crosses a declared scope boundary. (§8 Freshness
  Ledger was correctly left untouched.)
- **Fix A ⭐ Recommended**: Keep the §3 update and reconcile the record — treat it as an accepted
  addendum and correct the manual-criterion wording (see F2), since reverting would make §3
  contradict on-disk reality (the change folder exists).
  - Strength: Preserves an accurate doc; avoids re-introducing a stale "not started" row.
  - Tradeoff: Formalizes a small scope expansion after the fact.
  - Confidence: HIGH — the row content matches reality and the orchestrator would set the same value.
  - Blind spot: If `/10x-test-plan` later rewrites §3 from its own state model, it may overwrite this.
- **Fix B**: Revert the §3 Phase 2 row to `not started` / `—`, restoring strict scope and leaving
  §3 to the orchestrator.
  - Strength: Honors the plan's boundary literally; single-purpose Phase 4 commit.
  - Tradeoff: §3 then understates reality until the orchestrator runs.
  - Confidence: MEDIUM — depends on when the orchestrator next runs.
  - Blind spot: Another edit/commit needed; churn for a benign row.
- **Decision**: FIXED via Fix A — §3 update accepted as addendum; kept in place, reconciled by F2's 4.1 wording correction.

### F2 — Manual criterion 4.1 marked complete but its scope claims don't hold

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: context/changes/testing-recommender-correctness/plan.md (Progress row 4.1) ; context/foundation/test-plan.md:130
- **Detail**: Progress row 4.1 is checked `[x] — df89de6` and asserts *"§3 Status and §8 Freshness
  Ledger untouched"* and *"no stale 'not wired'/'deferred' PIT language remains."* Both are
  partially false: §3 Status **was** touched (F1), and `test-plan.md:130` (the §4 "Mutation:"
  stack-grounding bullet) still reads *"wiring deferred to S-05 …"* — corrected only by an appended
  *"— update: wiring subsequently landed …"* clause rather than a replacement. The substantive §4
  table row, §5 gate row, and §6.5 cookbook bullet were reconciled correctly; this is a
  verification-accuracy gap, not a functional failure.
- **Fix**: Amend row 4.1's wording to reflect what actually happened (§3 Phase 2 row updated to
  `change opened`; line 130 carries a trailing correction), and optionally tighten line 130 to drop
  the residual "deferred to S-05" phrasing so the "no deferred language remains" claim is literally
  true. (If F1 Fix B is chosen instead, 4.1's §3 claim becomes true and only the line-130 tidy-up
  remains.)
- **Decision**: FIXED — plan.md row 4.1 reworded to state the §3 update as an accepted addendum; test-plan.md:130 rewritten to drop the residual "deferred to S-05" phrasing.

### F3 — `cardsCarryTheExpectedViewFacts` presence-only assertions under-verify the projection

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/test/java/com/nextslope/recommendation/RecommendationServiceTests.java:295,298,299
- **Detail**: The test names a "faithfully projects the resort's view facts" contract but three of
  five field checks are presence-only: `top.name()).isNotBlank()`, `top.difficultyMix()).isNotNull()`,
  `top.rationale()).isNotBlank()`. It never pins `top.id()`, and all three fixtures share
  `country="Austria"` and `totalLifts==20`, so those two exact checks pass for *any* survivor. A
  projection regression that carried the wrong resort's name/difficultyMix (non-blank/non-null) would
  slip through. Mitigating: `isNotNull` still kills a `getDifficultyMix()→null` mutant, and rationale
  *truthfulness* is (correctly) proven elsewhere. This is the exact "not-null/not-blank without
  identity or content" pattern the test-plan §6.5 review criteria warn about.
- **Fix**: Pin the deterministic winner — e.g. `assertThat(top.id()).isEqualTo(1L)`,
  `assertThat(top.name()).isEqualTo("Solden")`, and assert the exact `difficultyMix()` — so the
  view-facts contract is verified by identity, not mere presence.
- **Decision**: FIXED — pinned `top.id()==1L`, `name()=="Solden"`, `difficultyMix()==new DifficultyMix(60,30,10)` (added the import); rationale kept as smoke-check. `RecommendationServiceTests` green.

### F4 — Truthfulness fallback assertion duplicates a production literal (brittle to copy-edits)

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/test/java/com/nextslope/recommendation/ScorerRationaleTruthfulnessTests.java:91
- **Detail**: The both-sub-threshold case asserts the full generic-fallback sentence literal
  ("one of the closest matches to your overall preferences"), so a copy-edit of that sentence breaks
  the test. This matches the existing repo convention (other tests assert `"couldn't find any"` etc.)
  and is an expected-value contract, not a tautology — acceptable as-is. Noted only so a future
  wording change knows to update this assertion.
- **Decision**: SKIPPED — acceptable expected-value contract consistent with repo convention.

## Automated verification (this session)

- `./gradlew test` — BUILD SUCCESSFUL (full suite green).
- `./gradlew pitest` — passes at achieved 94% (63/67) against retained `mutationThreshold = 90`;
  targeted scorer/filter/rationale classes at 100%.

## Manual verification review

- Phases 1–3 manual items (1.3, 2.5–2.7, 3.3): supported by the diff (real scorer/builder, no
  hand-crafted breakdowns, exact-wording edge assertions). Verified.
- Phase 4 item 4.1: marked complete but partly inaccurate — see F2.
- Phase 5 items (5.1–5.3): verified this session by an independent sub-agent (6/6 checks pass).
