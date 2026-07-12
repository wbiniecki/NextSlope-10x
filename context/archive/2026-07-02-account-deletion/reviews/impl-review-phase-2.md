<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Account Deletion (S-07) — Phase 2

- **Plan**: context/changes/account-deletion/plan.md
- **Scope**: Phase 2 of 2 ("Web Flow & Security Wiring")
- **Date**: 2026-07-12
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 1 observation
- **Reviewed state**: uncommitted working tree on `feature/10x-12-account-deletion` (Phase 2 not yet committed; Phase 1 landed in 5548248 + 42142d1 + d33411f, reviewed separately in reviews/impl-review-phase-1.md and impl-review-phase-1-r2.md)

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Findings

### F1 — Slice-test session-invalidation assertion can pass vacuously

- **Severity**: 👁 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: src/test/java/com/nextslope/web/AccountControllerTests.java:76-82
- **Detail**: The POST test asserts the session is "null OR has no attributes" via `satisfiesAnyOf`. In a `@WebMvcTest` + `@WithMockUser` setup, `MockHttpServletRequest.getSession(false)` can be null whether or not the controller invalidated anything, so the null branch can succeed without exercising the logout at all. The `unauthenticated()` matcher on the same request is the assertion that actually discriminates (it fails if `SecurityContextLogoutHandler.logout` is removed), and the real end-to-end session-death proof lives in `AccountDeletionIntegrationTests` (old session on `/resorts` → redirect `/login`), so behavior is genuinely covered — this is purely about the slice test overstating what it proves to a future reader.
- **Fix**: Either drop the `satisfiesAnyOf` session block (rely on `unauthenticated()` at slice level and the integration test for session death), or replace it with a comment noting the integration test is the authoritative session-invalidation proof.
- **Decision**: FIXED — vacuous satisfiesAnyOf session block removed; slice test relies on unauthenticated() with a comment pointing at AccountDeletionIntegrationTests as the authoritative session-invalidation proof.

## Success criteria evidence (fresh runs, 2026-07-12)

- 2.1 `./gradlew test --rerun` (full suite, Testcontainers Postgres included) → **BUILD SUCCESSFUL in 38s**, exit 0. ✅
- 2.2 `./gradlew test --rerun --tests "com.nextslope.PermitListLockTests" --tests "com.nextslope.CsrfEnforcedTests"` → **BUILD SUCCESSFUL in 4s**, exit 0. ✅
- 2.3 `./gradlew test --rerun --tests "com.nextslope.web.AccountControllerTests" --tests "com.nextslope.AccountDeletionIntegrationTests"` → **BUILD SUCCESSFUL in 8s**, exit 0. ✅
- 2.4–2.6 Manual (browser walk-through, post-delete access checks, cancel link) → **verified by the user** minutes before this review; the `[x]` marks are backed by observable diff evidence (danger-zone link, confirm page, `?deleted` banner, cancel anchor all exist and are locked by `AccountDeletionIntegrationTests`). Not rubber-stamped.

## Verified this round (no action needed)

- **Plan contracts 1–7 all hold**:
  1. `AccountController` — GET resolves principal via `CurrentUserService.requireUserId` and returns `account/confirm-delete`; POST does `requireUserId → accountService.deleteAccount(userId) → SecurityContextLogoutHandler.logout → redirect:/?deleted`. No id in either path (no IDOR surface); no `SecurityConfig` change (routes covered by `anyRequest().authenticated()`).
  2. `confirm-delete.html` — uses `fragments/layout :: head/navbar/scripts`; single `th:action="@{/account/delete}"` POST form (Thymeleaf auto-injects the CSRF token); `btn-danger` submit labeled "Permanently delete my account"; cancel link to `/profile`; zero JS. Copy is truthful per the PRD NFR: states **permanent**, profile + visited list removed **immediately**, **no undo**.
  3. Danger zone on `profile/form.html:93-104` — visually distinct `card border-danger` **below and outside** the profile `<form>`, warning text plus a **link** (not a form) to `/account/delete`.
  4. `index.html` — `th:if="${param.deleted}"` success alert ("Your account has been deleted.") adjacent to the existing `?logout` banner, same pattern.
  5. Security net — `/account/delete` added to `PermitListLockTests.mustStayGatedPathsRedirectAnonymousToLogin` `@ValueSource`; `CsrfEnforcedTests.accountDeletePostWithoutCsrfTokenIsForbidden` asserts token-less POST → 403, following the existing template.
  6. `AccountControllerTests` — `@WebMvcTest(controllers = AccountController.class)` + `@Import({SecurityConfig, AppUserDetailsService})` + `@MockitoBean` collaborators + `existsByEmail → true` stub, exactly per convention; GET renders the view, POST with `csrf()` verifies `deleteAccount(1L)`, `/?deleted` redirect, `unauthenticated()`.
  7. `AccountDeletionIntegrationTests` — extends `TwoUserIntegrationTestBase`; seeds profile+regions+visited for A and B; proves danger-zone reachability, confirm render, delete POST, all-four-tables emptiness for A (regions via JDBC count), B fully intact, A's stale session bounced to `/login`, banner rendered signed-out; subclass `@AfterEach` clears profile/visited rows before the base class `deleteAll()` (FK-safe cleanup ordering per the plan's Critical Implementation Details).
- **Security focus items all clean**: CSRF enforced and regression-locked on the destructive POST; route inside the authenticated gate and permit-list-locked; no IDOR surface; session invalidation uses the in-repo `SecurityContextLogoutHandler` primitive (same pattern as `StaleAuthenticatedSessionFilter:37,44` — the `AuthController` programmatic-save pattern is a *login* concern and correctly not mirrored here); success signaled via query param because a flash attribute would not survive invalidation.
- **Lessons priors satisfied**: "Plan navigation to every new screen" — the confirm page is reachable via the danger-zone link on `/profile`, present in the template and pinned by the integration test's `/profile` content assertions. "Linear sync" — 10X-12 remains **In Progress**, which is correct: phases are done but no PR to `main` is open yet (In Review is the PR gate); no status churn needed.
- **Phase 1 assumptions unbroken**: `AccountService`, `VisitedResortRepository.deleteByUserId`, and all Phase 1 tests are untouched by Phase 2. The `@MockitoBean AccountService` additions to the four blanket `@WebMvcTest` classes (`PermitListLockTests`, `CsrfEnforcedTests`, `H2ConsoleProfileTests` ×2 nested, `RoleGatingPatternTests`) are the required adaptation to the new controller entering the scanned slice — each follows the existing mock-roster pattern exactly.
- **Scope discipline ("What We're NOT Doing")**: no new Flyway migration, no undo/soft-delete/export, no `/account` settings page (only the two `/account/delete` routes), no last-admin guard or admin-specific behavior, no admin-deletes-user surface, no typed-confirmation/JS modal, no `StaleAuthenticatedSessionFilter` change. All respected.
- **Working tree**: matches the expected Phase 2 touched set exactly; only unrelated dirty path is untracked `context/changes/admin-resort-management/` (pre-existing, ignored).
- **Excluded by prior agreement**: repo-wide `org.testcontainers` `PostgreSQLContainer` deprecation (pre-existing pattern, not part of this change).
