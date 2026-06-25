# Resort Catalog & Browsing (S-03) — Plan Brief

> Full plan: `context/changes/resort-catalog-browse/plan.md`

## What & Why

Give a signed-in user the resort discovery surface the product is built around:
browse the full resort list with key facts and open a single resort's detail view
(PRD FR-006 / FR-007). This is the first slice to need resort data, so it also
establishes the resort schema and an idempotent CSV-fed seed that every later
slice (mark-visited, recommendation, admin) builds on.

## Starting Point

Only the `user` domain exists. There is no resort entity, repository, controller,
template, or migration — `V1__create_users.sql` is the sole migration. An
idempotent `ApplicationRunner` seed pattern already exists (`AdminBootstrap`), the
security chain already gates any new route behind authentication, and the
Bootstrap+HTMX CDN tags are inlined per-template (no shared layout yet). The
curated 40-resort Europe CSV is already shipped at
`src/main/resources/data/resorts-Europe-subset.csv`.

## Desired End State

`/resorts` lists all active resorts in one table sorted by country then name (name,
top lift height, slopes, lifts, difficulty mix), each linking to `/resorts/{id}`
with the full fact set. The table is populated with exactly 40 seeded rows; the
seed re-runs the wiped local H2 every boot and no-ops on the populated prod DB.
Unauthenticated requests redirect to `/login`.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Persisted columns | Full CSV richness | A substantial detail view; data is already curated and free. | Plan |
| Difficulty representation | Store raw counts, derive % | Lossless to source; percentages computed for display/S-05. | Plan |
| `active` flag | Add now (default true) | Browse filters active from day one; no S-06 schema rework. | Plan |
| Seed mechanism | Idempotent `ApplicationRunner` + empty-table guard | Reuses `AdminBootstrap`; one guard covers dev re-seed + prod once. | Plan |
| CSV parsing | Commons CSV (1.14.1) + UTF-8 reader | Handles the one quoted-comma field & accents correctly. | Plan |
| Identity | Internal generated PK + nullable-unique `external_id` | Internal id in URLs; CSV id is admin/internal only, never shown. | Plan |
| Layout | Shared fragment for new pages only | DRY for future pages without refactoring working auth templates. | Plan |
| List UX | Flat table, sorted country-then-name | Correct & simple at 40 rows; search/filter is parked. | Plan |
| Testing | Full standard (incl. dual-engine + seed/parse) | Matches the AGENTS.md bar for a new data-bearing table. | Plan |

## Scope

**In scope:** `resorts` table (`V2` migration), `Resort` entity + repository, CSV
seed loader, shared layout fragment, `ResortController` (`/resorts`,
`/resorts/{id}`), list + detail templates, full test coverage.

**Out of scope:** mark-visited (S-04), admin CRUD / flipping `active` (S-06),
recommendation/scoring (S-05), search/filter/pagination (parked), showing
`external_id` to users, migrating existing auth templates onto the fragment.

## Architecture / Approach

New `com.nextslope.resort` package (entity, repository, seed loader);
`ResortController` joins the existing `com.nextslope.web` controllers. Flow:
Flyway `V2` creates `resorts` → `ResortSeedLoader` (UTF-8 + Commons CSV) fills it
once when empty → `ResortController` serves gated reads via
`findByActiveTrueOrderByCountryAscNameAsc` / `findByIdAndActiveTrue` → Thymeleaf
list/detail render on a shared layout fragment. Internal surrogate `id` is the
URL key; CSV `ID` lives in `external_id` for seed dedupe and future admin use.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Domain & migration | `V2` table, `Resort` entity, repository, dual-engine tests | Cross-engine DDL/mapping parity (H2 ↔ Postgres) |
| 2. CSV seed loader | Commons CSV + idempotent UTF-8 seed of 40 rows | Quoted-comma field & UTF-8 names parsed correctly |
| 3. Browse list & detail UI | Layout fragment, controller, list + detail templates | Auth-gating correct; `external_id` never leaked to users |

**Prerequisites:** S-01 (account-authentication) — done. The CSV is already shipped.
**Estimated effort:** ~3 sessions, one per phase.

## Open Risks & Assumptions

- Difficulty-% rounding must sum to 100 from the three counts (largest-remainder or
  round-two-derive-third); guard a zero denominator.
- `external_id` is nullable-unique so S-06 admin-created resorts (no CSV id) fit
  without rework — assumes S-06 accepts internal-only ids for new resorts.
- Re-seeding the wiped H2 on every dev boot is intended behavior, not a bug.

## Success Criteria (Summary)

- A signed-in user browses 40 resorts and opens any detail page; signed-out users
  are redirected to login.
- The seed is idempotent across both engines; `./gradlew test` is green including
  the Testcontainers Postgres repo test and seed/parse tests.
- The CSV `external_id` never appears on a user-facing page.
