# Account Deletion (S-07) Implementation Plan

## Overview

A signed-in user can permanently delete their own account. From a "Danger zone" block on `/profile`
they reach a dedicated confirm page; confirming issues a CSRF-protected POST that deletes their
visited list, preference profile, and user row in one transaction, invalidates their session, and
redirects to the public landing with a "deleted" banner. Deletion is immediate — no undo window
(PRD Open Question 4, resolved 2026-06-16). PRD refs: FR-004, FR-005, and the "permanently delete
account" NFR (`prd.md:144`).

## Current State Analysis

From `context/changes/account-deletion/research.md` (2026-07-02, verified against the code):

- A user's data lives in exactly three tables: `users` (1 row), `preference_profiles` (0–1 row,
  `UNIQUE(user_id)`) with its `@ElementCollection` child `preference_profile_regions`, and
  `visited_resorts` (0–n rows). No other table references a user.
- **There is no DB-level cascade.** `preference_profiles.user_id` has a plain FK without
  `ON DELETE CASCADE` (`V3__create_preference_profiles.sql`), `preference_profile_regions` has a
  non-cascading FK to `preference_profiles`, and `visited_resorts` has no FK at all
  (`V4__create_visited_resorts.sql`). A naive `userRepository.delete(user)` throws an
  FK violation while a profile exists.
- The session aftermath is pre-built: `StaleAuthenticatedSessionFilter`
  (`src/main/java/com/nextslope/config/StaleAuthenticatedSessionFilter.java:20-57`) logs out any
  session whose principal no longer maps to a user row, and `StaleSessionIntegrationTests` already
  proves post-deletion behavior (gated → `/login`, home renders signed-out).
- No account/settings surface or delete route exists. No `AccountService`/`UserService` exists —
  the delete orchestration is net-new.
- All reusable patterns exist: principal-scoped controllers via
  `CurrentUserService.requireUserId(principal)` (`CurrentUserService.java:21-26`), CSRF-protected
  POST forms, the `?logout` query-param banner on `index.html:16-19`, and the full two-user +
  dual-engine test scaffolding.

## Desired End State

- `/profile` shows a "Danger zone" block linking to `GET /account/delete`.
- `GET /account/delete` renders a server-rendered confirm page stating that deletion is permanent
  and removes the profile and visited list; it contains a single CSRF-protected POST form.
- `POST /account/delete` deletes visited rows → profile (with its regions) → user row in one
  transaction, invalidates the caller's session, and redirects to `/?deleted`.
- `index.html` shows a success banner when `?deleted` is present.
- After deletion the user's data never reappears on any surface (satisfied structurally: all
  reads are principal-scoped by `userId` and the rows are gone; no admin surface reads
  profile/visited data).
- Verified by: `./gradlew test` green (including new dual-engine cascade tests, controller slice,
  CSRF, permit-list lock, and end-to-end integration tests) plus a manual browser walk-through.

### Key Discoveries:

- Deletion order is load-bearing: `visited_resorts` (bulk, no FK) → `preference_profiles` **as a
  managed entity** so Hibernate removes `preference_profile_regions` first → `users`
  (research.md §B).
- A derived `deleteByUserId` on `PreferenceProfileRepository` would be a bulk delete that skips
  the element collection and violates `fk_preference_profile_regions_profile` — the profile must
  go through `findByUserId(...)` → `repository.delete(entity)` (research.md §B).
- `VisitedResortRepository` already has the safe derived-bulk-delete precedent
  (`deleteByUserIdAndResortId`, `VisitedResortRepository.java:11-22`); `deleteByUserId` follows it.
- After session invalidation a flash attribute won't survive — success must be signaled with a
  redirect query param, mirroring the `?logout` banner (`index.html:16-19`).
- `TwoUserIntegrationTestBase.clearFixtureUsers()` does `userRepository.deleteAll()` in a
  superclass `@AfterEach`; subclass `@AfterEach` runs first and must clear profile/visited rows
  (pattern: `PreferenceProfileOwnershipIntegrationTests.java:33-45`).
- `SecurityConfig.filterChain` gates `anyRequest().authenticated()`, so the new `/account/delete`
  routes are gated with no config change (`SecurityConfig.java:47-67`); lock this in
  `PermitListLockTests`.

## What We're NOT Doing

- No new Flyway migration — the cascade lives in the application layer (decided at planning;
  matches the app's "plain `Long` FK, app owns integrity" convention).
- No undo/grace window, no soft delete, no "export my data" step.
- No new `/account` settings page — the entry point is a block on the existing `/profile` page.
- No last-admin guard and no admin-specific behavior: every role uses the same self-delete flow.
  Admin recovery is out-of-band via `AdminBootstrap` (PRD has no multi-admin management in v1).
- No admin-deletes-user capability (that would be a different, id-in-path surface).
- No typed-confirmation field or JS modal — a dedicated server-rendered confirm page is the
  confirmation affordance.
- No changes to `StaleAuthenticatedSessionFilter` — it already covers other live sessions of the
  deleted account.

## Implementation Approach

Two phases, backend-first. Phase 1 builds and proves the transactional cascade
(`AccountService.deleteAccount(userId)`) on both engines — this is the only genuinely risky part
of the slice, and the FK-ordering/element-collection bug is only catchable on real Postgres.
Phase 2 wires the user-facing flow (confirm page, controller, session invalidation, banner,
danger zone) plus the security test net, mirroring existing principal-scoped controller and PRG
patterns.

## Critical Implementation Details

### Cascade ordering & the element-collection trap

Delete in this order inside one `@Transactional` method: (1) `visited_resorts` by bulk
`deleteByUserId` (no FK, safe), (2) the profile via `preferenceProfileRepository.findByUserId(userId)`
→ `delete(entity)` — never a derived `deleteByUserId`, which would orphan
`preference_profile_regions` rows and violate its FK, (3) the `users` row. H2 will not reliably
catch a wrong order; the Testcontainers Postgres test is the real proof.

### Session invalidation & success signaling

Invalidate the caller's own session after the service call succeeds (the in-repo primitive is
`SecurityContextLogoutHandler`, already used by `StaleAuthenticatedSessionFilter`), then
`redirect:/?deleted`. Flash attributes will not survive the invalidation — use the query-param
banner pattern from `?logout`.

### Integration-test cleanup ordering

JUnit runs subclass `@AfterEach` before the superclass's. Any S-07 test seeding profile/visited
rows must delete them in its own `@AfterEach` (or rely on the delete-under-test having removed
them) or `TwoUserIntegrationTestBase.clearFixtureUsers()` will hit the `preference_profiles` FK.

---

## Phase 1: Deletion Cascade Service

### Overview

Create the transactional application-level cascade and prove it on both engines (H2 +
Testcontainers Postgres), including the "user with profile + regions + visited rows" worst case
and the "bare user, no data" simplest case.

### Changes Required:

#### 1. Visited bulk delete by user

**File**: `src/main/java/com/nextslope/visited/VisitedResortRepository.java`

**Intent**: Add the by-user bulk delete the cascade needs. Safe as a derived delete because
`visited_resorts` has no child table.

**Contract**: `@Modifying long deleteByUserId(Long userId)` — mirror the existing
`deleteByUserIdAndResortId` declaration style. (Transaction demarcation belongs to the calling
service; a repository-level `@Transactional` like the existing method's is acceptable but the
service transaction is authoritative.)

#### 2. Account deletion orchestrator

**File**: `src/main/java/com/nextslope/user/AccountService.java` (new)

**Intent**: The single place that knows how to remove every trace of a user, in the
FK-safe order. Keeps orchestration out of the controller, matching `PreferenceProfileService` /
`VisitedResortService` layering.

**Contract**: `@Service`, constructor injection (Lombok `@RequiredArgsConstructor`), one public
method `@Transactional void deleteAccount(Long userId)`:

```java
visitedResortRepository.deleteByUserId(userId);
preferenceProfileRepository.findByUserId(userId)
        .ifPresent(preferenceProfileRepository::delete); // entity delete → regions cascade
userRepository.deleteById(userId);
```

(Snippet included because the ordering and the entity-vs-bulk distinction are the load-bearing
contract other phases and tests depend on.)

#### 3. Dual-engine cascade tests

**Files**: `src/test/java/com/nextslope/user/AccountServiceTests.java` (new, H2) and
`src/test/java/com/nextslope/user/AccountServicePostgresTests.java` (new, Testcontainers)

**Intent**: Prove the cascade removes all four tables' rows for the target user, leaves another
user's data untouched, and works when the user has no profile/visited rows. The Postgres variant
is the one that actually catches FK-ordering / element-collection bugs — mirror the
`PreferenceProfileRepositoryTests` / `...PostgresTests` split.

**Contract**: Both cover at minimum: (a) user with profile + ≥1 region + ≥2 visited rows → after
`deleteAccount`, zero rows for that user in `users`, `preference_profiles`,
`preference_profile_regions`, `visited_resorts`; (b) second user's rows unaffected; (c) user with
no profile and no visited rows → delete succeeds. Assert `preference_profile_regions` emptiness
via a native/JPQL count, not just entity absence.

### Success Criteria:

#### Automated Verification:

- Full suite passes (both engines): `./gradlew test`
- New cascade tests pass in isolation: `./gradlew test --tests "com.nextslope.user.AccountService*"`

#### Manual Verification:

- None required — this phase has no user-facing surface. (Optional: exercise `deleteAccount`
  against the H2 console to eyeball row removal.)

**Implementation Note**: After completing this phase and all automated verification passes, pause
here for confirmation before proceeding to Phase 2.

---

## Phase 2: Web Flow & Security Wiring

### Overview

Wire the user-facing flow: danger zone on `/profile`, confirm page, delete POST with session
invalidation and `?deleted` banner — plus the security test net (route lock, CSRF, slice test,
end-to-end integration test).

### Changes Required:

#### 1. Account controller

**File**: `src/main/java/com/nextslope/web/AccountController.java` (new)

**Intent**: Thin principal-scoped controller for the confirm page and the delete action. No id in
the path — the target is always the authenticated principal (no IDOR surface), mirroring
`ProfileController`.

**Contract**:
- `GET /account/delete` → resolves the principal via `CurrentUserService.requireUserId` (401
  behavior for stale principals comes free), returns the `account/confirm-delete` view.
- `POST /account/delete` → `requireUserId(principal)` → `accountService.deleteAccount(userId)` →
  invalidate the caller's session/security context (`SecurityContextLogoutHandler`) →
  `redirect:/?deleted`. The POST form carries the auto-injected CSRF token; the route stays inside
  the `anyRequest().authenticated()` gate — no `SecurityConfig` change.

#### 2. Confirm page

**File**: `src/main/resources/templates/account/confirm-delete.html` (new)

**Intent**: The explicit confirmation the NFR requires. States that deletion is permanent and
immediately removes the preference profile and visited list, with no undo.

**Contract**: Server-rendered Thymeleaf page using `fragments/layout` head/navbar/scripts like
`profile/form.html`; one `th:action="@{/account/delete}"` POST form with a danger-styled submit
("Permanently delete my account") and a cancel link back to `/profile`. No JS.

#### 3. Danger zone on the profile page

**File**: `src/main/resources/templates/profile/form.html`

**Intent**: Give the delete flow a discoverable, deliberately separated entry point on the
existing profile page.

**Contract**: A visually distinct "Danger zone" block below the profile form card (outside the
`<form>`), containing a short warning and a link (not a form) to `/account/delete`.

#### 4. Deleted banner on the landing page

**File**: `src/main/resources/templates/index.html`

**Intent**: Confirm success to the now-signed-out user.

**Contract**: `th:if="${param.deleted}"` success alert ("Your account has been deleted.") next to
the existing `th:if="${param.logout}"` banner.

#### 5. Route lock & CSRF enforcement

**Files**: `src/test/java/com/nextslope/PermitListLockTests.java`,
`src/test/java/com/nextslope/CsrfEnforcedTests.java`

**Intent**: Lock the new route into the security regression net so an accidental `permitAll()`
widening or CSRF exemption fails CI.

**Contract**: Add `/account/delete` to `mustStayGatedPathsRedirectAnonymousToLogin`'s
`@ValueSource`; assert `POST /account/delete` without a CSRF token is rejected (extend
`CsrfEnforcedTests` following its existing template).

#### 6. Controller slice test

**File**: `src/test/java/com/nextslope/web/AccountControllerTests.java` (new)

**Intent**: Fast verification of the controller contract without booting the full context.

**Contract**: `@WebMvcTest(controllers = AccountController.class)` + `@Import({SecurityConfig.class,
AppUserDetailsService.class})` + `@MockitoBean` collaborators, per the repo convention. Stub
`userRepository.existsByEmail(...) → true` so `StaleAuthenticatedSessionFilter` passes the request.
Cover: GET renders the confirm view; POST `.with(csrf())` calls `deleteAccount` with the resolved
userId, redirects to `/?deleted`, and leaves the request unauthenticated/session invalidated.

#### 7. End-to-end integration test

**File**: `src/test/java/com/nextslope/AccountDeletionIntegrationTests.java` (new)

**Intent**: Prove the whole slice on a live context: confirm → delete → data gone → session dead →
other user untouched.

**Contract**: Extends `TwoUserIntegrationTestBase`. Seed profile (+regions) and visited rows for
users A and B; as A: GET `/account/delete` renders, POST (with session + csrf) redirects to
`/?deleted`; then assert A's rows are gone from all four tables, B's remain, A's old session on a
gated page redirects to `/login` (reuse the `StaleSessionIntegrationTests` shape), and `/?deleted`
renders the banner signed-out. Own `@AfterEach` clears any surviving profile/visited rows before
the base class's `deleteAll()` (see Critical Implementation Details).

### Success Criteria:

#### Automated Verification:

- Full suite passes: `./gradlew test`
- Security net passes in isolation: `./gradlew test --tests "com.nextslope.PermitListLockTests" --tests "com.nextslope.CsrfEnforcedTests"`
- New web tests pass: `./gradlew test --tests "com.nextslope.web.AccountControllerTests" --tests "com.nextslope.AccountDeletionIntegrationTests"`

#### Manual Verification:

- Browser walk-through on `bootRun`: sign up → create profile + mark a resort visited → Danger
  zone on `/profile` → confirm page reads clearly → delete → land on `/` with the banner, signed
  out.
- Back button / re-login checks: after deletion, gated pages redirect to `/login`; re-registering
  with the same email starts from a blank profile and empty visited list.
- Confirm-page cancel link returns to `/profile` without side effects.

**Implementation Note**: After completing this phase and all automated verification passes, pause
here for manual confirmation that the browser testing was successful.

---

## Testing Strategy

### Unit Tests:

- Cascade correctness and ordering via `AccountServiceTests` (H2): full-data user, bare user,
  neighbor-user isolation, `preference_profile_regions` emptied.

### Integration Tests:

- `AccountServicePostgresTests` (Testcontainers, Postgres 16) — the authoritative FK-order /
  element-collection proof; H2 alone won't catch it.
- `AccountDeletionIntegrationTests` — end-to-end flow incl. session death and two-user isolation.
- `PermitListLockTests` + `CsrfEnforcedTests` extensions — security regression net.
- `AccountControllerTests` — controller slice.

### Manual Testing Steps:

1. `./gradlew bootRun`; sign up a fresh user, fill the profile with ≥1 region, mark ≥1 resort visited.
2. `/profile` → Danger zone → confirm page → delete. Expect redirect to `/` with the
   "account deleted" banner, navbar signed-out.
3. Hit `/resorts` directly (or via back button) → redirected to `/login`.
4. Re-register with the same email → blank profile, empty visited list, `/recommend` treats the
   account as new.

## Performance Considerations

None material: the delete touches ≤ a few dozen rows across four tables in one short transaction.
No new read paths.

## Migration Notes

No schema change; `ddl-auto=validate` stays green with no new Flyway migration. The change is
purely additive at the code level, so rollback is a normal revert of the change branch. Rows
deleted in production are unrecoverable by design (Neon free has no rollback) — that is the
documented product behavior, not a migration risk.

## References

- Related research: `context/changes/account-deletion/research.md`
- Principal-scoped controller + PRG pattern: `src/main/java/com/nextslope/web/ProfileController.java:33-74`
- Stale-session safety net: `src/main/java/com/nextslope/config/StaleAuthenticatedSessionFilter.java:20-57`
- Query-param banner precedent: `src/main/resources/templates/index.html:16-19`
- Security chain (routes stay gated): `src/main/java/com/nextslope/config/SecurityConfig.java:47-67`
- `@AfterEach` cleanup-order pattern: `src/test/java/com/nextslope/profile/PreferenceProfileOwnershipIntegrationTests.java:33-45`
- Gated-path lock: `src/test/java/com/nextslope/PermitListLockTests.java:87-96`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Deletion Cascade Service

#### Automated

- [x] 1.1 Full suite passes (both engines): `./gradlew test` — 5548248
- [x] 1.2 New cascade tests pass in isolation: `./gradlew test --tests "com.nextslope.user.AccountService*"` — 5548248

### Phase 2: Web Flow & Security Wiring

#### Automated

- [ ] 2.1 Full suite passes: `./gradlew test`
- [ ] 2.2 Security net passes in isolation (`PermitListLockTests`, `CsrfEnforcedTests`)
- [ ] 2.3 New web tests pass (`AccountControllerTests`, `AccountDeletionIntegrationTests`)

#### Manual

- [ ] 2.4 Browser walk-through: profile → danger zone → confirm → delete → `/?deleted` banner, signed out
- [ ] 2.5 Post-delete access checks: gated pages redirect to `/login`; re-registering same email starts blank
- [ ] 2.6 Confirm-page cancel link returns to `/profile` without side effects
