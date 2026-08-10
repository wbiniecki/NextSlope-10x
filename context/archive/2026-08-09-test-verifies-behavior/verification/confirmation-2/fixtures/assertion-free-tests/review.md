# NextSlope code review

**Passed** — 3 findings, all below `high`.

## Criterion scores

| Criterion | Score | Justification |
| --- | --- | --- |
| `flyway-forward-only` | — | The diff touches only a test file under src/test/java/com/nextslope/recommendation/WeightedDistanceScorerTests.java. No migration files (V{n}__*.sql) are added or modified, so this criterion governs nothing in this diff. |
| `ddl-auto-validate` | — | No changes to ddl-auto configuration, entities, or persistent field mappings appear in this diff; it is limited to unit test logic for a scoring class. |
| `constructor-injection` | — | No production Spring components (@Controller/@Service/@Component/@Repository/@Configuration) are touched; the diff only modifies a plain unit test class. |
| `access-control-scoping` | — | The diff does not touch any request path, controller, or repository query related to owned-data resolution; it is confined to a scoring algorithm's unit tests. |
| `e2e-conventions` | — | The changed file lives under src/test/java/, not src/e2eTest/java/, so the Playwright/E2E conventions do not govern this diff at all. |
| `test-verifies-behavior` | 2/10 | This diff introduces three separate violations of this criterion in the same test class: an existing exact-value assertion is weakened to a bare isNotNull() check, an existing test is disabled with no replacement coverage, and a brand-new test computes values but never asserts on them. Only the fourth new test (scoringWithoutAProfileIsRejected, using assertThrows) actually verifies behavior. The net effect is a meaningful loss of regression-detection ability in this file. |

Scores are diagnostic. Only findings at or above the fail-on severity block the change.

## Findings

### `src/test/java/com/nextslope/recommendation/WeightedDistanceScorerTests.java`

- **line 35** · medium · `test-verifies-behavior` — The original assertion `assertThat(breakdown.alignDiff()).isEqualTo(1.0, within(EPS));` was weakened to `assertThat(breakdown).isNotNull();`. This is a value check reduced to a null check, removing the test's ability to detect a regression in the actual alignment computation while still appearing to pass.
- **line 38** · medium · `test-verifies-behavior` — @Disabled("Alignment maths is being retuned; re-enable once ScoringConfig settles.") is added to the existing test difficultyAlignmentDropsWithNormalizedL1Distance with no replacement coverage added in this diff, silently removing verification of the L1-distance alignment formula.
- **line 82** · medium · `test-verifies-behavior` — New test hardSkewedResortIsScoredForABeginnerProfile computes `alignment` and `combined` from the scorer's output but never asserts on either value (no assertThat/assertEquals/etc.), so it cannot fail even if the scoring logic regresses.
