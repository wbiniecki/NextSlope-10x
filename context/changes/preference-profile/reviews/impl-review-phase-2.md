<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Preference Profile (S-02) — Phase 2

- **Plan**: context/changes/preference-profile/plan.md
- **Scope**: Phase 2 of 3 (Service & Form)
- **Date**: 2026-06-26
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

## Plan adherence summary

| Planned item | File | Verdict |
|---|---|---|
| Form DTO: 3 `@NotNull` axes, `anyRegion`, `regionCountries`, `defaults()` | `PreferenceProfileForm.java` | MATCH |
| Service: `loadFormForUser` (saved-or-defaults, `anyRegion` from empty set) | `PreferenceProfileService.java` | MATCH |
| Service: `save` upsert + region normalization + out-of-vocab rejection | `PreferenceProfileService.java` | MATCH |
| Service: `availableCountries` distinct+sorted | `PreferenceProfileService.java` | MATCH |
| Domain exception mapped to field error | `UnknownRegionCountryException.java` | MATCH |
| Form validation test (standalone `Validator`) | `PreferenceProfileFormValidationTests.java` | MATCH |
| Service unit tests (Mockito) | `PreferenceProfileServiceTests.java` | MATCH |

## Success criteria (verified this session)

- `./gradlew test --tests "com.nextslope.profile.PreferenceProfileFormValidationTests" --tests "com.nextslope.profile.PreferenceProfileServiceTests"` → PASS (10 tests)
- `./gradlew test` (full suite) → PASS

## Findings

### O1 — @Transactional added (not used by sibling service)

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: PreferenceProfileService.java:28,36,53
- **Detail**: Service annotates reads `@Transactional(readOnly=true)` and `save` `@Transactional`. Not specified in the plan and the sibling `UserRegistrationService` doesn't annotate. Correct, defensible Spring practice (save is read-then-write; `@ElementCollection` load benefits from a tx boundary). Flagged only as an intentional deviation from the existing convention.
- **Fix**: None needed — keep. (Optional: standardize tx policy across services later.)
- **Decision**: SKIPPED — kept as-is (correct Spring practice)

### O2 — "empty list + anyRegion=false → empty set" branch untested

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; obvious and narrowly scoped
- **Dimension**: Testing
- **Location**: PreferenceProfileServiceTests.java
- **Detail**: `normalizeRegions` treats both `anyRegion=true` and an empty list as "any region". The `anyRegion=true` path is tested directly; the `anyRegion=false` + empty list path is only covered indirectly via `defaults()`. Plan listed "empty list → empty" explicitly.
- **Fix**: Optional one-line test (`anyRegion=false`, empty `regionCountries` → empty stored set). Low value; behavior is exercised.
- **Decision**: FIXED — added `saveNormalizesEmptyRegionListToEmptySetEvenWhenAnyRegionFalse`

### O3 — Validation test asserts paths, not custom messages

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; obvious and narrowly scoped
- **Dimension**: Testing
- **Location**: PreferenceProfileFormValidationTests.java:36
- **Detail**: The plan's test contract mentioned "verify validation messages"; the test asserts the invalid property paths instead. Equivalent coverage of "axes are required", but doesn't pin the user-facing message strings (surfaced by Phase 3's template).
- **Fix**: None needed — paths prove the constraints fire; message strings get exercised by the Phase 3 `@WebMvcTest` error path.
- **Decision**: SKIPPED — paths suffice; messages covered by Phase 3
