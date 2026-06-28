# Recommendation Scoring — Refinement Brief

> Hand-off from S-05 (`three-resort-recommendation`) to a separate algorithm-refinement session.
> S-05 shipped a **working** recommender behind a pluggable scorer with **defensible-default** tunables;
> the exact values were deliberately deferred. This brief is the contract for locking them.
>
> **What is NOT open**: the two-stage shape (hard filters → weighted soft score → deterministic
> ordering → explicit sparse/no-profile branch) and the truthfulness gate are settled by the PRD and
> S-05 and are pinned by tests + a PIT mutation gate. Refinement is a **values-and-tests edit**, not a
> redesign. If you change behavior, the guarding tests below must change with intent — never weaken an
> assertion just to make a retuned value pass.

## Where everything lives

| Concern | File |
|---|---|
| All tunables (one place) | `src/main/java/com/nextslope/recommendation/ScoringConfig.java` |
| Default values bound as a bean | `src/main/java/com/nextslope/recommendation/RecommendationConfig.java` (`ScoringConfig.defaults()`) |
| Scorer (Approach A) | `src/main/java/com/nextslope/recommendation/WeightedDistanceScorer.java` |
| Rationale (truthfulness gate) | `src/main/java/com/nextslope/recommendation/RationaleBuilder.java` |
| Orchestration (filters → score → order → sparse) | `src/main/java/com/nextslope/recommendation/RecommendationService.java` |

Swap or retune by editing `ScoringConfig.defaults()` (and, if the model changes, `Scorer` /
`WeightedDistanceScorer`). The `Scorer` SPI means an entirely different scoring model can be dropped in
without touching `RecommendationService`'s call site. Because the config is centralized, **no scoring
magic number should ever live outside `ScoringConfig`** — keep it that way.

## The open knobs (each: meaning · shipped default · rationale · guarding test)

All six knobs are fields of the `ScoringConfig` record.

### 1. `weightDiff` — difficulty-mix alignment weight in the combined score
- **Default**: `0.5`
- **Rationale**: Equal weight with experience is the neutral starting point; no data yet says one axis
  should dominate. `score = weightDiff·align_diff + weightExp·align_exp`.
- **Open**: whether difficulty should outweigh experience (or vice versa), and whether the two weights
  should still sum to 1.0 once retuned. (research Open Question 1.)
- **Guarding test**: `WeightedDistanceScorerTests.combinedScoreIsTheWeightedBlendOfBothAxes`.

### 2. `weightExp` — experience alignment weight in the combined score
- **Default**: `0.5`
- **Rationale**: See `weightDiff` — symmetric default.
- **Guarding test**: `WeightedDistanceScorerTests.combinedScoreIsTheWeightedBlendOfBothAxes`.

### 3–5. `beginnerHardnessTarget` / `intermediateHardnessTarget` / `advancedHardnessTarget` — the experience↔mix mapping
- **Defaults**: `0.20` / `0.45` / `0.70`
- **Meaning**: A resort's "hardness index" is `H = (0·easy + 0.5·medium + 1·hard)/100`; experience
  alignment is `align_exp = 1 − |H − target|`. The three targets are how the ordered `ExperienceLevel`
  enum is projected onto a resort's `(easy, medium, hard)` triple — there is **no experience attribute
  on `Resort`**, so this mapping is invented here.
- **Rationale**: Beginners prefer low-hardness terrain, advanced skiers high; `0.20 / 0.45 / 0.70` is a
  smooth, monotonic spread. The values are a **choice to confirm**, not derived from data.
- **Open**: this is research **Open Question 2** — the cleanest proposal (a hardness-index scalar) is
  shipped, but the exact target numbers (and whether a flat scalar is even the right model vs.
  per-band targets) are unconfirmed.
- **Guarding tests**: `WeightedDistanceScorerTests.experienceAlignmentComparesHardnessIndexToPerLevelTarget`
  (the `ADVANCED → 0.70` arithmetic) and `ScoringConfig` is exercised per-level through the scorer.
  If you change a target, this test's expected number changes with it.

### 6. `rationaleAlignmentThreshold` — minimum per-axis alignment for a truthful rationale clause
- **Default**: `0.6`
- **Meaning**: A rationale may name the difficulty or experience axis **only** when (a) the user set it
  and (b) its alignment is `>= threshold`. The genuinely strongest qualifying axis wins (fixed priority
  region → difficulty → experience breaks ties). Below threshold on every set axis → a truthful generic
  fallback. This is the structural guard against an untruthful "matches your mostly-hard preference"
  line given the catalog's easy/medium skew.
- **Rationale**: `0.6` keeps a clause only when the match is genuinely good; it is the most
  behavior-sensitive knob and the one most likely to be retuned against the expanded dataset.
- **Open**: the exact cutoff — too low re-admits over-claiming, too high makes the generic fallback the
  common case. Tune against the real expanded distribution. (Related to research Open Question 1's
  data-aware concern.)
- **Guarding tests** (the truthfulness oracle — derive expectations from user input, never from the
  generator): `RationaleBuilderTests.neverNamesDifficultyWhenItsAlignmentIsBelowThreshold`,
  `RationaleBuilderTests.namesDifficultyWhenItsAlignmentExactlyMeetsTheThreshold`,
  `RationaleBuilderTests.namesExperienceWhenItsAlignmentExactlyMeetsTheThreshold`,
  `RationaleBuilderTests.picksTheStrongestQualifyingAxisAmongExperienceAndDifficulty`,
  `RationaleBuilderTests.fallsBackTruthfullyWhenNoAxisQualifies`.

## Deferred decisions carried over from research (Open Questions 1–2)

1. **Soft-axis weights / data-aware handling** (research Open Question 1). The sharp form: how to weight
   a difficulty axis that, for two of three bands, *every* candidate fails roughly equally — without
   emitting an untruthful rationale. S-05 ships Approach A (`weightDiff`/`weightExp` + the threshold
   gate on the rationale) and **defers** the richer Approach C (data-aware gating that drops an axis no
   surviving candidate can satisfy). The dataset expansion (Phase 1) was specifically done so this is
   less acute, but the weighting/gating call is still open.
2. **Experience → difficulty-mix mapping** (research Open Question 2). The hardness-index scalar with
   targets `0.20 / 0.45 / 0.70` is shipped as the proposal; confirming or replacing it is open (see
   knobs 3–5).

## Behavior locked by tests (do not regress)

Beyond the per-knob tests above, these pin the **shape** the refinement must preserve. They are scoped
by the PIT mutation gate (`com.nextslope.recommendation.*`, `mutationThreshold = 90` in `build.gradle`;
CI runs `./gradlew pitest`). Retuning values is fine; breaking these guarantees is not.

- **Hard filters** — `RecommendationServiceTests`: `regionFilterKeepsOnlyResortsInTheSelectedCountries`,
  `newOnlyNoveltyExcludesVisitedResorts`, `doesNotConsultTheVisitedListWhenRevisitsAreAllowed`.
- **Sparse branch (no padding, truthful count/suggestion)** — `RecommendationServiceTests`:
  `returnsAnExplicitSparseExplanationWhenFewerThanThreeSurvive`,
  `returnsExactlyThreeCardsWhenMoreThanThreeSurvive`, `sparseExplanationStatesTheExactSurvivorCount`,
  `sparseExplanationForZeroSurvivorsSaysNoneMatched`,
  `sparseExplanationSuggestsAllowingRevisitsForNewOnlyUsers`.
- **No-profile state** — `RecommendationServiceTests.returnsNoProfileStateWhenTheUserHasNotSetUpAProfile`.
- **Determinism (total order `-score, country, name, id`)** — `RecommendationServiceTests`:
  `rankingIsDrivenByScoreNotInputOrAlphabeticalOrder`,
  `tiedScoresBreakDeterministicallyByCountryThenNameThenIdAcrossInputOrders`,
  `nameTieBreakOrdersResortsWithinTheSameCountryAndScore`,
  `idTieBreakIsTheFinalTotalOrderForResortsIdenticalInCountryNameAndScore`.
  **Never** introduce `HashSet`/`HashMap` iteration or `parallelStream()` into ranking.
- **Scorer math** — `WeightedDistanceScorerTests`: `perfectDifficultyMatchYieldsFullDifficultyAlignment`,
  `difficultyAlignmentDropsWithNormalizedL1Distance`, `difficultyAlignmentSumsAllThreeMixComponents`.
- **Privacy (principal isolation)** — `RecommendationOwnershipIntegrationTests` (excluded from PIT as a
  `@SpringBootTest`; still part of `./gradlew test`).

## Accepted surviving mutants (mutation gate, as of S-05 close)

Achieved mutation score **94%** (gate **90%**). The four surviving mutants are equivalent/cosmetic and
intentionally not chased — they carry no truthfulness/determinism signal:
- `RecommendationConfig.scoringConfig()` — the Spring `@Bean` factory returning `ScoringConfig.defaults()`
  (wiring, exercised by the integration test which is excluded from PIT).
- `RecommendationResult.isRecommendations()` / `isSparse()` / `isNoProfile()` — trivial DTO discriminator
  accessors.

If refinement adds real branching to any of these, add a killing test rather than lowering the gate.
