---
project: "NextSlope"
version: 1
status: draft
created: 2026-06-12
updated: 2026-06-25
prd_version: 1
main_goal: speed
top_blocker: time
---

# Roadmap: NextSlope

> Derived from `context/foundation/prd.md` (v1) + auto-researched codebase baseline.
> Edit-in-place; archive when superseded.
> Slices below are listed in dependency order. The "At a glance" table is the index.

## Vision recap

An avid skier or snowboarder planning the upcoming season drowns in scattered, multi-criteria resort data and finishes hours of tab-juggling with a low-confidence pick. NextSlope fuses **objective resort facts** (top lift height, slopes, lifts, difficulty mix, location) with **the user's own preferences** (experience, difficulty taste, region, novelty) and returns exactly three ranked recommendations, each with a one-line rationale that traces back to what the user actually told it. The whole MVP is a single region of 20–40 resorts, shipped in roughly three after-hours weeks.

## North star

**S-05: user gets three ranked recommendations with a truthful rationale** — this is the smallest end-to-end flow whose successful delivery proves the product's core idea: that preferences + facts can be turned into three trustworthy, explainable picks. Everything else (accounts, profile, browsing, visited-marking, admin curation) only matters because it feeds this one moment.

> "North star" (used interchangeably below with "validation milestone") here means the single slice that, once it works end-to-end, validates the reason the product exists — sequenced as early as its prerequisites allow, never deferred for symmetry.

## At a glance

| ID | Change ID | Outcome (user can …) | Prerequisites | PRD refs | Status |
|---|---|---|---|---|---|
| F-01 | persistence-migration-baseline | (foundation) schema migrations + persistence conventions wired and verified on the local and production databases | — | Access Control, NFR (determinism, privacy) | done |
| S-01 | account-authentication | sign up, sign in, and sign out with a real user + role model behind gated routes | F-01 | US-01, FR-001, FR-002, FR-003 | done |
| S-02 | preference-profile | create and edit a preference profile (experience, difficulty mix, region, novelty) | S-01 | US-01, FR-004 | proposed |
| S-03 | resort-catalog-browse | browse the resort list with key facts and open a single resort's detail view | S-01 | FR-006, FR-007 | proposed |
| S-04 | mark-visited | mark and unmark resorts as visited from the browse list, privately | S-01, S-03 | US-02, FR-005 | proposed |
| S-05 | three-resort-recommendation | click "Recommend resorts" and get exactly three ranked picks with a truthful rationale | S-02, S-03, S-04 | US-01, FR-008, FR-009 | proposed |
| S-06 | admin-resort-management | (admin) create, edit, and deactivate resort entries from an admin-only view | S-01, S-03 | US-03, FR-010, FR-011, FR-012, FR-013 | proposed |
| S-07 | account-deletion | permanently delete their account, removing profile and visited data everywhere | S-02, S-04 | FR-004, FR-005 | proposed |

## Streams

Navigation aid — groups items that share a Prerequisites chain. Canonical ordering still lives in the dependency graph below; this table is the proposed reading order across parallel tracks.

| Stream | Theme | Chain | Note |
|---|---|---|---|
| A | Accounts & profile | `F-01` → `S-01` → `S-02` → `S-07` | The must-have spine; `S-07` joins after `S-04` exists (cascade of visited data). |
| B | Resort discovery | `S-03` → `S-04` | Branches off `S-01`; runs parallel with Stream A's `S-02`. |
| C | Recommendation (north star) | `S-05` | Joins Stream A at `S-02` and Stream B at `S-04`; the validation milestone. |
| D | Admin curation | `S-06` | Joins Stream B at `S-03`; must-have but off the north-star path — parallelizable. |

## Baseline

What's already in place in the codebase as of `2026-06-12` (auto-researched + user-confirmed).
Foundations below assume these are present and do NOT re-scaffold them.

- **Frontend:** partial — Thymeleaf engine wired; `index.html` + `error.html` exist (`src/main/resources/templates/`). No base layout fragment, Bootstrap/HTMX CDN drop-ins, or domain views yet.
- **Backend / API:** partial — app boots; single `HomeController` serves the landing page (`src/main/java/com/nextslope/web/HomeController.java`). No domain controllers or services.
- **Data:** partial — JPA + H2 (local) + Postgres (prod) on the classpath and datasource wired (`application-prod.properties`), but no entities, repositories, migrations, or seed data (`ddl-auto=update` is a deliberate no-op).
- **Auth:** partial — `SecurityConfig` locks all non-public routes behind default form-login (`src/main/java/com/nextslope/config/SecurityConfig.java`). No User entity, sign-up, `UserDetailsService`, or USER/ADMIN roles.
- **Deploy / infra:** present — `Dockerfile`, `render.yaml`, `application-prod.properties` (Neon/Render-tuned), and GitHub Actions CI (`.github/workflows/ci.yml`) all exist; deployment plan in progress.
- **Observability:** minimal — actuator `/actuator/health` exposed (`application.properties`); nothing beyond defaults (sufficient for this MVP).

## Foundations

### F-01: Persistence & migration baseline

- **Outcome:** (foundation) a schema-migration mechanism and persistence (entity/repository) conventions are wired and verified to run identically against the local and production databases — the production profile's "migrations come later" gap is closed.
- **Change ID:** persistence-migration-baseline
- **PRD refs:** Access Control, NFR (determinism, privacy)
- **Unlocks:** S-01 (its first consumer, the account table), and every data-bearing slice S-02–S-07; reduces the open unknown "how is schema managed across the local/production database split"; establishes the migrations-run-in-CI verification path.
- **Prerequisites:** —
- **Parallel with:** —
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Sequenced first because every slice persists data and an ad-hoc per-slice schema choice would fragment the local/production database story. Kept minimal (tooling + conventions + one verifying migration), not a full data-layer build — S-01 immediately exercises it through real sign-up.
- **Status:** done

## Slices

### S-01: Account & authentication

- **Outcome:** A visitor can sign up with email + password, sign in, and sign out; gated routes are enforced against a real persisted user and role model, replacing the default-user scaffold.
- **Change ID:** account-authentication
- **PRD refs:** US-01, FR-001, FR-002, FR-003
- **Prerequisites:** F-01
- **Parallel with:** —
- **Blockers:** —
- **Unknowns:**
  - Session persistence / "remember me" behavior — Owner: TBD. Block: no. (PRD calls it an implementation choice.)
- **Risk:** First vertical slice; also the natural home for the shared page layout and base UI assets every later view reuses. The user/admin role distinction is modeled here even though admin enforcement arrives in S-06, so the model isn't reworked later. North-star prerequisite — kept lean to unblock the chain fast.
- **Status:** done

### S-02: Preference profile

- **Outcome:** A signed-in user can create and edit their preference profile — experience level, preferred difficulty mix, location/region, and novelty preference — and the edits persist across sessions.
- **Change ID:** preference-profile
- **PRD refs:** US-01, FR-004
- **Prerequisites:** S-01
- **Parallel with:** S-03, S-04, S-06
- **Blockers:** —
- **Unknowns:**
  - Exact shape of the difficulty-mix input (three percentages vs. preset bands) — Owner: TBD. Block: no.
- **Risk:** Edit (not just create) is mandatory because the Secondary success outcome — tweak a preference and re-run — depends on it. North-star prerequisite; independent of resort data, so it runs alongside S-03.
- **Status:** proposed

### S-03: Resort catalog & browsing

- **Outcome:** A signed-in user can browse the full resort list with key facts visible (name, location, top lift height, slopes, lifts, difficulty mix) and open a single resort's detail view.
- **Change ID:** resort-catalog-browse
- **PRD refs:** FR-006, FR-007
- **Prerequisites:** S-01
- **Parallel with:** S-02
- **Blockers:** —
- **Unknowns:** — (PRD Open Questions 1 & 2 resolved 2026-06-16, seed refined 2026-06-25: v1 ships a curated 40-resort Europe subset drawn from a ready ~500-resort worldwide set; the full set is parked, not in v1)
- **Risk:** First slice to need the resort data model, so it establishes that model (Flyway migration, schema only) and a CSV-fed seed (data only — never DDL). Seed must be idempotent and must not clobber later admin edits (S-06 writes the same table), so it seeds only when the table is empty rather than upserting every boot. Flat list stays correct at 40 entries (search/filter only needed past ~100); going worldwide later would re-open that.
- **Status:** proposed

### S-04: Mark resorts as visited

- **Outcome:** A signed-in user can mark and unmark resorts as visited directly from the browse list, with the visual state updating immediately and the list kept strictly private to that user.
- **Change ID:** mark-visited
- **PRD refs:** US-02, FR-005
- **Prerequisites:** S-01, S-03
- **Parallel with:** S-02, S-06
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Natural fit for an in-place partial update (no full page reload), exercising the interaction pattern the recommend button will reuse. Privacy guardrail (no other user, including admins, can see a visited list) must be enforced here, not deferred. Direct prerequisite of the north star.
- **Status:** proposed

### S-05: Three-resort recommendation

- **Outcome:** A signed-in user clicks "Recommend resorts" and sees exactly three ranked resorts (key facts + a one-line truthful rationale), or an explicit explanation when fewer than three viable matches exist — honoring hard filters (region, visited-when-new-only) then weighted scoring.
- **Change ID:** three-resort-recommendation
- **PRD refs:** US-01, FR-008, FR-009
- **Prerequisites:** S-02, S-03, S-04
- **Parallel with:** S-06, S-07
- **Blockers:** —
- **Unknowns:**
  - Soft-axis scoring weights (how much each preference axis contributes to alignment) — Owner: user/TBD. Block: no. (A defensible default ships; tuning is iterative.)
- **Risk:** The north star and the only "invest deeply" slice. Three guardrails converge here: results are deterministic (same inputs → same three in the same order), the rationale must reflect the actual ranking (not flavor text), and a sparse candidate set must produce an explicit explanation rather than padding. The result render surfaces a progress indicator to satisfy the 2s-progress NFR.
- **Status:** proposed

### S-06: Admin resort management

- **Outcome:** A signed-in admin can reach an admin-only view and create, edit, and deactivate resort entries; deactivated resorts vanish from browsing and new recommendations while existing visited-list references keep working; non-admins get access-denied.
- **Change ID:** admin-resort-management
- **PRD refs:** US-03, FR-010, FR-011, FR-012, FR-013
- **Prerequisites:** S-01, S-03
- **Parallel with:** S-02, S-04, S-05, S-07
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Must-have but off the north-star critical path (a seed populates resorts for the recommendation flow), so it's sequenced after the validation milestone and can run in parallel — the main lever against the `time` blocker. Validation rules (percentages sum to 100, non-negative integers, required fields) and deactivation-not-deletion (protects visited-list integrity) are the load-bearing details.
- **Status:** proposed

### S-07: Account deletion

- **Outcome:** A signed-in user can permanently delete their account; once confirmed, their profile and visited list are immediately removed and never reappear on any product surface.
- **Change ID:** account-deletion
- **PRD refs:** FR-004, FR-005
- **Prerequisites:** S-02, S-04
- **Parallel with:** S-05, S-06
- **Blockers:** —
- **Unknowns:** — (PRD Open Question 4 resolved 2026-06-16: deletion is immediate, no undo window)
- **Risk:** Satisfies the account-deletion NFR; FR-004/FR-005 define the cascaded data removed. Depends on both the profile (S-02) and visited (S-04) models existing so deletion can cascade across all the user's data. Privacy-completing slice; small and off the critical path, so it lands late without endangering the deadline.
- **Status:** proposed

## Backlog Handoff

| Roadmap ID | Change ID | Suggested issue title | Ready for `/10x-plan` | Notes |
|---|---|---|---|---|
| F-01 | persistence-migration-baseline | Wire schema migrations + persistence conventions (local + production DBs) | yes | Run `/10x-plan persistence-migration-baseline` |
| S-01 | account-authentication | Email/password sign-up, sign-in, sign-out with role model | no | Needs F-01 done |
| S-02 | preference-profile | Create & edit preference profile | no | Needs S-01 done |
| S-03 | resort-catalog-browse | Resort data model + browse list + detail view | no | Needs S-01 done; owns the CSV-fed seed (curated 40-resort Europe subset; idempotent, dev-on-startup / prod-once) |
| S-04 | mark-visited | Mark/unmark visited (in-place update, private) | no | Needs S-01, S-03 done |
| S-05 | three-resort-recommendation | Recommend three ranked resorts with rationale | no | Needs S-02, S-03, S-04 done |
| S-06 | admin-resort-management | Admin create/edit/deactivate resorts | no | Needs S-01, S-03 done; heaviest slice — if too broad, split admin create/edit vs deactivate (never by layer) |
| S-07 | account-deletion | Permanently delete account + data | no | Needs S-02, S-04 done |

## Open Roadmap Questions

1. **Which region and which 20–40 resorts seed the v1 dataset?** — Owner: user. **Resolved 2026-06-16:** region is Europe; the 20–40 resorts are randomly selected from a larger European set — the exact selection does not matter for the MVP.
2. **What is the source of truth for resort facts, and how is the seed produced (hand-typed, one-off scrape, or compiled)?** — Owner: user. **Resolved 2026-06-16, refined 2026-06-25:** resort facts are compiled into CSV (no scraping). v1 loads a curated 40-resort Europe subset programmatically — dev re-seeds the wiped in-memory DB on every startup; the persistent prod DB is seeded once via an idempotent empty-table guard. The loader mechanism and the CSV's shipped (classpath) location are S-03 plan-level decisions.
3. **Which language / locale ships in v1?** — Owner: user. **Resolved 2026-06-16:** v1 ships in English only.
4. **Account deletion: undo window before permanent, or immediate?** — Owner: user. **Resolved 2026-06-16:** deletion is immediate — no undo window.

## Parked

- **Booking / reservation integration** — Why parked: PRD §Non-Goals — NextSlope recommends, it does not transact.
- **Global resort coverage** — Why parked: §Non-Goals — v1 is a single region of 20–40 resorts.
- **Worldwide resort dataset (~500 resorts) + browse search/filter/pagination** — Why parked: v1 deliberately ships a curated 40-resort Europe subset to hold the "single region, 20–40" PRD scope (`prd.md` Vision + Non-Goals) and keep S-03 a flat list. The full worldwide dataset is ready behind the subset; expanding is a loader-filter change plus a new filtering/pagination slice.
- **Live weather / snow conditions** — Why parked: §Non-Goals — resort facts are static, admin-maintained.
- **Social features (sharing, comments, follows, leaderboards)** — Why parked: §Non-Goals.
- **User-generated reviews or ratings** — Why parked: §Non-Goals — preferences → facts → recommendation, not a review site.
- **Collaborative filtering / learned ranking** — Why parked: §Non-Goals — the recommender stays deterministic and explainable.
- **Native mobile app** — Why parked: §Non-Goals — responsive web only.
- **Offline / PWA mode** — Why parked: §Non-Goals — assumes the user is online.
- **Multi-language support** — Why parked: §Non-Goals — single locale for v1 (English; Q3 resolved).
- **Admin audit log / change history** — Why parked: §Non-Goals — single trusted admin in v1.
- **Email notifications beyond signup confirmation** — Why parked: §Non-Goals.

## Done

- **F-01: (foundation) schema migrations + persistence conventions wired and verified on the local and production databases** — Archived 2026-06-19 → `context/archive/2026-06-16-persistence-migration-baseline/`. Lesson: —.
- **S-01: A visitor can sign up with email + password, sign in, and sign out; gated routes are enforced against a real persisted user and role model, replacing the default-user scaffold.** — Archived 2026-06-21 → `context/archive/2026-06-19-account-authentication/`. Lesson: —.
