---
change_id: account-deletion
title: Account deletion (permanent, cascades profile + visited)
status: impl_reviewed
roadmap_id: S-07
created: 2026-07-02
updated: 2026-07-12
archived_at: null
---

## Notes

Roadmap slice S-07. A signed-in user can permanently delete their account; once confirmed, their
preference profile and visited list are immediately removed and never reappear on any product
surface. Non-admin, principal-scoped (no id in the path). Deletion is immediate — no undo window
(PRD Open Question 4, resolved 2026-06-16).

PRD refs: FR-004 (profile), FR-005 (visited), plus the "permanently delete account" NFR.
Prerequisites: S-02 (preference-profile) and S-04 (mark-visited) — both done.

Research completed 2026-07-02 → `research.md`.
Plan written 2026-07-12 → `plan.md` (+ `plan-brief.md`).
