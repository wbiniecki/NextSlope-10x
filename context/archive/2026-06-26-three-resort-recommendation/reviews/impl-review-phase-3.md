<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Three-Resort Recommendation (S-05)

- **Plan**: context/changes/three-resort-recommendation/plan.md
- **Scope**: Phase 3 of 4 (Web layer + navigation entry point)
- **Date**: 2026-06-28
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Evidence Verified

- Pattern compliance: `RecommendController` mirrors `VisitedController` (constructor injection, `@AuthenticationPrincipal` + `requireUserId`, no id in path, model attr matches fragment param, thin wrapper template). `recommend-results.html` delegates to named fragment `resorts/list :: recommendResults` (single source of truth), mirroring `visited-toggle-response.html`. Fragment uses the proven never-true `th:block` idiom.
- View↔domain bindings resolve: `result.isRecommendations()/isSparse()/isNoProfile()/cards()/explanation()` and all `ResortCard` accessors exist.
- Safety: CSRF enforced (`postWithoutCsrfIsForbidden`), gating enforced (`anonymousPostRedirectsToLogin` → /login), principal isolation proven (`RecommendationOwnershipIntegrationTests`: B never sees A's France pick; profile-less admin gets own no-profile state). All user text via `th:text` (auto-escaped). No secrets, no external-boundary calls in controller.
- Navigation lessons rule satisfied: "Recommend resorts" button on `/resorts` (with `hx-indicator` for 2s NFR); `index.html` "coming soon" copy replaced with live copy.
- Scope discipline: no dedicated `/recommend` full page, no extra endpoints, no creep. `@MockitoBean RecommendationService` added to every `@WebMvcTest` context that now loads the controller (PermitListLock, CsrfEnforced, H2ConsoleProfile, RoleGatingPattern), exactly as planned.
- Success criteria: Phase 3 scoped suite green; full `./gradlew test` green (34s).

## Findings

### F1 — Plan's 3.1 verification command names a non-existent test class

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: context/changes/three-resort-recommendation/plan.md:226, :373
- **Detail**: Plan criterion 3.1 says run `--tests com.nextslope.web.RecommendControllerTests`, but the implemented class is `RecommendControllerWebMvcTests` (correctly matching the existing `VisitedControllerWebMvcTests` convention). The literal command finds no tests (Gradle `--tests` with no match errors), so a future reader verifying by the plan gets a false failure. The actual class exists and passes; only the plan string is stale. Implementation chose the convention-consistent name.
- **Fix**: Update plan 3.1's command (and any prose reference) to `com.nextslope.web.RecommendControllerWebMvcTests`.
- **Decision**: FIXED — plan.md:226 and :373 updated to the real class name.

### F2 — Phase 3 Progress rows checked [x] but carry no commit SHA

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: context/changes/three-resort-recommendation/plan.md:373-376
- **Detail**: Phases 1–2 rows append ` — <sha>`; Phase 3 rows (3.1–3.4) are `[x]` with no SHA because the work is still in the working tree (all Phase 3 files untracked/modified, nothing committed since 9c19cfb). Expected for an uncommitted phase, but the per-phase-commit convention (git-workflow rule) isn't satisfied until you commit and back-fill the SHAs.
- **Fix**: Commit Phase 3 as one phase commit, then append the SHA to rows 3.1–3.4.
- **Decision**: FIXED — Phase 3 committed as 201dba2; rows 3.1–3.4 back-filled with the SHA.
