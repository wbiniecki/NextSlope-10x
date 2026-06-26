# Preference Profile (S-02) — Plan Brief

> Full plan: `context/changes/preference-profile/plan.md`
> Research: `context/changes/preference-profile/research.md`

## What & Why

Let a signed-in user create and edit a one-row **preference profile** (experience level, preferred
difficulty band, region preference, novelty preference) at `/profile`, persisting across sessions.
It's the S-05 recommender's input source — getting its stored shape right now avoids a rework later.

## Starting Point

S-01 (auth + gating) and S-03 (resort catalog + base layout) are shipped. `/profile` is already gated
by the default security rule and locked in `PermitListLockTests`, but no profile entity, route, or
navigation to it exists. The navbar today has only the brand link and Sign out — there is no way to
reach a profile page. Flyway is at `V2`; `ddl-auto=validate` requires a `V3` for any new entity.

## Desired End State

A signed-in user reaches `/profile` via a new navbar link (and automatically right after signup), sees a
form pre-filled with defaults (or their saved values), edits any axis, saves, and the values persist.
The stored shape matches the S-05 contract: region as a set of country strings (empty = "any region"),
novelty as a 2-value enum, experience as an ordered enum, difficulty as a band enum whose canonical
easy/medium/hard triple S-05 scores against `Resort.getDifficultyMix()`.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Difficulty input | Preset bands (MOSTLY_EASY / BALANCED / MOSTLY_HARD) | User picks a band; truthful and simple. | Plan |
| Difficulty storage | Store band enum; derive the mix triple in code | Keeps the S-05 contract while storing what the user actually chose; deterministic. | Plan (deviates from research's 3-column sketch) |
| Experience levels | Beginner / Intermediate / Advanced | Minimal ordered set the recommender can map. | Plan |
| Region | Multi-select countries + "Any region" | Hard-filterable against `Resort.country`; empty set = no filter. | Plan |
| Region storage | `@ElementCollection` set of country strings (first in project) | Multi-valued region needs a collection table, comparable to `Resort.country`. | Plan |
| Empty-state | Pre-fill defaults, ready to save | Lowest-friction onboarding. | Plan |
| Navigation | Navbar "Profile" link + signup redirects to `/profile` | Gives an explicit, discoverable path to the page. | Plan |
| Package | New `com.nextslope.profile` (controller in `web`) | Clean domain separation per one-package-per-domain convention. | Plan |
| Sum-to-100 / validity | App-layer only (no DB CHECK) | Band-derived mixes are correct by construction; matches no-CHECK convention. | Research + Plan |

## Scope

**In scope:** profile entity + `V3` migration (incl. region collection table), repository, owner-scoped
service, validated form, `/profile` GET/POST controller, Thymeleaf form view, navbar link, post-signup
redirect, and the full test pyramid (DataJpaTest, Testcontainers, WebMvc, two-user isolation).

**Out of scope:** the recommender (S-05), role/admin gating (S-06), account deletion cascade (S-07),
`SecurityConfig` changes, DB CHECK constraints, legacy-template migration.

## Architecture / Approach

New `com.nextslope.profile` package holds enums, the `PreferenceProfile` entity (1:1 to `users` via
`UNIQUE(user_id)`; region set via `@ElementCollection`), repository, service, and form DTO; the
controller lives in `com.nextslope.web`. Create and edit are one upsert form. The controller resolves
the owner from the authenticated principal (no id in the path → no IDOR surface). Distinct country
options come from active resorts via the existing repository method.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Domain & migration | Enums, entity (+ region collection), repo, `V3` (two tables) | First FK + first `@ElementCollection` must validate on H2 *and* Postgres |
| 2. Service & form | Owner-scoped upsert/load-defaults, validated form, country options | Region normalization + out-of-vocabulary rejection logic |
| 3. Controller, view, navigation & gating | `/profile` route, form view, navbar link, signup→/profile, tests | Updating the existing signup redirect test; band/region form binding |

**Prerequisites:** S-01 done (auth), S-03 done (resort data for country options) — both complete.
**Estimated effort:** ~1–2 after-hours sessions across 3 phases (thin, well-precedented slice).

## Open Risks & Assumptions

- Band→mix triples and experience-level set are S-02 plan decisions; S-05 may re-tune scoring weights
  but should not need to change the *stored* model.
- Changing the post-signup redirect to `/profile` breaks `SignupIntegrationTests` line 43 — the plan
  updates it as part of Phase 3.
- `@ElementCollection` is a new project convention; the Testcontainers proof gates it on real Postgres.

## Success Criteria (Summary)

- A new user lands on a defaults-filled `/profile` after signup and can reach it anytime via the navbar.
- Saved preferences persist across sign-out / sign-in and can be edited.
- A user's profile is readable/writable only by that user (isolation verified); `./gradlew test` green.
