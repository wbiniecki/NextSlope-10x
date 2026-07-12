# Account Deletion (S-07) — Plan Brief

> Full plan: `context/changes/account-deletion/plan.md`
> Research: `context/changes/account-deletion/research.md`

## What & Why

A signed-in user can permanently delete their own account: from a "Danger zone" on `/profile`,
through a dedicated confirm page, their preference profile, visited list, and user row are removed
immediately and irreversibly (no undo — PRD Open Question 4, resolved 2026-06-16). This closes the
privacy loop promised by FR-004/FR-005 and the "permanently delete account" NFR.

## Starting Point

The schema has **no DB-level cascade**: the profile FK to `users` has no `ON DELETE CASCADE`, the
regions child table has a non-cascading FK to profiles, and `visited_resorts` has no FK at all — so
a naive user delete throws an FK violation. The session aftermath is already solved:
`StaleAuthenticatedSessionFilter` was built for this slice and kills any lingering session of a
deleted account. No delete route, account surface, or `AccountService` exists yet.

## Desired End State

A user who confirms deletion lands signed-out on the public landing page with an "account deleted"
banner. All four tables (`users`, `preference_profiles`, `preference_profile_regions`,
`visited_resorts`) hold zero rows for them, their old session is dead, and re-registering with the
same email starts from a completely blank slate. Other users' data is untouched.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Cascade location | Application-level `@Transactional AccountService.deleteAccount(userId)` | No new migration; matches the app's "plain `Long` FK, app owns integrity" convention | Research / Plan |
| Deletion order | visited (bulk) → profile as managed entity → user | Entity delete is the only path that cascades the regions element collection; derived bulk delete would violate its FK | Research |
| Control placement | "Danger zone" block on existing `/profile` page | Smallest change; no other account controls exist to justify a new settings page | Plan |
| Confirmation affordance | Dedicated server-rendered confirm page (`GET /account/delete` → POST) | Satisfies the explicit-confirmation NFR with no JS, matching the no-build-step rule | Plan |
| Own-session handling | Explicit invalidation + redirect to `/?deleted` banner | Mirrors the sign-out contract; flash attributes can't survive invalidation, so a query param signals success | Research / Plan |
| Admin self-delete | Same flow for everyone, no last-admin guard | v1 has no multi-admin management; admin recovery is out-of-band via `AdminBootstrap` | Plan |

## Scope

**In scope:**
- `AccountService.deleteAccount(userId)` transactional cascade + `VisitedResortRepository.deleteByUserId`
- `AccountController` (`GET`/`POST /account/delete`), confirm page, danger zone on `/profile`, `?deleted` banner
- Dual-engine cascade tests (H2 + Testcontainers Postgres), controller slice, CSRF + permit-list locks, end-to-end two-user integration test

**Out of scope:**
- New Flyway migration / DB-level cascade; undo window or soft delete; data export
- New `/account` settings page; admin-deletes-user; last-admin guard; typed-confirmation UX
- Changes to `StaleAuthenticatedSessionFilter` (already covers other live sessions)

## Architecture / Approach

A thin principal-scoped controller (no id in the path — no IDOR surface) resolves the user via
`CurrentUserService`, delegates to a new `AccountService` that deletes in FK-safe order inside one
transaction, then invalidates the caller's session and redirects to the landing page. The UI is
three small Thymeleaf touches (danger zone, confirm page, banner) following existing patterns.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Deletion Cascade Service | `deleteAccount(userId)` proven on H2 + real Postgres | FK-ordering / element-collection bug that only Postgres catches |
| 2. Web Flow & Security Wiring | Confirm page, delete POST, session invalidation, banner + full security test net | Session-invalidation edge cases; accidental route/CSRF widening (locked by tests) |

**Prerequisites:** S-02 (preference-profile) and S-04 (mark-visited) — both done. Docker available for Testcontainers.
**Estimated effort:** ~2 sessions, one per phase.

## Open Risks & Assumptions

- Assumes no other table will reference `users` before this lands; if S-06/S-08 add one first, the cascade needs a new step.
- Production deletes are unrecoverable by design (Neon free has no rollback) — accepted product behavior.

## Success Criteria (Summary)

- Confirming deletion signs the user out onto `/?deleted` and removes every row of theirs across all four tables, leaving other users untouched.
- The old session can't reach any gated page afterwards; re-registering the same email starts blank.
- `./gradlew test` green, including the Testcontainers Postgres cascade proof and the extended security locks.
