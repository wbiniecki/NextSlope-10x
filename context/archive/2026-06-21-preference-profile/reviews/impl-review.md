<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Preference Profile (S-02)

- **Plan**: context/changes/preference-profile/plan.md
- **Scope**: Phases 1–3 of 3 (full plan)
- **Date**: 2026-06-26
- **Verdict**: APPROVED
- **Findings**: 0 critical, 1 warning, 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Plan drift summary

All planned items across the three phases verified MATCH — enums + canonical mix triples,
`PreferenceProfile` entity / `@ElementCollection`, `V3` migration (both tables, FK, unique
constraints), service upsert / region normalization / out-of-vocab rejection, principal-scoped
controller (PRG), the Thymeleaf form (SpEL enum lists), navbar Profile link, and the post-signup
redirect. No MISSING items. The only "extra" files are justified: `UnknownRegionCountryException`
(plan-implied), `resorts/list.html` (carries the new `profileSaved` flash from the redirect change),
and the touched broad `@WebMvcTest` / security tests (register a `PreferenceProfileService` mock now
that a new controller exists). No scope creep.

## Success criteria (verified this session)

- `./gradlew test` (full suite) → BUILD SUCCESSFUL (green).
- All Phase 1–3 Progress checkboxes (automated + manual) marked complete with observable diff evidence.

## Findings

### F1 — Upsert insert-race throws an unhandled 500

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; obvious and narrowly scoped
- **Dimension**: Safety & Quality (Reliability)
- **Location**: src/main/java/com/nextslope/web/ProfileController.java:57-64
- **Detail**: `save()` is a find-then-save upsert (`PreferenceProfileService.save`, line 41). Two
  concurrent POSTs from the same user before any row exists (e.g. a double-submit right after the
  post-signup redirect) both find nothing, both INSERT, and the second hits
  `uq_preference_profiles_user_id` → `DataIntegrityViolationException`, bubbling to a 500. The UNIQUE
  constraint prevents data corruption, so this is a UX/reliability nit, not a data-safety bug.
  `AuthController.java:61` already catches exactly this exception for the analogous signup
  insert-race — the profile path diverges from that established pattern.
- **Fix**: In `ProfileController.save`, also catch `DataIntegrityViolationException` and reject it to
  the form (re-render `profile/form` with `availableCountries`), mirroring `AuthController.java:61`.
- **Decision**: FIXED — added a `DataIntegrityViolationException` catch in `ProfileController.save`
  (global `reject` + re-render) and a global-errors alert in `profile/form.html`. Tests green.

### F2 — Plan §6 still says POST redirects to /profile

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: context/changes/preference-profile/plan.md:363
- **Detail**: The Phase 3 §6 test-contract bullet reads "valid POST redirects to /profile". The
  authoritative UX change (plan §1, lines 306-308) and the implementation both redirect to `/resorts`
  with a `profileSaved` flash (`ProfileController.java:66-67`). Code is correct; only the §6 bullet is
  stale and could mislead a future reader.
- **Fix**: Update plan.md:363 wording to "/resorts".
- **Decision**: FIXED — plan.md §6 contract now reads "/resorts" (with a pointer to the §1 UX change).

### F3 — availableCountries() loads full Resort rows for distinct strings

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; obvious and narrowly scoped
- **Dimension**: Safety & Quality (Performance)
- **Location**: src/main/java/com/nextslope/profile/PreferenceProfileService.java:54-60, 66-71
- **Detail**: `availableCountries()` fetches every active `Resort` entity then derives distinct+sorted
  countries in memory; it's invoked up to twice per request (GET, and the error/reject paths).
  `normalizeRegions` also does an O(n·m) `List.contains` per submitted country. Negligible at seed
  scale (14 countries), but a derived distinct-country query and a `Set` membership check would be
  tidier.
- **Fix**: Optional — a distinct-country derived query + `Set.contains` for membership. Not worth
  doing now given the data size.
- **Decision**: SKIPPED — negligible at seed scale (14 countries); revisit if the catalog grows.

### F4 — UI shows raw enum names (MOSTLY_EASY, REVISIT_OKAY)

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; obvious and narrowly scoped
- **Dimension**: Pattern Consistency (UX)
- **Location**: src/main/resources/templates/profile/form.html:23,35,47
- **Detail**: The three selects render `th:text="${level/band/novelty}"`, i.e. the raw enum constant
  ("MOSTLY_EASY", "REVISIT_OKAY"). Functional and not in the plan's scope, but reads as
  developer-facing. Human labels would be friendlier when S-05 makes this onboarding flow user-facing.
- **Fix**: Optional — add a display label to each enum (or a message-bundle lookup) and bind `th:text`
  to it.
- **Decision**: FIXED — added a `getLabel()` to `ExperienceLevel`, `DifficultyBand`, and
  `NoveltyPreference`, and bound the form's option `th:text` to `${...label}` (value stays the enum
  constant). Full suite green.
