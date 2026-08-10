# NextSlope code review

**Passed** — 3 findings, all below `high`.

## Criterion scores

| Criterion | Score | Justification |
| --- | --- | --- |
| `flyway-forward-only` | — | The diff touches only a JUnit test file under src/test/java; no migration files are added or modified, so this criterion governs nothing here. |
| `ddl-auto-validate` | — | No changes to Hibernate/JPA configuration, entities, or column mappings are present in this diff. |
| `constructor-injection` | — | No production Spring components under src/main/java are touched; the diff is confined to a test class. |
| `access-control-scoping` | — | No controller, route, or repository ownership logic is touched; the diff only modifies a scorer unit test. |
| `e2e-conventions` | — | The changed file lives under src/test/java, not src/e2eTest/java, so the Playwright/e2e conventions do not govern this diff. |
| `test-verifies-behavior` | 2/10 | Three distinct violations of this criterion appear in the same file: an existing exact-value assertion is weakened to a bare isNotNull() check, an existing test is marked @Disabled with no replacement coverage added in the same diff, and a brand-new test computes values but asserts nothing at all, so it cannot fail. Only the new assertThrows-based test is compliant. |

Scores are diagnostic. Only findings at or above the fail-on severity block the change.

## Findings

### `src/test/java/com/nextslope/recommendation/WeightedDistanceScorerTests.java`

- **line 35** · medium · `test-verifies-behavior` — The exact-value assertion `assertThat(breakdown.alignDiff()).isEqualTo(1.0, within(EPS))` was weakened in place to `assertThat(breakdown).isNotNull()`, which can no longer detect a regression in the computed alignment value. Restore the value-based assertion (severity capped at medium per the criterion's rollout rule despite this being a removed protection).
- **line 38** · medium · `test-verifies-behavior` — `@Disabled("Alignment maths is being retuned; re-enable once ScoringConfig settles.")` is added to the existing test `difficultyAlignmentDropsWithNormalizedL1Distance` with no replacement coverage added in this diff, removing existing verification of the L1-distance alignment calculation.
- **line 82** · medium · `test-verifies-behavior` — New test `hardSkewedResortIsScoredForABeginnerProfile` computes `alignment` and `combined` from the scorer but never asserts on either value, so it exercises the unit and ends without any way to fail on a regression.
