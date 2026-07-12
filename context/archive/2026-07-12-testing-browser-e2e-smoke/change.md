---
change_id: testing-browser-e2e-smoke
title: Testing browser e2e smoke
status: archived
created: 2026-07-12
updated: 2026-07-12
archived_at: 2026-07-12T16:39:35Z
---

## Notes

<!-- Free-form notes for this change: links, ad-hoc context, decisions that don't belong in research/frame/plan. -->

**Test-plan rollout Phase 3 (browser smoke tier) — not a roadmap slice.** Like Phase 1
(`testing-access-control-privacy-net`), this is a test-plan phase tracked under the product
slices it exercises (S-01→S-05); no separate Linear issue.

### How to run the browser smoke suite

- `./gradlew e2eTest` — boots the app on a random port (in-memory H2 + Flyway +
  150-resort seed) and drives headless Chromium through the chained HTMX journey.
  Not wired into `check`/`build`; it runs only when invoked explicitly.
- **First run downloads Chromium** (~150 MB) to `~/.cache/ms-playwright` (Linux
  default; `~/Library/Caches/ms-playwright` on macOS) and is correspondingly
  slower; subsequent runs reuse the local download. To provision
  explicitly (what CI does): `./gradlew playwrightInstall`.

Framed 2026-07-02 — see `frame.md` in this folder (originally drafted under the working name
`testing-end-to-end-user-flows`, consolidated here 2026-07-12). Direction confirmed by the
developer: a **small browser-driven e2e smoke suite** scoped to the HTMX in-place swaps
`MockMvc` can't see. The frozen test-plan strategy was amended accordingly on 2026-07-02
(`context/foundation/test-plan.md` §1/§3/§4/§5/§6.6/§7/§8 — Playwright-Java 1.61.0 recorded
as the leading, still-open tool option).
