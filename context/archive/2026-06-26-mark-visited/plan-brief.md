# Mark Resorts as Visited (S-04) — Plan Brief

> Full plan: `context/changes/mark-visited/plan.md`

## What & Why

Let a signed-in user mark and unmark resorts as visited directly from the browse
list, with the state updating in place (no full page reload) and the visited list
kept strictly private to that user. Why: it's a direct prerequisite of the north
star (S-05 Recommend, which honors the visited list), and it establishes the
app's first HTMX in-place-update + CSRF-delivery pattern that S-05 reuses.

## Starting Point

The `user`, `resort`, and `profile` domains exist; `htmx.min.js` is already
loaded in the shared layout but nothing uses it. `/resorts` renders a plain
`th:each` table with no per-row action. `PreferenceProfile` is the precedent for
per-user data (plain `user_id` FK, principal-scoped, no cross-user route), and
the test scaffolding (`OwnershipPatternIntegrationTests`,
`AccessControlAssertions.assertWrongOwnerDenied`) explicitly names S-04 as the
slice that fills the ownership/isolation seam.

## Desired End State

Every active resort row carries a "Mark visited" / "Visited ✓" toggle; clicking
it flips the database state and swaps just the control in place via HTMX, with a
subtle row highlight when visited. The list is private (no other user, including
an admin, can read it; users never affect each other) — proven by a two-user
isolation test.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Persistence model | Dedicated `VisitedResort` join entity (`user_id`, `resort_id`, `created_at`) + `UNIQUE(user_id, resort_id)` | Explicit, queryable, audit-friendly — mirrors the lean plain-FK style of `PreferenceProfile` over a `@ManyToMany` | Plan |
| Interaction shape | HTMX POST returns the single updated control fragment, swapped `outerHTML` | Smallest in-place update; the pattern S-05 inherits | Plan |
| Control placement | Browse list only | Matches US-02 / roadmap wording; detail-page toggle is a later nicety | Plan |
| CSRF for HTMX | `<meta>` token tags in layout `<head>` + global `htmx:configRequest` header injection | One-time shared wiring reused by S-05; leaves the pinned server CSRF config untouched | Plan |
| S-05 read model | Add only the queries needed now (`existsByUserIdAndResortId`, `findResortIdsByUserId`, delete) | Minimal surface; S-05 reuses them without over-building | Plan |
| Deactivated resorts | Mark active-only; unmark always allowed | Honors FR-013 (stale visited references keep working and can always be cleared) | Plan |
| Unknown/inactive-on-mark resort | `404` (mirror `ResortController.detail`) | Consistent with the existing resort-not-found behavior | Plan |
| Testing | Dual-engine repo test + controller `@WebMvcTest` + two-user isolation test | Matches repo conventions; privacy guardrail is tested, not deferred | Plan |

## Scope

**In scope:** join-table migration + entity/repository; owner-scoped service;
authenticated POST toggle returning a fragment; per-row HTMX control + CSRF
wiring; privacy isolation test; minimal read query for S-05.

**Out of scope:** detail-page control; a "my visited" page; using visited data in
recommendations (S-05); visited count/badge/sort/filter; bulk actions;
account-deletion cascade (S-07).

## Architecture / Approach

New `com.nextslope.visited` package: `VisitedResort` entity + repository →
`VisitedResortService` (owner-scoped, toggle/exists/ids) → `VisitedController`
(`POST /resorts/{id}/visited`, principal-resolved userId, returns the
`visitedToggle` fragment). `ResortController.list` gains the user's visited ids
for initial render. The shared layout delivers the CSRF token to HTMX. Security
auto-gates the new route; no `SecurityConfig` change.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Persistence | `V4` migration, `VisitedResort` entity + repository, dual-engine repo tests | Cross-engine DDL parity (H2/Postgres) |
| 2. Service + endpoint + privacy | Owner-scoped service, POST toggle returning fragment, two-user isolation test | Getting the active-on-mark / always-on-unmark asymmetry right; privacy correctness |
| 3. List UI + HTMX wiring | Per-row control, CSRF-to-HTMX wiring, in-place swap + highlight | First-time HTMX/CSRF integration (no token → silent 403) |

**Prerequisites:** S-01 (auth) and S-03 (resort catalog) — both done.
**Estimated effort:** ~1 session across 3 phases (small, well-precedented slice).

## Open Risks & Assumptions

- HTMX/CSRF is net-new; a missing token surfaces as a silent `403` on toggle —
  Phase 3 manual check explicitly watches the network tab.
- `UserDetails` carries only the email, so each handler resolves `userId` via
  `findByEmail`; duplicating the existing `currentUserId` helper is accepted
  (a shared helper is optional).

## Success Criteria (Summary)

- A user can mark/unmark any active resort from `/resorts`, updating in place and
  persisting across refreshes.
- The visited list is private: invisible to other users and to admins, with no
  cross-user bleed (isolation test green).
- `./gradlew test` is green, including the Testcontainers Postgres repo test.
