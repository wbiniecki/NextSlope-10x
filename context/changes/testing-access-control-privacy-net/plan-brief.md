# Access-control & Privacy Regression Net (Test-Plan Phase 1) — Plan Brief

> Full plan: `context/changes/testing-access-control-privacy-net/plan.md`
> Research: `context/changes/testing-access-control-privacy-net/research.md`

## What & Why

Lock today's authentication surface against regression and ship the reusable security-test harness
(test-plan cookbook §6.4) that every later product slice extends. Covers Risk #4 (a permit-list edit
silently exposes a gated route) and seeds Risk #5 (privacy / IDOR + admin-authz). **Test-only — no
production code changes.**

## Starting Point

The auth model is authentication-only and binary: one permit-list + `anyRequest().authenticated()`
in `SecurityConfig`, no role enforcement, no admin routes, no user-owned resources. The only gated
route that exists today is the framework `/error`. `RouteGatingTests` probes a synthetic `/whatever`
path (impl-review finding **F13** — a proxy, not a real gated route), and there is no shared test
base or fixtures anywhere.

## Desired End State

A `support` test package (two-user + admin fixtures, integration base, assertion vocabulary) exists;
a permit-list lock fails CI on any future `permitAll()` widening; F13 is closed via a real gated
route (`/error`); CSRF-enforcement and the H2-console prod-absence contract are pinned; and the
Risk #5 vocabulary (anonymous→login / `USER`→403 / `ADMIN`→200 / wrong-owner→denied) is proven green
against a test-only demo route and documented as the executable §6.4 recipe.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Phase 1 scope | Lock current surface + ship reusable harness; defer real IDOR/403 to S-02/04/06 | The protected routes don't exist yet; only the gating surface + harness are buildable today | Research / Plan |
| Permit-list lock | Sample-path MockMvc assertions (+ curated must-stay-gated set) | Matches existing MockMvc idiom; cheap; catches a widened permitAll | Research / Plan |
| F13 fix | Use `/error` as the real gated route; keep `/whatever` as canary | `/error` is a genuinely gated route that exists today | Research / Plan |
| Test scaffolding | Introduce `src/test/java/com/nextslope/support/` | This shared harness *is* the durable Phase 1 deliverable | Research / Plan |
| Risk #5 vocabulary | Demonstrate green against a test-only role-gated route | A running template beats unexecuted prose for S-06 to copy | Plan |
| Admin principal | Persist ADMIN via repo (integration); `@WithMockUser(roles=ADMIN)` (web-slice) | Matches both existing test patterns; no env-var coupling | Plan |
| H2-console | Pin both halves: permit-all non-prod, absent in prod | Guards a real accidental prod-exposure path | Plan |
| CSRF | Add a minimal CSRF-enforced assertion | Cheaply catches a silent CSRF-disable regression | Plan |

## Scope

**In scope:** `support` test package; permit-list lock; `/error` real-gated-route assertion; CSRF
enforcement test; H2-console profile contract; role-gated demo-route vocabulary; two-user ownership
demonstration; cookbook §6.4 + AGENTS.md note.

**Out of scope:** any production code change; real per-route IDOR/admin-403 assertions (land with
S-02/S-04/S-06); SecurityFilterChain reflection; browser/e2e; AdminBootstrap test; admin UI/seed.

## Architecture / Approach

Foundation-first, three phases. Phase 1 builds the `support` harness (fixtures + integration base +
assertion helpers). Phase 2 is a self-contained web-slice locking the Risk #4 surface. Phase 3 uses
the harness to demonstrate the Risk #5 vocabulary against a test-scoped role-gated route and writes
the cookbook recipe. All assertions are MockMvc / `@SpringBootTest` integration — the correct layer
for a server-rendered Thymeleaf+HTMX app.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Test-support harness | `UserFixtures`, `TwoUserIntegrationTestBase`, `AccessControlAssertions` | New shared-base convention the repo lacked — document it |
| 2. Permit-list lock & gating net | Lock + `/error` real route + CSRF + H2 prod-absence pins | Prod-profile H2 check must not boot Neon/Flyway wiring |
| 3. IDOR/admin-authz seed + cookbook | Green role-gated demo + ownership demo + §6.4 recipe | Demo route is synthetic — must be clearly labeled a fixture |

**Prerequisites:** none beyond the current `main` (auth surface already shipped via S-01).
**Estimated effort:** ~1–2 sessions across 3 phases.

## Open Risks & Assumptions

- The prod-profile H2 absence check may need a bean-presence / `ApplicationContextRunner` approach
  rather than a full prod MockMvc boot, to avoid requiring Neon/`SPRING_FLYWAY_URL` prod secrets.
- `/error` under MockMvc won't cleanly return 200 for the authed case; assert "not redirected to
  `/login`" (reached past security) instead of a specific status.
- The real IDOR/admin-403 value only materializes when S-02/S-04/S-06 ship and extend this harness;
  Phase 1's payoff is the seam + caught regressions on today's surface.

## Success Criteria (Summary)

- `./gradlew test` green, including the new access-control regression net.
- A widened `permitAll()` (or a removed `@Profile("!prod")` H2 guard, or a disabled CSRF) is caught
  by a failing test.
- Cookbook §6.4 is a complete, executable recipe pointing at real reference tests that later slices
  extend with a single assertion row.
