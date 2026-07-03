<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Admin Resort Management (S-06)

- **Plan**: context/changes/admin-resort-management/plan.md
- **Scope**: Phase 1 of 3 — "Admin gate, resort list & navigation"
- **Date**: 2026-07-03
- **Verdict**: APPROVED
- **Findings**: 0 critical, 2 warnings, 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

## Success Criteria

**Automated (Phase 1 items 1.1–1.4):** `./gradlew test` → BUILD SUCCESSFUL (all tasks up-to-date from the last clean run; no re-compilation needed). New/extended tests present and passing:
- `AdminResortControllerTests` — anonymous→/login, USER→403, ADMIN→200 + view `admin/resorts/list`.
- `PermitListLockTests` — `@WithMockUser(roles="USER") GET /admin/resorts` → 403.
- `ResortRepositoryTests` — `findAllByOrderByCountryAscNameAsc()` returns active + inactive in country/name order.

**Manual (Phase 1 items 1.5–1.7):** User-confirmed successful (admin reaches `/admin/resorts` from landing + navbar and sees inactive resorts; USER sees no admin links and gets 403; `DevAdminBootstrap` absent under prod). These remain `- [ ]` in the plan's Progress by design (not flipped in this review).

## Findings

### F1 — ResortService.listAll() missing @Transactional(readOnly = true)

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/java/com/nextslope/resort/ResortService.java:15-17
- **Detail**: The new read method has no transactional annotation, while peer read services mark read paths read-only (`PreferenceProfileService.java:30`, `VisitedResortService.java:51-52`). Functionally harmless for a single read, but inconsistent with the established service convention this seam is meant to follow.
- **Fix**: Add `@Transactional(readOnly = true)` to `listAll()` (import `org.springframework.transaction.annotation.Transactional`), matching the peer read services.
- **Decision**: FIXED

### F2 — RoleGatingPatternTests Javadoc now stale after URL-level admin gate

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/test/java/com/nextslope/support/RoleGatingPatternTests.java:44-50, 104-106
- **Detail**: Class Javadoc still asserts production `SecurityConfig` is "binary (permit-listed vs. authenticated())" and that importing it alone "would let an authenticated USER through." Phase 1 added `.requestMatchers("/admin/**").hasRole("ADMIN")`, so importing `SecurityConfig` now genuinely produces USER→403 for `/admin/**` without method security. The doc is now misleading about the very seam it documents.
- **Fix**: Update the Javadoc to note that URL-level admin gating is live in `SecurityConfig`; keep the method-security demo framed as the template for slices that opt into `@PreAuthorize` instead.
- **Decision**: FIXED

### F3 — No automated prod-profile absence test for DevAdminBootstrap

- **Severity**: 🔷 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: src/main/java/com/nextslope/config/DevAdminBootstrap.java (N/A — missing test)
- **Detail**: The plan scoped "DevAdminBootstrap absent under prod profile" as **Manual** item 1.7 (user-confirmed), so the implementer followed the plan. However, a strong precedent exists for asserting profile-conditioned wiring automatically (`H2ConsoleProfileTests` has a prod nested class), and the plan's Critical Implementation Details flag "local admin must be provably non-prod" as a key safety property — an automated guard would lock the known-credentials-admin-never-in-prod invariant against future regressions.
- **Fix**: Optionally add `DevAdminBootstrapProfileTests` mirroring `H2ConsoleProfileTests` — assert the bean is absent under `@ActiveProfiles("prod")` and present under `!prod`.
- **Decision**: FIXED

### F4 — Dev admin password logged at INFO

- **Severity**: 🔷 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/nextslope/config/DevAdminBootstrap.java:52-53
- **Detail**: Seeded dev credentials (incl. plaintext password) are logged at INFO on create. This is **plan-sanctioned** ("Log the seeded credentials at startup (dev only)") and gated by `@Profile("!prod")`, so it never runs against Neon/Render. The only residual exposure is a shared/aggregated dev log. Noted for awareness, not a deviation.
- **Fix**: Optional — log the email at INFO and the password at DEBUG (or console-only) if dev logs are ever shared.
- **Decision**: FIXED (log email only; never log passwords)

### F5 — Minor test/template hygiene in new admin files

- **Severity**: 🔷 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/test/java/com/nextslope/web/AdminResortControllerTests.java:38-63; src/main/resources/templates/admin/resorts/list.html:3
- **Detail**: (a) `AdminResortControllerTests` mocks collaborators the controller doesn't use (`UserRegistrationService`, `CurrentUserService`) and uses raw `status().isForbidden()` instead of the shared `AccessControlAssertions.assertForbidden(...)` helper used elsewhere (`RoleGatingPatternTests.java:107`). (b) `list.html` declares `xmlns:sec` but uses no `sec:*` attributes yet. All harmless; consolidate for consistency.
- **Fix**: Trim unused `@MockitoBean`s (if the context still loads), prefer `assertForbidden(...)`, and either drop the unused `xmlns:sec` or leave it for the Phase 2 admin actions that will use it.
- **Decision**: FIXED
