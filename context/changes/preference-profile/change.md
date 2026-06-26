---
change_id: preference-profile
title: Create and edit a preference profile
status: implementing
created: 2026-06-21
updated: 2026-06-26
archived_at: null
---

## Notes

<!-- Free-form notes for this change: links, ad-hoc context, decisions that don't belong in research/frame/plan. -->

- 2026-06-26 — Phase 1 (Domain & Migration) reviewed: APPROVED, 0 critical / 0 warnings / 5 deferred observations (`reviews/impl-review-phase-1.md`). Status stays `implementing` — Phases 2–3 still pending.
- 2026-06-26 — Phase 2 (Service & Form) implemented test-first: `PreferenceProfileForm` (validated DTO + defaults factory), `PreferenceProfileService` (owner-scoped load/upsert, region normalization, `availableCountries`), `UnknownRegionCountryException`. 10 unit tests (form validation + Mockito service); full suite green. Phase 3 still pending.
- 2026-06-26 — Phase 2 reviewed: APPROVED, 0 critical / 0 warnings / 3 observations (`reviews/impl-review-phase-2.md`). Status stays `implementing` — Phase 3 still pending.
- 2026-06-26 — Phase 3 (Controller, View, Navigation & Gating) implemented test-first: `ProfileController` (GET/POST, principal-scoped), `profile/form.html`, navbar Profile link, post-signup redirect → `/profile`. Owner-isolation integration test + controller `@WebMvcTest` added; broad `@WebMvcTest` slices given a `PreferenceProfileService` mock. Full suite green. Post-manual-review UX fixes: (1) region "Any" and country checkboxes are mutually exclusive client-side (checking a country clears "Any region"); (2) successful save now redirects to `/resorts` with a "profileSaved" confirmation (was `/profile`) so new users have a path into the app; (3) navbar Profile styled as a button.
