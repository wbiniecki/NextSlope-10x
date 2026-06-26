<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Mark Resorts as Visited (S-04)

- **Plan**: context/changes/mark-visited/plan.md
- **Scope**: Phases 1–3 of 3 (all automated steps landed; Phase 3 manual checks pending by design)
- **Date**: 2026-06-26
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 2 warnings, 3 observations

Automated verification re-run live: `./gradlew test` (visited.*, VisitedControllerWebMvcTests,
ResortControllerWebMvcTests, CsrfEnforcedTests, ProfileControllerWebMvcTests,
NextslopeApplicationTests) → BUILD SUCCESSFUL. Drift sweep: 17/18 planned items MATCH, 0 MISSING.
Phase 2 was previously phase-reviewed (`impl-review-phase-2.md`); its F1/F2 are confirmed fixed.

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Findings

### F1 — Toggle is check-then-act with no in-flight guard (double-submit can re-mark)

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/nextslope/visited/VisitedResortService.java:32-48; src/main/resources/templates/resorts/list.html:61-67
- **Detail**: `toggle()` reads state then mutates (exists → delete / else insert) with no row lock, and the HTMX button has no `hx-disabled-elt` / in-flight guard. A rapid double-click on a *visited* resort can interleave: request 1 deletes (returns false), request 2 sees exists=false and re-inserts (returns true) — UI flips back to "Visited". The concurrent *mark* path is safe (UNIQUE constraint + DataIntegrityViolationException caught); the unmark→remark interleave is not idempotent.
- **Fix A ⭐ Recommended**: Add `hx-disabled-elt="this"` to the visitedToggle button.
  - Strength: One-line change at the exact source of the double-submit; disables the button while the POST is in flight, eliminating the interleave for the realistic same-user double-click case. No server change.
  - Tradeoff: Doesn't harden against truly concurrent requests from two tabs; purely client-side.
  - Confidence: HIGH — `hx-disabled-elt` is the canonical htmx idiom; button already swaps outerHTML on itself.
  - Blind spot: None significant for MVP threat model.
- **Fix B**: Make mark/unmark idempotent server-side (rely on delete-count + insert catching the unique violation; drop the pre-existence read).
  - Strength: Robust under genuine concurrency, not just same-user double-click.
  - Tradeoff: More invasive; returned "new state" boolean gets ambiguous under a true race.
  - Confidence: MEDIUM — correct but adds logic for a scenario MVP scale rarely hits.
  - Blind spot: Returned boolean under a true race is inherently ambiguous.
- **Decision**: FIXED via Fix A — added `hx-disabled-elt="this"` to the visitedToggle button.

### F2 — No end-to-end test that unmark works on a deactivated resort (FR-013)

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/test/java/com/nextslope/visited/ (no integration coverage)
- **Detail**: The service unit test proves toggle's delete branch runs before the active check (`VisitedResortServiceTests`), and FR-013 is the explicit activeness-asymmetry guardrail (plan §Critical Implementation Details). But no integration/repository test seeds a mark, deactivates the resort, then POSTs the toggle to confirm the mark is removed end-to-end. Correct by inspection; not pinned against regression.
- **Fix**: Add a case (extend `VisitedResortOwnershipIntegrationTests` or a new slice): save a mark → set resort active=false → POST `/resorts/{id}/visited` with session+csrf → assert the mark row is gone.
- **Decision**: FIXED — added `aMarkOnALaterDeactivatedResortCanStillBeUnmarked` to `VisitedResortOwnershipIntegrationTests` (passes).

### F3 — Deactivated resorts with existing marks can't be unmarked from the browse UI

- **Severity**: OBSERVATION
- **Impact**: 🔎 MEDIUM — touches FR-013 UX boundary
- **Dimension**: Architecture / Scope
- **Location**: src/main/java/com/nextslope/web/ResortController.java:27-32
- **Detail**: The list renders only active resorts (`findByActiveTrue…`), so a mark on a later-deactivated resort is invisible and thus un-unmarkable via product UI — even though the service/API supports it. The plan defers any "my visited" listing and admin-deactivate flow to later slices, so this is a known scope boundary, not a defect in S-04.
- **Fix**: None now — track as a follow-up for the slice that surfaces visited state or admin deactivation.
- **Decision**: SKIPPED — known scope boundary; tracked for a later slice.

### F4 — No FK constraints on visited_resorts(user_id, resort_id)

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency / Data safety
- **Location**: src/main/resources/db/migration/V4__create_visited_resorts.sql:1-7
- **Detail**: `V3__create_preference_profiles.sql` declares `fk_preference_profiles_user_id → users(id)`; V4 omits FKs on its two FK-shaped columns, so orphan rows are possible if data is deleted out-of-band. This is the plan's stated decision (§Key Discoveries: "No FK … is strictly required"). A deliberate, documented deviation.
- **Fix**: None — accept as plan-approved; revisit when S-07 (account-deletion cascade) makes referential integrity load-bearing.
- **Decision**: SKIPPED — plan-approved decision; revisit at S-07.

### F5 — Plan §3 still describes the abandoned OOB visitedRowState fragment

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: context/changes/mark-visited/plan.md:332-335
- **Detail**: Phase 2 §3 still instructs returning both `visitedToggle` AND an OOB `visitedRowState` fragment. Phase 2 §4 / Phase 3 §1 supersede this (button-only + client-side `htmx:afterSwap` highlight, per the phase-2 impl-review F1 fix). The implementation correctly follows the superseding text; the stale §3 wording is a documentation inconsistency only.
- **Fix**: Trim/annotate plan §3 to point at the §4 button-only design.
- **Decision**: FIXED — plan §3 now returns only `visitedToggle` and carries a "Superseded (phase-2 impl-review F1)" note pointing at §4 / Phase 3 §1.
