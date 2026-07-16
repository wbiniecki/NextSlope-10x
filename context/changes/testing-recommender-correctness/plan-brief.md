# Recommender Correctness Suite — Plan Brief

> Full plan: `context/changes/testing-recommender-correctness/plan.md`
> Research: `context/changes/testing-recommender-correctness/research.md`

## What & Why

Close the three test-plan Phase 2 gaps (Risks #1–#3) in the already-shipped recommendation engine:
no single-axis differential proof for any of the four preference axes, no test tying the emitted
rationale to a *real* (not hand-crafted) score, and three untested edges in the visited/new-only hard
filter. Plus reconcile stale "PIT not wired" documentation and validate the new coverage against the
existing PIT threshold of 90. This is a **test-only** change — no production code moves.

## Starting Point

The recommendation engine (S-05) is fully built and stable, with 38 existing test methods covering
the happy path, sparse/exact-three paths, tie-breaking, and scorer math, plus two flow-level browser
guardrails that explicitly defer their "deeper half" to this phase. The PIT mutation gate is already
wired and CI-blocking (contrary to stale `test-plan.md` language) at threshold 90 (94% first-run
score).

## Desired End State

Every preference axis has a test proving it's actually wired into scoring or filtering. The
rationale-truthfulness contract is proven against a real, hand-verified `ScoreBreakdown` rather than
a hand-crafted one, including the service-to-card handoff. Three specific visited/new-only edges are
explicitly covered. `test-plan.md` accurately describes the PIT gate as live; the threshold remains
90 and the achieved score plus survivor review are recorded.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Truthfulness test layer (Risk #1) | Both, split by concern | Direct plain-JUnit Scorer+RationaleBuilder composition covers the full contract-case matrix cheaply; one service-level guard protects the emitted-card handoff | Plan review |
| Differential test layer (Risk #2) | Service-level only | That's the actual gap research identified; a scorer-level companion would duplicate existing exact-value scorer tests without new signal | Research + Plan |
| Test file organization | Dedicated classes per concern | Matches the project's behavior-named-test-class convention (`PermitListLockTests`, `RoleGatingPatternTests`) and isolates each new fixture pattern for reuse | Plan |
| Weak existing test (`cardsCarryViewFactsAndATruthfulRationale`) | Split into two single-purpose tests | Each test should assert only what its name promises — no overclaiming | Plan |
| `test-plan.md` reconciliation scope | Minimal fix + clarify §5 gate row | Prevents a future reader from thinking the PIT gate is still aspirational | Plan |
| Mutation threshold | Retain 90; measure and review | Keep the established gate while recording the achieved score and resolving or documenting every survivor | User triage |

## Scope

**In scope:**
- 4 single-axis differential tests (region, novelty, difficulty band, experience level)
- 4 rationale-truthfulness composition tests + 1 service-handoff guard + 1 renamed/slimmed existing test
- 3 visited/new-only edges covered across the novelty differential and 2 dedicated edge-case tests
- `test-plan.md` §4/§5/§6.5 docs reconciliation
- PIT validation, achieved-score recording, and survivor review at `mutationThreshold = 90`

**Out of scope:**
- Any production code change (engine is correct and locked per the S-05 refinement brief)
- Re-tuning scoring weights, hardness targets, or the 0.6 rationale threshold
- A scorer-level companion test for the soft-axis differentials
- Re-testing the two flow-level e2e journeys already on `main`
- `test-plan.md` §3 Status field / §8 Freshness Ledger edits (orchestrator's job)

## Architecture / Approach

Two new plain-JUnit test classes (`RecommendationAxisDifferentialTests`, `ScorerRationaleTruthfulnessTests`)
plus targeted additions to the existing `RecommendationServiceTests`, all following the recommender
package's established no-Spring-context convention so they stay fast under PIT's per-mutant re-run
model. A final docs-only phase and a PIT-validation phase close out the change.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Single-axis differentials | Proof all 4 axes are wired into scoring/filtering | Fixture math must produce a clean, unambiguous flip — verified by hand in the plan |
| 2. Rationale truthfulness | Real-scorer-backed truthfulness contract, service-handoff guard, slimmed weak test | Must not silently reintroduce hand-crafted breakdowns |
| 3. Hard-filter edges | 2 remaining visited/new-only gaps closed; Phase 1 covers positive inclusion | Deliberate-break check needed to confirm tests aren't tautological |
| 4. Docs reconciliation | Accurate PIT status in `test-plan.md` | Scope creep into orchestrator-owned fields |
| 5. PIT validation | Score and survivors recorded at retained threshold 90 | Genuine surviving mutants must strengthen tests before completion |

**Prerequisites:** None — the engine is already built; this is purely additive test authoring.
**Estimated effort:** ~1 session across 5 phases (no production code, all fixtures pre-derived in the plan).

## Open Risks & Assumptions

- The hand-computed fixture math in the plan (differential pairs, truthfulness cases) assumes
  `Resort.getDifficultyMix()` returns slope counts unchanged when they already sum to 100 — verified
  against the actual largest-remainder implementation, not assumed.
- The aggregate PIT score may stay at 94% even when the semantic coverage improves; Phase 5 records
  the measured result and reviews survivor identity rather than inferring value from percentage alone.

## Success Criteria (Summary)

- `./gradlew test` and `./gradlew pitest` both pass with the new/extended test suites.
- Every one of the four preference axes has a passing differential test; the rationale contract is
  proven against a real (not hand-crafted) score; the three visited/new-only edges are covered.
- `test-plan.md` no longer describes the PIT gate as unwired.
