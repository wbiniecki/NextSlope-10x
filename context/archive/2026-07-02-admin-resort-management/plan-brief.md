# Admin Resort Management (S-06) — Plan Brief

> Full plan: `context/changes/admin-resort-management/plan.md`
> Research: `context/changes/admin-resort-management/research.md`

## What & Why

Give a signed-in **admin** an admin-only view to **create**, **edit**, and
**deactivate/reactivate** resort entries. Resort facts change over the season (new lifts, revised
slope counts) and editing them outside the product is fragile — FR-010–FR-013 / US-03. This is also
the app's first real role-based authorization enforcement.

## Starting Point

Deactivation is already wired at the read side — the `active` column exists and every user-facing read
path filters through `findByActiveTrue*`, so deactivating is a field-flip with **no migration**.
Visited references survive deactivation by design (FK-free `visited_resorts`, proven by an existing
test). What's missing: any admin authorization enforcement (production `SecurityConfig` is binary), a
`ResortService`, a form DTO, and the admin UI. Roles, `ROLE_ADMIN` mapping, `AdminBootstrap`, and the
role-gating test scaffolding all already exist.

## Desired End State

After login, an admin sees an Admin entry point (landing button + navbar link) → `/admin/resorts`
lists all resorts (active + inactive). They add resorts via a validated form, edit them (untouched
fields preserved), and toggle active state in place. Deactivated resorts vanish from browse and
recommendations while prior visited marks still work; non-admins get 403.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Form data model | Collect slope **counts**; derive % mix + `totalSlopes` | Matches the entity/engine exactly, no lossy %→count round-trip; sum-to-100 becomes inherent | Plan |
| US-03 "% sum to 100" | Reframed as inherent (no custom validator) | Counts make the derived mix always sum to 100 — documented deviation | Plan |
| Authorization seam | URL-level `/admin/**` → `hasRole("ADMIN")` | Smallest change, matches the existing filter-chain style, real USER→403 without method security | Plan |
| Deactivate control | HTMX in-place toggle (mirror `VisitedController`) | Reuses a built/tested pattern; activate↔deactivate maps onto mark↔unmark | Plan |
| Access-denied UX | Default bare HTTP 403 | Satisfies the PRD literally; keeps scope tight | Plan |
| `externalId` | Optional field, default NULL, admin may set/edit (uniqueness-checked) | Nullable `UNIQUE` column; keeps admin-created resorts clean but flexible | Plan |
| Local admin access | `@Profile("!prod")` dev admin seed | Frictionless local testing, provably impossible in prod | Plan |
| `totalSlopes` | Normalized to sum of the three band counts on save | One coherent "number of slopes"; keeps the derived mix truthful | Plan |
| Edit safety | `update` loads entity, sets only managed fields | Prevents wiping the ~19 columns the form doesn't cover | Plan |

## Scope

**In scope:** admin gate (`/admin/**`), admin resort list (incl. inactive), create/edit form +
validation, deactivate/reactivate toggle, navigation entry points, local dev admin seed, tests.

**Out of scope:** percentages input / sum-to-100 validator, custom 403 page, editing non-PRD fields,
delete, audit/change-history, role-management UI, admin search/pagination.

## Architecture / Approach

New `AdminResortController` (all routes under `/admin/resorts…`, covered by one URL matcher) → new
`ResortService` (owns admin reads + create/update/toggle, `externalId` uniqueness,
unmanaged-field-preserving edit) → `ResortRepository` (+ un-filtered finder, `findByExternalId`).
Templates mirror existing patterns: list ≈ `resorts/list.html`, form ≈ `profile/form.html`, toggle ≈
`VisitedController` + fragment. Auth gate is one line in `SecurityConfig`. No schema change.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Gate + list + nav | Gated `/admin/resorts` list (active+inactive), admin nav links, dev admin seed | Matcher ordering; dev-admin must be provably non-prod |
| 2. Create & edit | Validated form + service write path, live % preview, PRG | Edit must preserve unmanaged fields; `externalId` uniqueness/race |
| 3. Deactivate/reactivate | HTMX toggle + FR-013 lifecycle guarantees | In-place row restyle; proving browse/recommendation exclusion + visited survival |

**Prerequisites:** S-01 (auth/roles) and S-03 (resort model) — both done. Local: dev admin seed lands
in Phase 1.
**Estimated effort:** ~3 sessions, one per phase.

## Open Risks & Assumptions

- `totalSlopes` re-normalization on edit will change stored totals for any seed resort where the
  stored total diverged from the band sum (intended, but a visible data change).
- The form's client-side % preview must replicate the server's largest-remainder rounding to avoid a
  confusing preview-vs-stored mismatch.
- Assumes admin assignment stays out-of-band (no self-service promotion), per PRD.

## Success Criteria (Summary)

- Admin can create/edit/deactivate/reactivate resorts from a reachable admin-only view; non-admins get
  403.
- Deactivated resorts disappear from browse + recommendations; prior visited marks still work;
  reactivation restores them.
- Full `./gradlew test` green, including the admin 403/CRUD/toggle tests and the deactivation
  integration test.
