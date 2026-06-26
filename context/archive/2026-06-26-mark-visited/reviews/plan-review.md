<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Mark Resorts as Visited (S-04)

- **Plan**: context/changes/mark-visited/plan.md
- **Mode**: Deep
- **Date**: 2026-06-26
- **Verdict**: REVISE → SOUND (all findings fixed during triage 2026-06-26)
- **Findings**: 1 critical, 2 warnings, 2 observations — all FIXED

## Verdicts

| Dimension | Verdict (initial) | After fixes |
|-----------|-----------|-------------|
| End-State Alignment | FAIL | PASS |
| Lean Execution | WARNING | PASS |
| Architectural Fitness | PASS | PASS |
| Blind Spots | PASS | PASS |
| Plan Completeness | WARNING | PASS |

## Grounding

6/6 paths ✓ (migration dir, profile, web, templates, support), symbols ✓ (`findByIdAndActiveTrue`, `currentUserId`, `ResortController.list`, `/css` permit-list, htmx loaded-but-unused, `V4` is next free version), brief↔plan ✓.

## Findings

### F1 — In-place row highlight won't update on toggle

- **Severity**: ❌ CRITICAL
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: End-State Alignment
- **Location**: Phase 2 §3 (fragment) + Phase 3 §2 (table render)
- **Detail**: The toggle fragment is just the control button, swapped with `hx-swap="outerHTML"` targeting itself (Phase 2 §3). The row highlight is a class on the `<tr>` (`table-active`), set only at full-page server render in the `th:each` loop (Phase 3 §2). A fragment swap of the button never touches the `<tr>`, so after a toggle the highlight stays stale until a full reload. This contradicts the Desired End State ("marking a resort highlights its row ... without a reload", lines 94–95, 107) and Manual Verification 3.3 ("flips to 'Visited ✓' and highlights the row without a full page reload") — that success criterion cannot pass as written.
- **Fix A ⭐ Recommended**: Out-of-band swap for the row class — give each `<tr>` a stable id (e.g. `id="resort-row-{id}"`); the POST response returns the toggle fragment PLUS a tiny `hx-swap-oob` snippet that sets/clears the row's state class.
  - Strength: Keeps the minimal control-only swap AND updates the row highlight in place; no need to re-render full row data.
  - Tradeoff: Two elements in the response; the OOB snippet must carry the matching row id.
  - Confidence: HIGH — standard HTMX OOB pattern; controller already has the resort id + new visited boolean.
  - Blind spot: None significant.
- **Fix B**: Swap the whole row — fragment becomes the full `<tr>` (control + highlight class); control uses `hx-target="closest tr" hx-swap="outerHTML"`.
  - Strength: Single coherent fragment; no OOB.
  - Tradeoff: The POST handler must load the full Resort and re-render every column — more template duplication and a heavier endpoint than "return one button".
  - Confidence: MED — works, but pushes against the plan's "smallest in-place update" decision.
  - Blind spot: Re-rendering all columns in the toggle path is untested.
- **Decision**: FIXED via Fix A (OOB `visitedRowState` fragment + stable `resort-row-{id}` ids)

### F2 — Fragment param vs model attribute name mismatch

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 2 §2 (controller) + §3 (fragment)
- **Detail**: The fragment signature is `visitedToggle(resortId, visited)` (Phase 2 §3), but the controller returns `"resorts/list :: visitedToggle"` while putting "the resort id and the new visited boolean on the model" (Phase 2 §2). When a parameterized fragment is returned by name without explicit args, Thymeleaf binds the signature parameters from context variables of the same name — so the model attribute must be named exactly `resortId` (not `id`). "Put the resort id on the model" risks an attribute named `id`, leaving `resortId` unbound at render.
- **Fix**: Specify the model attribute is named `resortId` (matching the fragment param) and `visited` — or return the fragment with explicit args. Make the initial-render call in Phase 3 §2 use the same names.
- **Decision**: FIXED (Phase 2 §2 now pins attribute names `resortId`/`visited` or explicit fragment args)

### F3 — Unused isVisited() service method

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Lean Execution
- **Location**: Phase 2 §1 (VisitedResortService contract)
- **Detail**: The service contract specifies `isVisited(userId, resortId)`, but no phase consumes it: `toggle()` returns the new state directly, and the list render uses `visitedResortIds(userId)`. The plan's own scope says "add only the queries needed now". `isVisited` has no caller in any phase.
- **Fix**: Drop `isVisited` from this slice (YAGNI), or state explicitly it is an S-05 read and belongs to the deferred read-model — don't build it speculatively here.
- **Decision**: FIXED (dropped `isVisited` from Phase 2 §1)

### F4 — currentUserId duplicated across three controllers

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW
- **Dimension**: Lean Execution
- **Location**: Phase 2 §2 (VisitedController) + Phase 3 §3 (ResortController)
- **Detail**: The `findByEmail`→`getId` helper exists in `ProfileController`; this slice adds it to `VisitedController` AND `ResortController.list`. That's the third copy (rule of three). The plan accepts duplication, which is defensible, but this is the natural moment to extract a small shared `CurrentUser` helper if preferred.
- **Fix**: Optional — extract once now, or keep duplicating per the plan's stated acceptance. No change required.
- **Decision**: FIXED (added Phase 2 §2 `CurrentUserService`; `ProfileController`/`VisitedController`/`ResortController` all delegate to it)

### F5 — assertWrongOwnerDenied seam stays an unfilled placeholder

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW
- **Dimension**: Plan Completeness
- **Location**: Phase 2 §5 (isolation test)
- **Detail**: `OwnershipPatternIntegrationTests` and `AccessControlAssertions.assertWrongOwnerDenied` both explicitly name S-04 as the slice that specializes the wrong-owner seam. This plan correctly chooses an isolation test (principal-scoped route, no cross-user URL to forge) instead — but that leaves the named seam still a delegating placeholder after S-04, which a future reader may mistake for an oversight.
- **Fix**: Add one line to Phase 2 §5 noting the seam stays a placeholder by design (S-04 has no addressable cross-user route, so isolation is the correct assertion, not a forbidden response).
- **Decision**: FIXED (added by-design note to Phase 2 §6 isolation test)
