<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Mark Resorts as Visited (S-04)

- **Plan**: context/changes/mark-visited/plan.md
- **Scope**: Phase 2 of 3 (Service, toggle endpoint, and privacy guarantee)
- **Date**: 2026-06-26
- **Commit reviewed**: 3791793
- **Verdict**: NEEDS ATTENTION → RESOLVED (both findings fixed in triage 2026-06-26)
- **Findings**: 0 critical, 1 warning, 1 observation (all FIXED)
- **Triage**: F1 FIXED (Fix A), F2 FIXED. Full `./gradlew test` green after edits.

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Plan adherence detail

- **VisitedResortService** — MATCH. `@Transactional boolean toggle(userId, resortId)` deletes-and-returns-false
  when a mark exists; else `findByIdAndActiveTrue` (→ `ResortNotFoundException`), inserts, returns true;
  catches `DataIntegrityViolationException` as already-visited. `@Transactional(readOnly=true) Set<Long>
  visitedResortIds`. No `isVisited` (correctly omitted). Mirrors `PreferenceProfileService`.
- **CurrentUserService (new) + ProfileController refactor** — MATCH. `requireUserId(UserDetails)` is the
  verbatim `findByEmail(normalize(...)).getId()` lookup, 401 when absent. `ProfileController` injects it and
  drops its private `currentUserId`; `ProfileControllerWebMvcTests` swapped its stub to `CurrentUserService`.
- **VisitedController** — MATCH. `@PostMapping("/resorts/{id}/visited")`, resolves via
  `currentUserService.requireUserId`, calls `toggle`, maps `ResortNotFoundException` → 404, puts
  `resortId`/`visited` on the model, returns `resorts/visited-toggle-response`.
- **Fragments** — `visitedToggle(resortId, visited)` + `visitedRowState(resortId, visited)` defined in
  `list.html` (wrapped in a never-true block); `visited-toggle-response.html` emits both. See F1.
- **VisitedControllerWebMvcTests** — MATCH. anon→/login, mark returns fragment + calls service, no-csrf→403,
  unknown resort→404.
- **VisitedResortOwnershipIntegrationTests** — MATCH (isolation shape). `assertWrongOwnerDenied` seam left as
  a delegating placeholder, per the plan's explicit note (no addressable cross-user route to assert against).
- **Extra (not scope creep)**: `ResortNotFoundException` (the planned "not-found signal"),
  `VisitedResortServiceTests` (TDD service coverage — in spirit with the plan).

## Success criteria

- Automated: `VisitedControllerWebMvcTests`, `VisitedResortServiceTests`,
  `VisitedResortOwnershipIntegrationTests`, `CsrfEnforcedTests`, and full `./gradlew test`
  (42s, incl. Testcontainers Postgres) — all green. No lint errors.
- Manual (2.5): POST returns the toggle fragment; second toggle flips back — confirmed by the developer.

## Findings

### F1 — OOB row-state fragment is an empty <tr>; will wipe the row in Phase 3

- **Severity**: ⚠️ WARNING
- **Impact**: 🔬 HIGH — architectural stakes; think carefully before deciding
- **Dimension**: Safety & Quality (reliability/correctness)
- **Location**: src/main/resources/templates/resorts/list.html:65-68
- **Detail**: `visitedRowState` renders an EMPTY `<tr id="resort-row-{id}" hx-swap-oob="true">`.
  `hx-swap-oob="true"` defaults to an outerHTML swap, so on every toggle HTMX replaces the entire matched
  row — including all `<td>` cells and the just-swapped button — with this empty `<tr class="table-active">`,
  collapsing the row to a blank highlighted strip. Latent today (Phase 2 only defines the fragment; no
  populated `<tr id="resort-row-...">` exists yet), but it is the endpoint's return contract and Phase 3 §2
  wires that exact id onto the real rows, so Phase 3 manual checks 3.3/3.4 will break (button vanishes on
  first toggle). Inherited plan-design flaw ("the row's `<tr>` opening (or an empty placeholder element)"),
  faithfully implemented.
- **Fix A ⭐ Recommended**: Drop the row-replacement OOB; toggle the highlight class without destroying
  content — keep the button-only outerHTML swap and drive the row highlight via an `hx-on::after-request`/
  small JS on the closest `<tr>`, OR move the cue onto a stable inner element the OOB can safely replace.
  - Strength: Preserves all row cells; keeps the surgical button-only swap the plan wanted.
  - Tradeoff: Slightly more wiring than one OOB `<tr>`; exact mechanism is a Phase-3 decision.
  - Confidence: HIGH — htmx `hx-swap-oob="true"` is documented to swap the id-matched element's outerHTML.
  - Blind spot: Chosen highlight mechanism not yet prototyped in the live browser.
- **Fix B**: Make the OOB fragment render the FULL row (all cells + button) so replacing it is lossless.
  - Strength: Keeps the single-OOB-element model; one swap updates everything.
  - Tradeoff: The toggle response must carry the resort's full data; the lightweight
    `visited-toggle-response` contract widens.
  - Confidence: MED — works but couples the toggle endpoint to full-row rendering.
  - Blind spot: The controller only puts `resortId`/`visited` on the model; it has no `Resort` to render.
- **Decision**: FIXED via Fix A — removed the empty-`<tr>` OOB fragment from `list.html`; `visited-toggle-response.html` now returns only the button (carrying `data-visited`); plan Phase 2 §4 / Phase 3 §1–§2 rewritten to the button-driven `htmx:afterSwap` class-toggle. Phase 2 tests re-run green.

### F2 — Misleading comment in the isolation test

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/test/java/com/nextslope/visited/VisitedResortOwnershipIntegrationTests.java:82
- **Detail**: The comment reads "B unmarking the same resort id is a no-op against B's (empty) list", but
  `toggle()` on an unvisited resort MARKS it — and line 90 asserts B now contains `resortXId`. So B's POST
  creates B's own independent mark, not an unmark no-op. The test still correctly proves isolation; only the
  comment misdescribes the behavior.
- **Fix**: Reword to "B toggling the same resort id creates B's own independent mark; A's mark is untouched."
- **Decision**: FIXED — comment reworded at VisitedResortOwnershipIntegrationTests.java:82.
