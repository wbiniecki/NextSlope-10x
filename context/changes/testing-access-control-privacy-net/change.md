---
change_id: testing-access-control-privacy-net
title: Access-control & privacy regression net (test-plan rollout Phase 1)
status: impl_reviewed
created: 2026-06-23
updated: 2026-06-24
archived_at: null
---

## Notes

**Closing-out work for S-01 / `account-authentication` (Linear 10X-6, Done).** This is not a roadmap slice of its own — it is the test-plan Phase 1 regression net that locks the auth/route-gating surface S-01 delivered, and seeds the reusable per-route gating + ownership/IDOR + admin-authz pattern (cookbook §6.4) that later slices extend. No separate Linear issue; tracked under S-01.

Open a change folder for rollout Phase 1 of context/foundation/test-plan.md: "Access-control & privacy regression net".
Risks covered: #4 (access-control regression — a new gated route ships unprotected or a permit-list change exposes a gated route to anonymous users) and #5 (privacy / IDOR — one user reads/edits another user's profile or visited list; admin sees private data; non-admin reaches the admin surface).
Test types planned: web-slice (@WebMvcTest + spring-security-test) and integration.
Risk response intent:
- #4: prove that for each new gated route, anonymous -> redirect to /login and authenticated-but-wrong-role -> denied; challenge the assumption that "authenticated == authorized" and that the generic /whatever gate test is sufficient.
- #5: prove that user B is blocked from user A's profile/visited resources, an admin has no surface to a user's private data, and a non-admin gets 403 on admin routes; challenge the assumption that "logged in == owns this resource".
This phase establishes the reusable per-route gating + ownership/IDOR + admin-authz test pattern (cookbook §6.4) that every later slice extends. It is implementable now against the existing auth surface (reference: src/test/java/com/nextslope/RouteGatingTests.java).
