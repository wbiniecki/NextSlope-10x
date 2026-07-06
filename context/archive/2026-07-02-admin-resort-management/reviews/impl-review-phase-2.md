<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Admin Resort Management (S-06)

- **Plan**: context/changes/admin-resort-management/plan.md
- **Scope**: Phase 2 of 3 — "Create & edit resort"
- **Date**: 2026-07-03
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 4 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Success Criteria

**Automated (Phase 2 items 2.1–2.4):** `./gradlew test` → BUILD SUCCESSFUL; the three Phase 2 test classes re-run clean with `--rerun-tasks`:
- `ResortFormValidationTests` — blank name/country, null and negative integer fields each produce violations; a fully valid form produces none.
- `ResortServiceTests` — `create` sets `active=true` and `totalSlopes = band sum` (35); `update` preserves unmanaged fields (`price`, `latitude`, `longitude`) while changing managed ones; `loadForm` maps managed fields; duplicate `externalId` throws before save; same `externalId` on update is allowed.
- `AdminResortControllerTests` — `GET /admin/resorts/new` (ADMIN 200 + empty form; USER 403); invalid `POST` re-renders form with field errors and `create` never called; valid `POST` calls `create`, redirects, sets flash; duplicate `externalId` → field error; `GET /admin/resorts/{id}/edit` populated (404 when missing); valid `POST /admin/resorts/{id}` calls `update` + redirects.

**Manual (Phase 2 items 2.5–2.7):** Remain `- [ ]` in the plan's Progress (pending user confirmation) — not flipped by this review.

## Findings

### F1 — saveResort maps any DataIntegrityViolationException to DuplicateExternalIdException when externalId is non-null

- **Severity**: 🔷 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/nextslope/resort/ResortService.java:74-82
- **Detail**: The `save` race-backstop `catch (DataIntegrityViolationException)` rethrows as `DuplicateExternalIdException` whenever `resort.getExternalId() != null`, without inspecting the cause. Today `external_id` is the only unique constraint on `resorts`, so any DIV on save with a non-null external id is almost certainly that collision — but a future constraint (or a not-null violation) on a resort that happens to carry an external id would be mislabeled as a duplicate-external-id field error. This is **plan-sanctioned** ("also treat a `DataIntegrityViolationException` on save as the same collision (race backstop)"), so it is not a deviation — noted for awareness only.
- **Fix**: Optional — narrow the catch to inspect the constraint/message (or leave as-is given `external_id` is the only unique constraint at this scale).
- **Decision**: FIXED — `saveResort` now only relabels a `DataIntegrityViolationException` as `DuplicateExternalIdException` when the cause chain names the `uq_resorts_external_id` constraint (portable across H2/Postgres); unrelated integrity errors rethrow unchanged.

### F2 — DataIntegrityViolationException race-backstop branch is untested

- **Severity**: 🔷 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: src/test/java/com/nextslope/resort/ResortServiceTests.java (N/A — missing case)
- **Detail**: `ResortServiceTests` covers the pre-check uniqueness path (`duplicateExternalIdThrowsBeforeSave`, `sameExternalIdOnUpdateIsAllowed`) but never exercises the `saveResort` `catch (DataIntegrityViolationException)` backstop that the plan's service contract explicitly calls out. The branch is currently unverified.
- **Fix**: Optional — add a `ResortServiceTests` case: stub `resortRepository.save(...)` to throw `DataIntegrityViolationException` for a form with a non-null `externalId` and assert `create`/`update` surface `DuplicateExternalIdException`.
- **Decision**: FIXED — added `dataIntegrityViolationOnSaveIsRelabeledAsDuplicateExternalId` (constraint-named DIV → `DuplicateExternalIdException`) and `unrelatedDataIntegrityViolationOnSaveIsNotRelabeled` (other DIV rethrown as-is), locking in the F1 narrowing.

### F3 — create/update controller handlers duplicate formAction + duplicate-externalId handling

- **Severity**: 🔷 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/java/com/nextslope/web/AdminResortController.java:44-100
- **Detail**: `create` and `update` repeat the same `model.addAttribute("formAction", …)` on every early return and an identical `catch (DuplicateExternalIdException)` → `rejectValue("externalId", …)` + re-render block. The shape faithfully mirrors `ProfileController`'s validate→PRG pattern (which also inlines its catch), so this is consistent with the codebase — just mildly repetitive.
- **Fix**: Optional — extract a small private `rejectDuplicateExternalId(bindingResult, model, formAction)` helper if the toggle work in Phase 3 adds more branches; otherwise leave as-is for parity with `ProfileController`.
- **Decision**: SKIPPED — kept parity with `ProfileController`'s inline validate→PRG style.

### F4 — Difficulty-mix largest-remainder algorithm duplicated in JS and Java

- **Severity**: 🔷 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/resources/templates/admin/resorts/form.html:119-181 (mirrors src/main/java/com/nextslope/resort/Resort.java:127-162)
- **Detail**: The form's inline preview script re-implements `Resort.getDifficultyMix()`'s largest-remainder rounding in JavaScript so the live preview matches the stored mix. The two implementations must stay in lockstep; if the Java rounding ever changes, the JS preview silently drifts. This is **plan-sanctioned** ("replicates the largest-remainder rounding … so the preview matches the stored mix") — the alternative (server round-trip) was ruled out to keep the preview instant. Noted as a maintenance risk, not a deviation.
- **Fix**: Optional — add a code comment cross-link on both sides (the JS already has one) or a small test asserting parity on a few sample inputs if this becomes a recurring edit site.
- **Decision**: SKIPPED — plan-sanctioned; the JS already cross-links to `Resort.getDifficultyMix()`.
