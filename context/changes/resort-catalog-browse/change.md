---
change_id: resort-catalog-browse
title: Resort catalog & browsing
status: implemented
created: 2026-06-21
updated: 2026-06-25
archived_at: null
---

## Notes

<!-- Free-form notes for this change: links, ad-hoc context, decisions that don't belong in research/frame/plan. -->

- Phase 3: added a "Browse resorts" link to the landing page's authenticated block
  (`index.html`) so the new `/resorts` catalog is reachable from the UI — without it
  the slice's "user can browse" outcome was only reachable by typing the URL. The
  post-login redirect (`SecurityConfig.defaultSuccessUrl("/")`) was deliberately left
  unchanged: the signed-in "home" destination is an S-05 (recommendation) concern and
  shouldn't be pinned to `/resorts` now.
