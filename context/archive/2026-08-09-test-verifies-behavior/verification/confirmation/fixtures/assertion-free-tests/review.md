# NextSlope code review

**Passed** — 3 findings, all below `high`.

## Criterion scores

| Criterion | Score | Justification |
| --- | --- | --- |
| `flyway-forward-only` | — | No migration files (V{n}__*.sql) are touched by this diff; it is entirely test-source changes to WeightedDistanceScorerTests.java. |
| `ddl-auto-validate` | — | No entity, mapping, or ddl-auto property changes appear in this diff. |
| `constructor-injection` | — | No Spring component (@Controller/@Service/@Component/@Repository/@Configuration) under src/main/java is touched; the diff only modifies a JUnit test class. |
| `access-control-scoping` | — | No request paths, controllers, or repository ownership queries are touched; this diff is confined to a recommendation-scoring unit test. |
| `e2e-conventions` | — | The changed file lives under src/test/java, not src/e2eTest/java, so no Playwright/E2E convention governs it. |
| `test-verifies-behavior` | 3/10 | Three separate regressions in the same test class: an existing exact-value assertion (isEqualTo(1.0, within(EPS))) was weakened to a tautological isNotNull() check; an existing test was given @Disabled with only a promise to re-enable later and no replacement coverage added in this diff; and a brand-new test computes 'alignment' and 'combined' but never asserts on them, so it cannot fail no matter what the scorer returns. The new scoringWithoutAProfileIsRejected test is fine (assertThrows is real verification), but it does not offset the three violations above. |

Scores are diagnostic. Only findings at or above the fail-on severity block the change.

## Findings

### `src/test/java/com/nextslope/recommendation/WeightedDistanceScorerTests.java`

- **line 35** · medium · `test-verifies-behavior` — The precise assertion assertThat(breakdown.alignDiff()).isEqualTo(1.0, within(EPS)) was replaced with assertThat(breakdown).isNotNull(), which can no longer detect a regression in the computed alignment value.
- **line 38** · medium · `test-verifies-behavior` — @Disabled("Alignment maths is being retuned; re-enable once ScoringConfig settles.") is added to the existing difficultyAlignmentDropsWithNormalizedL1Distance test with no replacement coverage supplied in this diff, removing existing verification of the L1-distance alignment calculation.
- **line 82** · medium · `test-verifies-behavior` — hardSkewedResortIsScoredForABeginnerProfile computes 'alignment' and 'combined' from breakdown but never asserts on either value, so the test cannot fail regardless of the scorer's output.
