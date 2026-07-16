---
date: 2026-07-14T18:10:53+02:00
researcher: Wojciech Biniecki
git_commit: 74ec2287b45005df73d18fec58edd6675ff194b6
branch: main
repository: wbiniecki/NextSlope-10x
topic: "Recommender correctness suite (test-plan rollout Phase 2) — map existing coverage to Risks #1–#3 and isolate the true gaps"
tags: [research, codebase, testing, recommendation, pitest, rationale, hard-filter]
status: complete
last_updated: 2026-07-14
last_updated_by: Wojciech Biniecki
---

# Research: Recommender correctness suite (test-plan rollout Phase 2)

**Date**: 2026-07-14T18:10:53+02:00
**Researcher**: Wojciech Biniecki
**Git Commit**: 74ec2287b45005df73d18fec58edd6675ff194b6 (clean `main`; only working-tree changes are the `test-plan.md` Phase-2 status edit and this change folder)
**Branch**: main
**Repository**: wbiniecki/NextSlope-10x

Permalink base: `https://github.com/wbiniecki/NextSlope-10x/blob/74ec2287b45005df73d18fec58edd6675ff194b6/`

## Research Question

Ground test-plan rollout Phase 2 ("Recommender correctness suite", Risks #1–#3, unit + integration + PIT scoped to `com.nextslope.recommendation.*`): **confirm** the pre-verified state from `change.md` (PIT already wired; substantial S-05 coverage exists; two flow-level e2e guardrails shipped), map every existing recommender test to Risks #1–#3, analyze the engine internals that determine what a truthful/differential oracle can assert, and isolate the true remaining gaps so the plan targets only those.

## Summary

**All three pre-verified claims are confirmed.** The PIT gate is fully wired and CI-blocking (`build.gradle:114-122`, `.github/workflows/ci.yml:39-40` — threshold 90, first green run scored 94%); test-plan §4/§6.5 "not wired today" language is stale and must be reconciled in this change. Substantial S-05 coverage exists (38 recommendation-related test methods inventoried), and both flow-level e2e guardrails are on `main` with JavaDoc explicitly deferring the deeper halves to this phase.

**The true gaps are exactly three, and they are narrower than the risk names suggest:**

1. **Risk #2 — no single-axis differential test exists for *any* of the four axes.** Existing tests prove each filter/scorer piece one-sidedly (one profile, assert output property) but never run the A/B pattern from §6.5: change exactly one axis, hold everything else fixed, assert the candidate set or ordering changes. Crucial architectural fact for designing these: only **difficulty band** and **experience level** enter the weighted score; **region** and **novelty** are hard filters only. So differential tests must assert *ordering* changes for the two soft axes and *candidate-set* changes for the two filter axes — an ordering assertion for novelty would be structurally impossible.
2. **Risk #1 — no test ties the rationale to a real scorer-produced `ScoreBreakdown`.** `RationaleBuilderTests` (10 methods) prove the builder's selection/threshold/priority rules but every test injects a hand-crafted breakdown — tautological with respect to "corresponds to the resort's real scoring reasons". The one service-level test with "truthful" in its name (`cardsCarryViewFactsAndATruthfulRationale`) only asserts the rationale `isNotBlank()`. The missing piece is an integration between the real `WeightedDistanceScorer` and `RationaleBuilder`: derive the expected axis independently from profile + resort mix arithmetic, then assert the emitted rationale names it.
3. **Risk #3 — the edges are untested at the unit layer.** Covered: NEW_ONLY excludes a visited id (unit), revisit-okay skips the visited lookup (unit, behavioral), revisit-okay still shows a visited resort (e2e control arm only). Missing: **all-candidates-visited under NEW_ONLY → zero-survivor sparse** (no test anywhere), **positive inclusion of a visited resort under REVISIT_OKAY at the unit layer**, and explicit empty-visited-list semantics.

Everything else is already protected: sparse explanations (0/1/2 survivors + revisit suggestion), exactly-three, deterministic tie-break chain, scorer math (exact-value unit tests), privacy/ownership, controller wiring, and the two flow-level browser journeys.

## Detailed Findings

### Pre-verified state — confirmed

- **PIT wired**: `build.gradle:114-122` — `info.solidsoft.pitest` 1.19.0, `junit5PluginVersion` 1.2.3, `targetClasses`/`targetTests` `com.nextslope.recommendation.*`, `excludedTestClasses` `RecommendationOwnershipIntegrationTests`, `mutationThreshold = 90`, `threads = 2`, `timestampedReports = false`. ([build.gradle#L114-L122](https://github.com/wbiniecki/NextSlope-10x/blob/74ec2287b45005df73d18fec58edd6675ff194b6/build.gradle#L114-L122))
- **CI blocking**: `.github/workflows/ci.yml:39-40` runs `./gradlew pitest --no-daemon` between `test` and the Playwright steps, no `continue-on-error`. ([ci.yml#L36-L40](https://github.com/wbiniecki/NextSlope-10x/blob/74ec2287b45005df73d18fec58edd6675ff194b6/.github/workflows/ci.yml#L36-L40))
- **Calibration record**: threshold 90 was calibrated empirically per the S-05 plan (`context/archive/2026-06-26-three-resort-recommendation/plan.md:255`); first green run scored **94%** with four accepted equivalent/cosmetic surviving mutants (`RecommendationConfig.scoringConfig()` bean wiring, `RecommendationResult.is*()` accessors) — `context/archive/2026-06-26-three-resort-recommendation/refinement-brief.md:116-123`.
- **Stale docs confirmed**: `context/foundation/test-plan.md:123` (§4 PIT row) and `:228-231` (§6.5 "Deferred to S-05 … not wired today") still describe the gate as unwired. The working tree already carries an uncommitted §3 edit marking Phase 2 as `change opened`; the §4/§6.5 reconciliation should land in this change too.
- **E2E guardrails on main**: `src/e2eTest/java/com/nextslope/e2e/RationaleTruthfulnessE2eTests.java` (PR #32, commit `b3b0cd4`) and `NewOnlyGuardrailE2eTests.java` (PR #31, commit `c54e0a4`); HEAD `74ec228` is the PR #32 merge.

### Engine architecture (what an oracle can and cannot assert)

Pipeline in `RecommendationService.recommend(Long)` (`src/main/java/com/nextslope/recommendation/RecommendationService.java:48-77`):

```
profile load → (NEW_ONLY only) visited lookup → active pool (DB query)
→ region hard filter → visited hard filter → sparse gate (<3 survivors)
→ score → rank → limit(3) → card + rationale
```

- **Axis roles** (the load-bearing fact for Risk #2 test design):

| Axis | Role | Scoring? | Rationale? |
|---|---|---|---|
| Active flag | Hard (repo query, `RecommendationService.java:60`) | No | No |
| Region (`Set<String>` countries) | Hard filter (`:61-62`) | **No** | Yes — priority #1, unbeatable when set |
| Novelty (`NEW_ONLY`/`REVISIT_OKAY`) | Hard filter (`:56-58,63`) | **No** | **No** (sparse suggestion text only, `:97-99`) |
| Difficulty band | Weighted score `alignDiff` (`WeightedDistanceScorer.java:26-30`) | Yes | Yes (≥ 0.6 threshold) |
| Experience level | Weighted score `alignExp` (`WeightedDistanceScorer.java:32-34`) | Yes | Yes (≥ 0.6 threshold) |

- **Scoring formula** (`WeightedDistanceScorer.java:25-37`, defaults hardcoded in `ScoringConfig.defaults()` — `ScoringConfig.java:26-28`, wired via `RecommendationConfig.java:13-16`, *not* from properties):
  - `alignDiff = 1 − L1(preferredMix, resortMix) / 200` where `preferredMix` comes from the `DifficultyBand` enum: MOSTLY_EASY (60,30,10), BALANCED (34,33,33), MOSTLY_HARD (10,30,60).
  - `alignExp = 1 − |hardnessIndex − target|` where `hardnessIndex = (0.5·medium + hard)/100` from the *same* resort `DifficultyMix`, and targets are BEGINNER 0.20 / INTERMEDIATE 0.45 / ADVANCED 0.70.
  - `score = 0.5·alignDiff + 0.5·alignExp`.
  - Both soft axes consume the same resort input (`Resort.getDifficultyMix()`, a deterministic largest-remainder `@Transient` derivation, `Resort.java:132-167`) — an experience-differential test must pick resort mixes where flipping only `ExperienceLevel` reorders results.
- **Rationale generation** (`RationaleBuilder.java:23-56`): consumes the **same** `ScoreBreakdown` the ranking used (passed through `RecommendationService.toCard`, `:80-90`; never recomputed). Priority: region (fixed alignment 1.0, unbeatable) → difficulty (`alignDiff ≥ 0.6` and strictly greater than current best) → experience (same, so difficulty beats experience on ties) → truthful fallback (two variants, region-flavored or generic). Full vocabulary is six template strings; no novelty string, no numbers, no resort name.
- **Truthfulness contract nuances** (must inform the plan's oracle design):
  - When a region filter is set, the rationale **always** cites region for every survivor — difficulty/experience rationale lines are only reachable with **no region filter**. Tests for the soft-axis rationale must use region-unconstrained profiles.
  - Sub-threshold soft alignments still drive ranking but yield the generic fallback — "rationale names the ranking driver" is *not* the contract; the contract is "rationale never states a false fact and only names axes the user set". `ScoreBreakdown` (`ScoreBreakdown.java:11-12` — `alignDiff`, `alignExp`, `score` only) has no region/novelty components, so region-line truthfulness needs a profile+resort oracle, exactly as the e2e test does.
- **Determinism**: full ordering chain score-desc → country → name → id (`RecommendationService.java:41-45`); no random/time/static state anywhere in the package; all components constructor-injected and unit-testable without Spring (the existing `RecommendationServiceTests` already builds the real scorer + rationale builder with mocked repos).
- **Sparse path**: `<3 survivors` short-circuits scoring entirely and returns zero cards with an exact-count explanation; zero-survivor wording differs from 1–2; NEW_ONLY adds "allowing revisits" to the suggestion (`RecommendationService.java:66-68,93-101`).

### Existing test inventory mapped to Risks #1–#3

38 test methods across 7 files. Full per-method mapping with oracle-independence analysis was produced during research; the decision-relevant condensation:

| Suite | Methods | Protects | Oracle quality |
|---|---|---|---|
| `RecommendationServiceTests` (`src/test/java/com/nextslope/recommendation/`, 14 methods) | region filter (`:91-105`), NEW_ONLY exclusion (`:107-121`), revisit skips lookup (`:123-135`), sparse 0/1/2 + wording (`:137-150,235-277`), exactly-three (`:152-165`), score-driven ranking (`:167-180`), tie-break chain (`:182-233`), no-profile (`:80-89`) | #2 partial, #3 partial | Independent, but all one-sided (single profile per test — no A/B differential) |
| `WeightedDistanceScorerTests` (5 methods) | scorer math for both soft axes | #2 partial (scorer-internal) | Independent hand math, but **pins exact score numbers** (`isEqualTo(…, within(EPS))`) — the §6.5 anti-pattern if replicated at service level; acceptable as isolated scorer units |
| `RationaleBuilderTests` (10 methods) | axis selection, 0.6 thresholds incl. boundary-equality, region>difficulty>experience priority, both fallbacks | #1 partial | **Hand-injected `ScoreBreakdown` in every test** — tautological w.r.t. real scoring correspondence; resorts are name/country shells (`:19-21`), slope data never used |
| `RecommendationServiceTests.cardsCarryViewFactsAndATruthfulRationale` (`:279-295`) | card facts + rationale | #1 nominal | Rationale asserted only `isNotBlank()` despite the method name |
| `RecommendationOwnershipIntegrationTests` (2 methods) | principal isolation | #5 (not #1–#3) | Independent |
| `RecommendControllerWebMvcTests` (`src/test/java/com/nextslope/web/`, 5 methods) | gating, CSRF, fragment rendering | #4 / wiring (mocked service) | N/A for #1–#3 |
| `RationaleTruthfulnessE2eTests` (1 method, `:118-164`) | #1 flow-level, **region axis only** — form-input oracle + card-country corroboration | #1 partial | Independent; JavaDoc `:39-43` explicitly defers "the full truthfulness contract (rationale ↔ real scoring reasons)" to "the Phase-2 unit/integration suite" |
| `NewOnlyGuardrailE2eTests` (1 method, `:121-166`) | #3 flow-level happy path **with revisit-okay control arm** | #3 partial | Independent; JavaDoc `:40-44` defers hard-filter isolation to "Phase-2 server-side suites" |

### Gap analysis — what this phase must actually build

**Risk #2 (single-axis differential): all four axes missing.**

| Axis | Existing proof | Missing differential | Expected observable |
|---|---|---|---|
| Region | One-profile filter proof (`RecommendationServiceTests:91-105`) | `{Austria}` vs `{}` (or vs `{Austria, France}`) with identical fixtures | Candidate-set change |
| Novelty | NEW_ONLY-only exclusion (`:107-121`); e2e flip is entangled with visited-toggle state | Same fixture set + same visited stub, NEW_ONLY vs REVISIT_OKAY | Candidate-set change (visited resort present ↔ absent) — also closes the #3 positive-inclusion gap |
| Difficulty band | Fixed-profile ranking test varies *resorts* (`:167-180`) — inverse of the pattern | Same resorts, flip only `DifficultyBand` | Ordering change |
| Experience | One scorer unit (`WeightedDistanceScorerTests:57-65`) | Same resorts, flip only `ExperienceLevel` (mixes chosen so hardness targets 0.20/0.45/0.70 reorder) | Ordering change |

Anti-patterns to honor (§6.5 + Risk Response row #2): assert set/ordering deltas, never exact score numbers at service level; use the real scorer (no mocking of `Scorer`).

**Risk #1 (truthfulness vs. scoring internals):** one integration seam is untested — real `WeightedDistanceScorer` → real `RationaleBuilder` through `RecommendationService` (or direct composition), with the expected axis computed independently from profile band/level + resort mix arithmetic. Cases the architecture dictates: difficulty-wins (alignDiff ≥ 0.6, > alignExp), experience-wins, both-sub-threshold → fallback (and *not* naming any axis the user didn't effectively qualify for), region-set → region line named while soft alignments differ, and never-names-an-unset-axis (e.g. no region filter → no region wording). Upgrade or replace the `isNotBlank()` assertion in `cardsCarryViewFactsAndATruthfulRationale`.

**Risk #3 (edges):** (a) all-active-candidates-visited under NEW_ONLY → zero-survivor sparse with the "allowing revisits" suggestion (no test anywhere today; service supports it, `RecommendationService.java:56-67,93-101`); (b) REVISIT_OKAY positive inclusion at unit layer (currently only the e2e control arm proves it); (c) explicit empty-visited-list NEW_ONLY case (today only implicit via `Set.of()` stubs).

**Docs reconciliation (in-scope for this change):** amend `test-plan.md` §4 PIT row and §6.5 "Deferred to S-05" bullet to reflect the wired gate (plugin versions, threshold 90, achieved 94%, CI step), and land the already-edited §3 Phase-2 status row.

## Code References

- `src/main/java/com/nextslope/recommendation/RecommendationService.java:41-45` — deterministic ranking comparator (score desc, country, name, id)
- `src/main/java/com/nextslope/recommendation/RecommendationService.java:56-68` — visited prefetch (NEW_ONLY only), hard filters, sparse gate
- `src/main/java/com/nextslope/recommendation/RecommendationService.java:80-90` — card projection; same `ScoreBreakdown` passed to rationale
- `src/main/java/com/nextslope/recommendation/RecommendationService.java:93-101` — sparse explanation incl. zero-survivor + "allowing revisits" variants
- `src/main/java/com/nextslope/recommendation/WeightedDistanceScorer.java:25-37` — full scoring formula (L1 difficulty distance; hardness-index experience)
- `src/main/java/com/nextslope/recommendation/RationaleBuilder.java:31-55` — axis priority, 0.6 threshold, unbeatable region line, two fallbacks
- `src/main/java/com/nextslope/recommendation/ScoreBreakdown.java:11-12` — `alignDiff`/`alignExp`/`score` only; no region/novelty components
- `src/main/java/com/nextslope/recommendation/ScoringConfig.java:26-28` — hardcoded defaults (weights 0.5/0.5, targets 0.20/0.45/0.70, threshold 0.6)
- `src/main/java/com/nextslope/resort/Resort.java:132-167` — deterministic `DifficultyMix` derivation (largest remainder)
- `src/test/java/com/nextslope/recommendation/RecommendationServiceTests.java:80-295` — 14 existing service tests (see inventory)
- `src/test/java/com/nextslope/recommendation/RationaleBuilderTests.java:19-21,27-149` — hand-crafted-breakdown pattern (tautology w.r.t. real scoring)
- `src/test/java/com/nextslope/recommendation/WeightedDistanceScorerTests.java:26-76` — exact-value scorer units
- `src/e2eTest/java/com/nextslope/e2e/RationaleTruthfulnessE2eTests.java:39-43,118-164` — flow guardrail + explicit Phase-2 deferral
- `src/e2eTest/java/com/nextslope/e2e/NewOnlyGuardrailE2eTests.java:40-44,121-166` — flow guardrail with control arm + deferral
- `build.gradle:114-122` — wired PIT gate ([permalink](https://github.com/wbiniecki/NextSlope-10x/blob/74ec2287b45005df73d18fec58edd6675ff194b6/build.gradle#L114-L122))
- `.github/workflows/ci.yml:36-40` — blocking pitest CI step ([permalink](https://github.com/wbiniecki/NextSlope-10x/blob/74ec2287b45005df73d18fec58edd6675ff194b6/.github/workflows/ci.yml#L36-L40))
- `context/foundation/test-plan.md:123,207-231` — stale §4/§6.5 PIT language to reconcile

## Architecture Insights

- **Two-stage design is literal**: hard filters (active → region → visited) fully precede scoring; sparse short-circuits before any scoring runs. Filter axes can only change the candidate set; soft axes can only change ordering among survivors. Differential tests must match the observable to the axis's stage.
- **Single resort input for both soft axes**: `alignDiff` and `alignExp` both derive from `Resort.getDifficultyMix()` — no separate experience attribute exists. Experience-differential fixtures must exploit the different hardness targets.
- **Rationale-by-design divergence**: rationale is not "the ranking driver" — region trumps everything when set, and sub-threshold scores fall back to generic text while still driving rank. The truthfulness oracle must encode "no false facts, only user-set axes", not "names the top-scoring axis".
- **Plain-JUnit testability is deliberate**: constructor injection everywhere, `ScoringConfig.defaults()` injectable without Spring, zero random/time state. This matters for PIT — new tests in `com.nextslope.recommendation.*` are automatically inside the mutation target and must stay fast (no Spring context), or be added to `excludedTestClasses` like the ownership integration test.
- **PIT interplay**: new truthfulness/differential unit tests will raise mutant kill pressure on `RationaleBuilder`/`RecommendationService` branches; the 90 threshold (94 achieved) has headroom, but a new Spring-context test class in the package would slow every mutant run — keep integration-style additions either lightweight or excluded.

## Historical Context (from prior changes)

- `context/archive/2026-06-26-three-resort-recommendation/plan.md:255,291-307` — S-05 shipped the baseline suites *and* Phase 4 wired PIT with empirical calibration; commit `3481cc5`.
- `context/archive/2026-06-26-three-resort-recommendation/refinement-brief.md:116-123` — 94% first green run; four accepted equivalent/cosmetic mutants (do not chase; do not lower the gate).
- `context/archive/2026-06-26-three-resort-recommendation/reviews/impl-review.md` — accepted risks: recommendation units run twice (test + pitest), zero-slope resort latent bug (curation-guarded), sparse-branch coverage later landed.
- `context/archive/2026-06-25-test-plan-refresh-2026-06-25/` — PIT policy origin: recommender-scoped only, never repo-wide; threshold qualitative until S-05; independent-oracle requirement.
- `context/archive/2026-07-12-testing-browser-e2e-smoke/plan.md:80-81` — browser tier explicitly excludes re-testing recommender correctness.
- PRs #31/#32 (commits `c54e0a4`, `b3b0cd4`) — no change folders; scope lives in class JavaDoc, both explicitly deferring the deeper halves to this phase; Linear 10X-15/10X-16 (Done).
- Algorithm tunable values are a contract from the S-05 refinement session (`refinement-brief.md`) — this test phase must not re-tune weights/targets/thresholds.

## Related Research

- `context/archive/2026-06-26-three-resort-recommendation/research.md` — S-05 pre-implementation research (noted PIT "deferred to S-05 (wire it here)", since fulfilled).
- `context/foundation/test-plan.md` §2 Risk Response Guidance + §6.5 — the normative patterns this phase implements.

## Open Questions

1. **Where should the scorer↔rationale truthfulness tests live?** Plain-JUnit composition of real `WeightedDistanceScorer` + `RationaleBuilder` (fast, PIT-friendly, in-package) vs. a `RecommendationService`-level test with mocked repos but real scoring/rationale (already the established pattern in `RecommendationServiceTests`). Likely both, but the plan should decide the split explicitly.
2. **Does the `isNotBlank()` truthful-rationale test get upgraded in place or superseded** by a new independently-oracled test (and renamed to stop overclaiming)?
3. **How far to take the test-plan.md reconciliation** — minimal §4/§6.5 factual fix vs. also updating §5 gate rows ("required after Phase 2" is now partially true since the PIT gate is live before the correctness suite is complete).
4. **Mutation-threshold ratchet**: after new tests land, should the calibrated `mutationThreshold` be raised from 90 toward the new achieved score, or left as-is? (S-05 policy was "calibrate empirically"; no ratchet policy recorded.)
