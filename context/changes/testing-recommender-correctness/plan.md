# Recommender Correctness Suite Implementation Plan

## Overview

Close the three test-plan Phase 2 gaps (`context/foundation/test-plan.md` §2 Risks #1–#3) that
`research.md` identified in the already-shipped S-05 recommendation engine: no single-axis
differential proof for any of the four preference axes (Risk #2), no test tying the emitted
rationale to a *real* (not hand-crafted) `ScoreBreakdown` (Risk #1), and three untested edges in
the visited/new-only hard filter (Risk #3). The change also reconciles stale PIT documentation in
`test-plan.md`, validates the new coverage against the live PIT gate, and records the achieved
mutation score while retaining the existing threshold of 90. No production code changes — this is
a test-only change.

## Current State Analysis

The recommendation engine (`com.nextslope.recommendation.*`) is fully built and stable (S-05,
shipped). 38 existing test methods across 7 files already protect the happy path, the sparse/exact
three-card paths, deterministic tie-breaking, scorer math, and privacy/ownership. Two flow-level
Playwright e2e guardrails (`RationaleTruthfulnessE2eTests`, `NewOnlyGuardrailE2eTests`) prove the
region-axis truthfulness happy path and the new-only-vs-revisit-okay happy path in a real browser,
and both explicitly defer their "deeper half" to this phase in their class JavaDoc. The recommender
PIT mutation gate is already wired and CI-blocking (`build.gradle:114-122`,
`.github/workflows/ci.yml:36-40`; threshold 90, first green run scored 94%) — contrary to stale
language still in `test-plan.md` §4/§6.5.

What's missing is narrower than the risk names suggest (see `research.md` §"Gap analysis" for the
full derivation): every existing test proves its slice of behavior one-sidedly (one profile, assert
one output property) rather than the differential A/B pattern §6.5 mandates, `RationaleBuilderTests`
injects a hand-crafted `ScoreBreakdown` in every one of its 10 methods (tautological with respect to
"corresponds to the resort's real scoring reasons"), and three specific edge combinations in the
visited/new-only filter have no test anywhere (all-visited-under-NEW_ONLY, REVISIT_OKAY positive
inclusion at the unit layer, explicit empty-visited-list semantics).

## Desired End State

Every one of the four preference axes (region, novelty, difficulty band, experience level) has a
test that flips exactly that axis, holds everything else fixed, and asserts the candidate set or
ranking order changes. The rationale-truthfulness contract is proven against a `ScoreBreakdown`
produced by the real `WeightedDistanceScorer` (never hand-crafted), covering difficulty-wins,
experience-wins, both-sub-threshold fallback, and region's fixed unbeatable priority. The three
identified visited/new-only edges are explicitly tested. `test-plan.md` accurately describes the
PIT gate as wired and live. The existing PIT `mutationThreshold` remains 90, with the achieved score
and surviving-mutant review recorded after the new coverage lands.

**Verification**: `./gradlew test` and `./gradlew pitest` both pass locally and in CI; the new test
classes compile and run without a Spring context (plain JUnit 5 + AssertJ, per the existing
recommender-package convention so PIT stays fast).

### Key Discoveries:

- Only difficulty band and experience level are scored; region and novelty are hard filters only —
  a differential test for region/novelty must assert a *candidate-set* change, and for
  difficulty/experience must assert an *ordering* change. An ordering assertion for region or
  novelty is structurally impossible (`research.md` §"Engine architecture").
- `RationaleBuilder.build()` (`src/main/java/com/nextslope/recommendation/RationaleBuilder.java:31-55`)
  consumes the *same* `ScoreBreakdown` object `RecommendationService.toCard`
  (`RecommendationService.java:80-90`) used for ranking — never recomputed. Region, when set and
  matched, is given a fixed `bestAlignment = 1.0` and only a **strictly greater** soft alignment can
  override it (`RationaleBuilder.java:39,44`), so region wins even on an exact 1.0/1.0 tie.
- `Resort.getDifficultyMix()` (`src/main/java/com/nextslope/resort/Resort.java:132-167`) returns the
  slope-count percentages unchanged (no rounding artifact) whenever the three counts already sum to
  100 — the existing test convention of constructing resorts with counts summing to 100 is exact,
  not an approximation.

## What We're NOT Doing

- No production code changes. `RecommendationService`, `WeightedDistanceScorer`, `RationaleBuilder`,
  and `ScoringConfig` are correct per S-05's refinement session; this phase adds tests, not fixes.
- Not re-tuning any scoring weight, hardness target, or the 0.6 rationale threshold — those are a
  locked contract from the S-05 refinement brief.
- Not adding a scorer-level companion test for the difficulty/experience differentials — the
  service-level ordering-change test is the actual gap; scorer-level exact-value tests already exist
  and a companion would be redundant coverage without new signal.
- Not re-testing the two flow-level e2e journeys already on `main` (`RationaleTruthfulnessE2eTests`,
  `NewOnlyGuardrailE2eTests`) — this phase covers only what they explicitly deferred.
- Not touching `test-plan.md` §3's Phase 2 Status field or §8 Freshness Ledger — those are the
  test-rollout orchestrator's responsibility as artifacts appear on disk, not this reconciliation.
- Not changing the existing PIT `mutationThreshold` of 90. Phase 5 measures the achieved score and
  reviews survivors, but keeps the established gate unchanged.

## Implementation Approach

Five phases, each independently testable and committable: the three risk-closing phases (differential
axes, rationale truthfulness, hard-filter edges) land as new or extended plain-JUnit test classes
following the project's existing recommender-test conventions (no Spring context, real collaborators,
mocked repositories only where `RecommendationServiceTests` already mocks them); a docs-only phase
reconciles the stale PIT language; a final phase validates all new tests against the retained
mutation threshold, records the achieved score, and reviews surviving mutants.

## Critical Implementation Details

### Differential fixture design (Phase 1)

Getting a clean single-axis flip requires resorts whose exact scores were computed by hand
(`alignDiff = 1 − L1(preferredMix, resortMix)/200`; `alignExp = 1 − |hardnessIndex − target|` where
`hardnessIndex = (0.5·medium + hard)/100`; `score = 0.5·alignDiff + 0.5·alignExp`) so the ordering
flip is guaranteed, not incidental. The region hard-filter fixture also needs a score constraint
because `RecommendationService` returns sparse below three survivors and truncates larger pools to
three cards. Novelty needs only a unique perfect-match visited resort. The fixtures are:

**Region flip** — fixed profile: `MOSTLY_EASY`, `BEGINNER`, `REVISIT_OKAY`; only
`regionCountries` changes. Use three Austrian fillers (ids 1–3, distinct names, each 10/30/60,
score 0.475) and one French resort (id 4, `Chamonix`, 60/30/10, score 0.975). With
`Set.of("Austria")`, all three Austrian resorts are emitted and id 4 is absent. With `Set.of()`,
id 4 is the unique top scorer and is present. Both runs must be recommendation results, never sparse.

**Difficulty band flip** — fixed profile: `ExperienceLevel.INTERMEDIATE` (target 0.45), no region
filter, `REVISIT_OKAY`. Three resorts, ids 1–3:

| id | name | mix (easy/med/hard) | alignDiff @ MOSTLY_EASY | alignDiff @ MOSTLY_HARD |
|---|---|---|---|---|
| 1 | Easy | 60/30/10 | 1.00 (score 0.90) | 0.50 (score 0.65) |
| 2 | Balanced | 34/33/33 | 0.74 (score 0.85) | 0.73 (score 0.8425) |
| 3 | Hard | 10/30/60 | 0.50 (score 0.60) | 1.00 (score 0.85) |

(alignExp is identical in both runs since it only depends on the resort mix and `INTERMEDIATE`'s
fixed target: Easy 0.80, Balanced 0.955, Hard 0.70.) Top pick is resort 1 under `MOSTLY_EASY` and
resort 3 under `MOSTLY_HARD` — assert `cardIds().get(0)` flips between the two `recommend()` calls
with everything else identical.

**Experience level flip** — fixed profile: `DifficultyBand.BALANCED` (preferred mix 34/33/33), no
region filter, `REVISIT_OKAY`. Three resorts, ids 1–3:

| id | name | mix (easy/med/hard) | hardnessIndex | score @ BEGINNER (target 0.20) | score @ ADVANCED (target 0.70) |
|---|---|---|---|---|---|
| 1 | Soft | 70/20/10 | 0.20 | 0.82 | 0.57 |
| 2 | Hard | 20/20/60 | 0.70 | 0.615 | 0.865 |
| 3 | Filler | 0/0/100 | 1.00 | 0.265 | 0.515 |

Top pick is resort 1 under `BEGINNER` and resort 2 under `ADVANCED`; resort 3 never leads and exists
only to keep the survivor count at 3 (avoiding the sparse short-circuit).

### Rationale truthfulness fixtures (Phase 2)

Compose the real `WeightedDistanceScorer` + real `RationaleBuilder` directly (`ScoringConfig.defaults()`,
no mocks) and derive the expected `ScoreBreakdown` by hand before calling either — the same
independent-oracle discipline `WeightedDistanceScorerTests` already uses, extended one seam further
into the rationale text:

| Case | Profile | Resort mix | Real alignDiff | Real alignExp | Expected rationale contains |
|---|---|---|---|---|---|
| Difficulty wins | MOSTLY_EASY, INTERMEDIATE, no region | 60/30/10 | 1.00 | 0.80 | `"Mostly easy runs"`, not `"Intermediate"` |
| Experience wins | MOSTLY_EASY, ADVANCED, no region | 20/20/60 | 0.50 | 1.00 | `"Advanced"`, not `"Mostly easy runs"` |
| Both sub-threshold | BALANCED, INTERMEDIATE, no region | 0/0/100 | 0.33 | 0.45 | the no-region generic fallback text; neither `"Balanced mix"` nor `"Intermediate"` |
| Region unbeatable on a real tie | MOSTLY_EASY, INTERMEDIATE, region={"Austria"}, resort country Austria | 60/30/10 | 1.00 | 0.80 | the resort's country; not `"Mostly easy runs"` (proves the strict-`>` priority rule holds against a *real*, not hand-crafted, 1.0/1.0 tie) |

Every case above also asserts the rationale never contains region wording when no region filter was
set (cases 1–3), closing the "never names an unset axis" contract.

The service-emission handoff guard reuses the difficulty-wins profile and resort plus two
lower-scoring fillers in `RecommendationServiceTests`. It asserts the expected resort is the emitted
top card and that its rationale contains `"Mostly easy runs"`, with the expected axis derived from
the fixture arithmetic above. This keeps the real scorer and real builder while also protecting the
`RecommendationService.toCard()` handoff that direct composition bypasses.

## Phase 1: Single-axis differential tests (Risk #2)

### Overview

Prove all four preference axes are actually wired into scoring/filtering by flipping exactly one axis
per test and asserting the candidate set or ordering changes.

### Changes Required:

#### 1. New differential test class

**File**: `src/test/java/com/nextslope/recommendation/RecommendationAxisDifferentialTests.java`

**Intent**: One test per axis (region, novelty, difficulty band, experience level), each calling
`RecommendationService.recommend()` twice with identical fixtures except the one axis under test,
and asserting the observable that axis's stage can actually produce (candidate-set membership for
the two hard-filter axes; top-rank identity for the two soft-scored axes).

**Contract**: Follow `RecommendationServiceTests`'s existing helper pattern (`resort(...)`,
`profile(...)`, `givenProfile(...)`, mocked `PreferenceProfileService`/`ResortRepository`/
`VisitedResortService`, real `WeightedDistanceScorer` + `RationaleBuilder` via `ScoringConfig.defaults()`).
Use the exact fixtures from "Critical Implementation Details" above for the difficulty and
experience tests. For region, use the exact four-resort fixture above, assert both runs are
recommendation results, and assert id 4 is absent with `regionCountries = Set.of("Austria")` but
present with `Set.of()`. For novelty: four resorts (one a unique perfect match so it reliably lands
in the top three), stub `visitedResortIds` to return that resort's id, run once with `NEW_ONLY` and
once with `REVISIT_OKAY` (same region/band/experience), assert its id is absent under `NEW_ONLY` and
present under `REVISIT_OKAY`.

### Success Criteria:

#### Automated Verification:

- New test class compiles and all four tests pass: `./gradlew test --tests com.nextslope.recommendation.RecommendationAxisDifferentialTests`
- Full suite still green: `./gradlew test`

#### Manual Verification:

- Read each of the four tests and confirm it genuinely exercises the real `WeightedDistanceScorer`/
  filter logic (no mocking of `Scorer`) and asserts a set/ordering delta rather than a copied exact
  score number, per the §6.5 anti-pattern warning.

---

## Phase 2: Rationale truthfulness vs. real scoring (Risk #1)

### Overview

Prove the rationale-truthfulness contract — "names an axis the user set and corresponds to the
resort's real scoring reasons" — using a `ScoreBreakdown` produced by the real scorer, closing the
tautology gap in the existing hand-crafted-breakdown `RationaleBuilderTests`.

### Changes Required:

#### 1. New truthfulness composition test class

**File**: `src/test/java/com/nextslope/recommendation/ScorerRationaleTruthfulnessTests.java`

**Intent**: Compose the real `WeightedDistanceScorer` and real `RationaleBuilder` directly (no
Spring, no service, no mocks) against the four fixtures in "Critical Implementation Details" above,
proving the emitted text is truthful relative to independently hand-computed alignments — not
relative to what the generator itself produced.

**Contract**: Each test calls `scorer.score(resort.getDifficultyMix(), profile)` to get the real
breakdown, then `rationaleBuilder.build(resort, breakdown, profile)`, then asserts the label/region
containment per the fixture table (difficulty-wins, experience-wins, both-sub-threshold fallback,
region-unbeatable-on-a-real-tie).

#### 2. Split the existing over-claiming service test

**File**: `src/test/java/com/nextslope/recommendation/RecommendationServiceTests.java`

**Intent**: `cardsCarryViewFactsAndATruthfulRationale` currently asserts only `rationale().isNotBlank()`
despite its name claiming truthfulness. Rename it to `cardsCarryTheExpectedViewFacts` and drop the
truthfulness claim from the name/comment — the real truthfulness contract now lives in
`ScorerRationaleTruthfulnessTests`. Keep the `isNotBlank()` line as a minimal smoke check that a
rationale is always produced (a distinct, legitimate concern from *is it truthful*). Add
`cardsUseTheRealScorerBreakdownForRationale` as a separate service-emission handoff guard using the
fixture defined above.

**Contract**: Keep the existing test body, rename the method, and update its comment to describe the
narrower view-facts scope. The new handoff test uses mocked repositories/profile loading but the
real `WeightedDistanceScorer` and `RationaleBuilder`, asserts the independently expected top resort,
and asserts its emitted rationale contains `"Mostly easy runs"`.

### Success Criteria:

#### Automated Verification:

- New test class compiles and all four tests pass: `./gradlew test --tests com.nextslope.recommendation.ScorerRationaleTruthfulnessTests`
- Renamed `cardsCarryTheExpectedViewFacts` test still passes: `./gradlew test --tests com.nextslope.recommendation.RecommendationServiceTests`
- New `cardsUseTheRealScorerBreakdownForRationale` service-handoff guard passes: `./gradlew test --tests com.nextslope.recommendation.RecommendationServiceTests`
- Full suite still green: `./gradlew test`

#### Manual Verification:

- Grep the new test class to confirm no `ScoreBreakdown` is ever hand-constructed — every breakdown
  must come from calling the real `scorer.score(...)`.
- Read the four assertions against the fixture table above and confirm the expected labels match
  `DifficultyBand`/`ExperienceLevel`'s actual `getLabel()` strings.
- Confirm the service-handoff guard derives the expected axis from profile + resort fixture data and
  asserts the rationale on the emitted `ResortCard`, not on a directly invoked builder result.

---

## Phase 3: Visited/new-only hard-filter edges (Risk #3)

### Overview

Close the two remaining edge gaps: all-visited under `NEW_ONLY` and explicit empty-visited-list
semantics. Phase 1's novelty differential already supplies the third identified edge,
`REVISIT_OKAY` positive inclusion at the unit layer.

### Changes Required:

#### 1. Two new edge-case tests

**File**: `src/test/java/com/nextslope/recommendation/RecommendationServiceTests.java`

**Intent**: Extend the existing class with the two fixtures below, following its established
helper pattern.

**Contract**:
- `allVisitedCandidatesUnderNewOnlyYieldsZeroSurvivorSparseWithRevisitSuggestion` — `NEW_ONLY`, no
  region filter, three active resorts, `visitedResortIds` stubbed to return all three ids. Assert
  `result.isSparse()`, empty cards, and the explanation contains both the zero-survivor wording
  ("couldn't find any") and the `NEW_ONLY` suggestion ("allowing revisits") — the specific
  zero-survivors-via-visited-exhaustion combination no existing test covers.
- `emptyVisitedListUnderNewOnlyBehavesLikeNoVisitedResorts` — `NEW_ONLY`, no region filter, three
  resorts, `visitedResortIds` explicitly stubbed to return `Set.of()` (not merely left unstubbed).
  Assert `result.isRecommendations()` and all three resorts are represented, proving explicit empty-
  set semantics rather than relying on incidental default-mock behavior.

### Success Criteria:

#### Automated Verification:

- New tests pass: `./gradlew test --tests com.nextslope.recommendation.RecommendationServiceTests`
- Full suite still green: `./gradlew test`

#### Manual Verification:

- Temporarily comment out the `NEW_ONLY` visited-filter line in `RecommendationService` and confirm
  `allVisitedCandidatesUnderNewOnlyYieldsZeroSurvivorSparseWithRevisitSuggestion` fails — then
  restore the line.

---

## Phase 4: Docs reconciliation

### Overview

Fix the stale "PIT not wired today" language in `test-plan.md` §4/§6.5 and clarify §5's gate row —
docs-only, no code.

### Changes Required:

#### 1. Fix §4 stack table PIT row

**File**: `context/foundation/test-plan.md`

**Intent**: Replace the "**Not wired today** — deferred to S-05" closing sentence in the mutation-
testing row with a statement that the gate is wired and live, citing the plugin versions, the 90/94%
threshold, and the CI-blocking step.

**Contract**: Edit the row's trailing sentence only; keep the rest of the row (Java/Gradle
compatibility notes) unchanged since those remain accurate.

#### 2. Fix §6.5 cookbook "Deferred to S-05" bullet

**File**: `context/foundation/test-plan.md`

**Intent**: Replace the bullet claiming the gate, threshold, package names, and CI cadence are all
still to be resolved with a statement that they're resolved and live (`build.gradle:114-122`,
`.github/workflows/ci.yml:36-40`), directing future contributors to extend the existing scaffolding.

**Contract**: Replace the "**Deferred to S-05**" bullet under §6.5; the four preceding bullets in
that section (differential pattern, hard-filter pattern, truthfulness oracle, mutation-gate
how-to) stay as-is — they describe the *pattern*, which this change is implementing, not the wiring
status.

#### 3. Clarify §5 gate row

**File**: `context/foundation/test-plan.md`

**Intent**: The recommender mutation-score gate row's "Required?" column reads "required after §3
Phase 2" as if the gate doesn't exist until this phase completes; add a clarifying note that the
gate is already live and CI-blocking today, and that only the *threshold calibration* (not the gate's
existence) is finalized when this phase's tests land.

**Contract**: Amend the "Required?" cell's wording; leave the "Catches" cell's description of what
the gate protects against unchanged.

### Success Criteria:

#### Automated Verification:

No automated verification applies because no build step depends on this file's prose.

#### Manual Verification:

- Read the amended §4/§5/§6.5 sections end-to-end and confirm no other stale "not wired"/"deferred"
  PIT references remain, and that §3's Phase 2 Status row and §8 Freshness Ledger are left untouched
  (out of scope for this phase per "What We're NOT Doing").

---

## Phase 5: PIT validation at the retained threshold

### Overview

Once Phases 1–3 land, run PIT against the complete recommender suite, record the achieved mutation
score, and review every survivor while retaining `mutationThreshold = 90`.

### Changes Required:

#### 1. Measure and record the mutation result

**File**: `context/changes/testing-recommender-correctness/plan.md`

**Intent**: Run the mutation gate once all new recommender tests exist, read the achieved
mutation-score percentage from the PIT HTML/CSV report, review each surviving mutant, and record the
score plus survivor disposition in this plan's Progress notes.

**Contract**: Leave the existing `pitest {}` block unchanged, including
`mutationThreshold = 90` (`build.gradle:114-122`). Any genuine surviving mutant in the targeted
scorer/filter/rationale behavior must drive a stronger test before the phase completes; equivalent
or cosmetic survivors are documented with their rationale. Record the achieved percentage and
survivor review in Progress notes for traceability, the same way
`refinement-brief.md:116-123` recorded the original calibration.

### Success Criteria:

#### Automated Verification:

- `./gradlew pitest` passes against the retained threshold of 90: `./gradlew pitest`
- Full suite still green: `./gradlew test`

#### Manual Verification:

- Confirm `build.gradle` still sets `mutationThreshold = 90`, record the achieved score, and verify
  every surviving mutant is either documented as equivalent/cosmetic (per the original S-05
  precedent) or addressed by strengthening a test before the phase completes.

---

## Testing Strategy

### Unit Tests:

- All five phases are themselves test-authoring phases; there is no separate "tests for the tests"
  layer beyond running the suites described in each phase's Success Criteria.

### Integration Tests:

- None beyond what already exists (`RecommendationOwnershipIntegrationTests` is unaffected by this
  change and stays excluded from the PIT target per `build.gradle:120`).

### Manual Testing Steps:

1. After Phase 1–3 land, run `./gradlew test` locally and confirm the recommender package's test
   count grew by the expected ~11 methods (4 differential + 4 truthfulness + 1 service handoff + 2
   edges) with zero regressions elsewhere.
2. After Phase 5, open the PIT HTML report (`build/reports/pitest/index.html`), record the achieved
   score, and manually confirm which mutants (if any) survive at the retained threshold,
   cross-checking each against the accepted-equivalent-mutant list already recorded from S-05.

## Performance Considerations

All new/extended test classes stay plain JUnit 5 + AssertJ with no Spring context, per the existing
recommender convention — this keeps them fast under PIT's per-mutant re-run model and avoids
slowing `./gradlew pitest` the way a new `@SpringBootTest` class in the package would.

## Migration Notes

Not applicable — no schema, data, or runtime-config changes in this test-only phase.

## References

- Research: `context/changes/testing-recommender-correctness/research.md`
- PIT gate wiring: `build.gradle:114-122`, `.github/workflows/ci.yml:36-40`
- Original calibration precedent: `context/archive/2026-06-26-three-resort-recommendation/refinement-brief.md:116-123`
- Existing recommender tests: `src/test/java/com/nextslope/recommendation/RecommendationServiceTests.java`,
  `.../RationaleBuilderTests.java`, `.../WeightedDistanceScorerTests.java`
- Deferred e2e halves: `src/e2eTest/java/com/nextslope/e2e/RationaleTruthfulnessE2eTests.java:39-43`,
  `.../NewOnlyGuardrailE2eTests.java:40-44`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Single-axis differential tests (Risk #2)

#### Automated

- [x] 1.1 New test class compiles and all four tests pass — cef8068
- [x] 1.2 Full suite still green (`./gradlew test`) — cef8068

#### Manual

- [x] 1.3 Confirm each test exercises the real scorer/filter and asserts a set/ordering delta, not a copied exact score — cef8068

### Phase 2: Rationale truthfulness vs. real scoring (Risk #1)

#### Automated

- [x] 2.1 New truthfulness test class compiles and all four tests pass
- [x] 2.2 Renamed `cardsCarryTheExpectedViewFacts` test still passes
- [x] 2.3 New `cardsUseTheRealScorerBreakdownForRationale` service-handoff guard passes
- [x] 2.4 Full suite still green (`./gradlew test`)

#### Manual

- [x] 2.5 Confirm no `ScoreBreakdown` is hand-constructed anywhere in the new class
- [x] 2.6 Confirm expected labels match `DifficultyBand`/`ExperienceLevel` real `getLabel()` strings
- [x] 2.7 Confirm the service-handoff guard derives its expected axis independently and asserts the emitted `ResortCard` rationale

### Phase 3: Visited/new-only hard-filter edges (Risk #3)

#### Automated

- [ ] 3.1 New edge-case tests pass
- [ ] 3.2 Full suite still green (`./gradlew test`)

#### Manual

- [ ] 3.3 Deliberate-break check: commenting out the `NEW_ONLY` visited-filter line fails `allVisitedCandidatesUnderNewOnlyYieldsZeroSurvivorSparseWithRevisitSuggestion`, then restore

### Phase 4: Docs reconciliation

#### Manual

- [ ] 4.1 §4/§5/§6.5 read end-to-end, no stale "not wired"/"deferred" PIT language remains, §3 Status and §8 Freshness Ledger untouched

### Phase 5: PIT validation at the retained threshold

#### Automated

- [ ] 5.1 `./gradlew pitest` passes against the retained threshold of 90
- [ ] 5.2 Full suite still green (`./gradlew test`)

#### Manual

- [ ] 5.3 Confirm `mutationThreshold = 90`, record the achieved score, and resolve or document every surviving mutant
