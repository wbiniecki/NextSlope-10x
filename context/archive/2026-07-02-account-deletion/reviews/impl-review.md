<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Account Deletion (S-07) — Full Plan

- **Plan**: context/changes/account-deletion/plan.md
- **Scope**: Full plan (Phases 1–2 of 2)
- **Date**: 2026-07-12
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 0 observations
- **Reviewed range**: `680f27e..HEAD` on `feature/10x-12-account-deletion` (5548248, 42142d1, d33411f, ce8a9fc, 6bfef06)
- **Prior reviews**: reviews/impl-review-phase-1.md, impl-review-phase-1-r2.md, impl-review-phase-2.md — all fully triaged. Settled decisions (not reopened here): accepted concurrent-visited-write race (P1-F1, documented in `AccountService` javadoc), derived-delete semantics corrections (P1-F2 / r2-F1), removed vacuous slice-test session assertion (P2-F1).

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

None. Three prior phase-scoped reviews were fully triaged against the same commits; this full-plan sweep focused on cross-phase interactions, PRD guardrail conformance, and whole-plan completeness, and surfaced no new issues.

## Success criteria evidence (fresh runs, 2026-07-12)

- 1.1 / 2.1 `./gradlew test --rerun` (full suite, Testcontainers Postgres included) → **BUILD SUCCESSFUL in 41s**, exit 0. ✅
- 1.2 `./gradlew test --rerun --tests "com.nextslope.user.AccountService*"` → **BUILD SUCCESSFUL in 13s**. ✅
- 2.2 `./gradlew test --rerun --tests "com.nextslope.PermitListLockTests" --tests "com.nextslope.CsrfEnforcedTests"` → **BUILD SUCCESSFUL in 4s**. ✅
- 2.3 `./gradlew test --rerun --tests "com.nextslope.web.AccountControllerTests" --tests "com.nextslope.AccountDeletionIntegrationTests"` → **BUILD SUCCESSFUL in 7s**. ✅
- 2.4–2.6 Manual (browser walk-through, post-delete access checks, cancel link) → performed by the user in a browser earlier today; the `[x]` marks are backed by observable diff evidence (danger-zone link on `profile/form.html:93-104`, confirm page, `?deleted` banner, cancel anchor) and locked by `AccountDeletionIntegrationTests`. Verified, not rubber-stamped.

## Full-plan verification (no action needed)

### PRD guardrail conformance (whole slice)

- **Deletion is permanent and cascades everywhere** (NFR, `prd.md:144`): `AccountService.deleteAccount` removes `visited_resorts` → `preference_profiles` (as managed entity, cascading `preference_profile_regions`) → `users` in one `@Transactional` method; proven on H2 (`AccountServiceTests`), real Postgres (`AccountServicePostgresTests`, incl. rollback-on-failure test), and end-to-end (`AccountDeletionIntegrationTests` asserts all four tables empty for user A via repository reads + JDBC region count).
- **Truthful confirm copy**: `confirm-delete.html` states deletion is **permanent**, removes the preference profile and visited list **immediately**, **no undo** — exactly what the code does. The danger-zone copy on `/profile` says the same. No overstatement or understatement found.
- **Privacy — no surface can show deleted data**: every profile/visited read in `src/main/java` goes through `CurrentUserService.requireUserId(principal)` + `findByUserId`/`findResortIdsByUserId` (verified by repo-wide search); after deletion the principal no longer resolves (401 / stale-session logout) and the rows are gone. `AdminResortController` (the only admin surface) reads no profile/visited data (verified by search). The integration test additionally proves the deleted user's stale session is bounced to `/login` and `/?deleted` renders signed-out.
- **Neighbor isolation**: two-user isolation asserted in all three test layers (H2 service, Postgres service, integration) — user B's rows, regions, and visited marks intact after A's deletion.

### Desired End State checklist (plan → verified)

- `/profile` shows a "Danger zone" block linking to `GET /account/delete` → ✅ `profile/form.html:93-104`, a distinct `card border-danger` outside the profile `<form>`, plain link (not a form).
- `GET /account/delete` renders a confirm page (permanent, profile + visited removed) with a single CSRF-protected POST form → ✅ `confirm-delete.html` (Thymeleaf auto-injects the CSRF token into `th:action` POST forms); route inside `anyRequest().authenticated()`.
- `POST /account/delete` deletes visited → profile (+regions) → user in one transaction, invalidates the caller's session, redirects to `/?deleted` → ✅ `AccountController.deleteAccount` → `AccountService.deleteAccount` → `SecurityContextLogoutHandler.logout` → `redirect:/?deleted`.
- `index.html` shows a success banner on `?deleted` → ✅ `th:if="${param.deleted}"` alert adjacent to the `?logout` banner, same pattern.
- Data never reappears on any surface → ✅ structurally (principal-scoped reads, rows gone, no admin read surface), plus the integration-test proof above.
- `./gradlew test` green + manual walk-through → ✅ fresh runs above; manual rows performed by the user.

### Cross-phase interactions

- **Web flow ↔ service contract**: the controller calls `deleteAccount(userId)` with the id resolved by `requireUserId(principal)` — no id in either route path (no IDOR surface), matching the Phase 1 contract exactly. Session invalidation happens *after* the service call succeeds, so a failed delete leaves the session intact (transaction rolls back, proven by the Postgres rollback test).
- **Phase 2 tests ↔ Phase 1 tests**: no conflicts. `AccountDeletionIntegrationTests` extends `TwoUserIntegrationTestBase` and declares its own `@AfterEach` clearing profiles/visited before the base class's `userRepository.deleteAll()` (FK-safe, per the plan's Critical Implementation Details; JUnit runs subclass `@AfterEach` first). `AccountServicePostgresTests` runs in its own Testcontainers context with its own FK-safe cleanup; `AccountServiceTests` is a rollback-per-test `@DataJpaTest` slice. No shared-context leakage between them.
- **Blanket `@WebMvcTest` adaptation**: the `@MockitoBean AccountService` additions in `PermitListLockTests`, `CsrfEnforcedTests`, `H2ConsoleProfileTests` (×2 nested), and `RoleGatingPatternTests` are the required response to `AccountController` entering the scanned slice; each follows the existing mock-roster pattern.
- **Phase 1 untouched by Phase 2**: `AccountService`, `VisitedResortRepository.deleteByUserId`, and the Phase 1 tests received no Phase 2 edits.

### "What We're NOT Doing" (whole branch)

All boundaries respected across `680f27e..HEAD`: no Flyway migration (no `db/migration` change in the diff), no undo/soft-delete/export, no `/account` settings page (only the two `/account/delete` routes), no last-admin guard or admin-specific behavior, no admin-deletes-user surface, no typed-confirmation field or JS modal (zero JS on the confirm page), no `SecurityConfig` or `StaleAuthenticatedSessionFilter` change (verified via diff).

### Testing Strategy — fully realized

Every test the plan's Testing Strategy names exists and passes: `AccountServiceTests` (H2 unit: full-data user, bare user, neighbor isolation, regions emptiness via native count), `AccountServicePostgresTests` (Testcontainers Postgres 16: the authoritative FK-order/element-collection proof, plus the rollback test added in phase 1 triage), `AccountDeletionIntegrationTests` (end-to-end incl. session death and two-user isolation), `PermitListLockTests` + `CsrfEnforcedTests` extensions (security regression net), `AccountControllerTests` (controller slice). Manual steps 1–4 of the plan map to the user-verified rows 2.4–2.6.

### Housekeeping

- **Progress section**: all 8 rows `[x]` with SHAs (1.x → 5548248, 2.x → ce8a9fc); epilogue commit 6bfef06 wrote the SHAs back and stamped `change.md` → `implemented`. Consistent with the actual commit history.
- **Working tree**: clean except untracked `context/changes/admin-resort-management/` (unrelated, expected).
- **Linear**: 10X-12 correctly remains **In Progress** — per the linear-sync rule it moves to In Review only when the PR to `main` opens (not an action of this review).
- **Excluded by prior agreement**: repo-wide `org.testcontainers` `PostgreSQLContainer` deprecation (pre-existing pattern shared by all `*PostgresTests`).
