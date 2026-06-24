---
date: 2026-06-23T09:38:00+02:00
researcher: binieckw
git_commit: 890a6b37f89f38b7ca42a4573da9df325c2618cf
branch: main
repository: wbiniecki/NextSlope-10x
topic: "Access-control & privacy regression net (test-plan rollout Phase 1) — risks #4 (access-control regression) and #5 (privacy/IDOR)"
tags: [research, codebase, security, access-control, idor, testing, route-gating, spring-security]
status: complete
last_updated: 2026-06-23
last_updated_by: binieckw
---

# Research: Access-control & privacy regression net (Phase 1, risks #4 & #5)

**Date**: 2026-06-23T09:38:00+02:00
**Researcher**: binieckw
**Git Commit**: 890a6b37f89f38b7ca42a4573da9df325c2618cf
**Branch**: main
**Repository**: wbiniecki/NextSlope-10x

## Research Question

Ground rollout Phase 1 of `context/foundation/test-plan.md` ("Access-control & privacy
regression net") against the live codebase. The phase covers Risk #4 (a new gated route ships
unprotected, or a permit-list change silently exposes a gated route to anonymous users) and
Risk #5 (privacy / IDOR — one user reads/edits another user's profile or visited list; an admin
sees private data; a non-admin reaches the admin surface). It is meant to establish the reusable
per-route gating + ownership/IDOR + admin-authz test pattern (cookbook §6.4) that every later
slice extends, reportedly "implementable now against the existing auth surface."

## Summary

The auth surface that exists today is **authentication-only and binary**: every request is
either on a small static permit-list or it must be `authenticated()`. There is **no route-level
role enforcement, no `@PreAuthorize`/method security, no admin routes, and no user-owned domain
resource** anywhere in the codebase. The only persisted role distinction (`USER`/`ADMIN`) is
loaded into `ROLE_*` authorities by `AppUserDetailsService` but is **never consulted by an
authorization rule**.

This produces the single most important finding for planning, and it directly contradicts part
of the change brief:

- **Risk #4 (gating / permit-list regression) is genuinely implementable now** — and is where
  Phase 1 should invest. The valuable, buildable work is (a) replacing the `/whatever` proxy
  test with assertions against the *real* permit-list and at least one *real* gated route, and
  (b) a **permit-list lock**: a regression test that pins exactly which paths are public so any
  future `permitAll` edit that exposes a gated route fails CI. The existing `RouteGatingTests`
  only probes a synthetic `/whatever` path and asserts a 404 for the authenticated case — a known
  gap (impl-review finding **F13**).
- **Risk #5 (IDOR + admin-authz) is NOT fully implementable now** — the resources and routes it
  protects do not exist yet. Ownership/IDOR needs the preference-profile (S-02) and visited-list
  (S-04) resources; admin-authz (non-admin → 403) needs the admin surface (S-06) and a `hasRole`
  rule. None of these have controllers, entities, or routes today. What Phase 1 *can* do for
  Risk #5 is **seed the reusable harness** (two distinct persisted users, role-bearing principals,
  ownership-assertion helpers) so each later slice extends it rather than reinventing it.

Per `test-plan.md` §1 principle #3 ("If the plan and research disagree about where the failure
lives, research is the ground truth") and §3 ("if a product slice ships its own guardrail tests
via `/10x-implement` first, the corresponding phase narrows to the gaps that remain"), Phase 1
should be **scoped to what is real today**: lock + harden the current gating surface, and ship
the cookbook §6.4 harness as the seam later slices plug their real IDOR/admin assertions into.
The decision to confirm with the user is whether Phase 1 = "lock current surface + harness only"
(recommended) vs. trying to author route assertions against not-yet-built endpoints (not
possible without the slices).

## Detailed Findings

### Security configuration & route-gating surface

The entire authorization model lives in one file, `SecurityConfig.java`, as two filter chains.

- **Main chain** (`@Order(2)`) — the only authorization split is `permitAll()` vs
  `authenticated()`; there is **no** `hasRole`/`hasAuthority`/`hasAnyRole` and **no** per-route
  role matcher. (`src/main/java/com/nextslope/config/SecurityConfig.java:44-62`)
  - Permit-list (public): `/`, `/index`, `/login`, `/signup`, `/actuator/health`, `/css/**`,
    `/js/**`, `/webjars/**`; everything else → `anyRequest().authenticated()`.
    (`SecurityConfig.java:50-54`)
  - Form login: `loginPage("/login")`, `defaultSuccessUrl("/", true)`; logout →
    `/?logout`; CSRF **enabled** (not disabled). (`SecurityConfig.java:55-61`)
- **H2-console chain** (`@Order(1)`, `@Profile("!prod")`) — `securityMatcher("/h2-console/**")`,
  `permitAll`, CSRF disabled, `frameOptions sameOrigin`; absent entirely in prod.
  (`SecurityConfig.java:22-31`)
- **No method-level security** anywhere: zero `@PreAuthorize`/`@PostAuthorize`/`@Secured`/
  `@RolesAllowed`/`@EnableMethodSecurity` in `src/main`.

### The complete route inventory (what actually exists to test)

Exactly **two** controllers, **0** `@RestController`. Every application route today is public:

- `HomeController` — `GET /`, `GET /index` → `index` (public). (`src/main/java/com/nextslope/web/HomeController.java:9-12`)
- `AuthController` — `GET /login`, `GET /signup`, `POST /signup` (all public; `POST /signup`
  auto-logs-in on success). (`src/main/java/com/nextslope/web/AuthController.java:34-84`)
- Framework routes (no controller, but real HTTP surface): `POST /login` and `POST /logout`
  (Spring Security filters), `GET /actuator/health` (public), `/h2-console/**` (non-prod),
  static `/css/**` `/js/**` `/webjars/**`, and `/error` (Spring Boot `BasicErrorController` —
  **gated**, since it is not on the permit-list).

**Implication:** there is **no real, authenticated-only application endpoint today** to assert a
"200 for authed user" against. `RouteGatingTests` works around this by hitting `/whatever`
(non-existent) and asserting 404 for the authed case — a proxy, not a real gated route. The
future gated routes named in the brief (profile, resort, visited, recommend, admin) **have no
handlers yet** (roadmap S-02–S-06 are `proposed`).

### Role / identity model & ownership determination

- **One JPA entity only**: `User` (`@Table(name="users")`), with `id` (identity PK), unique
  `email` (doubles as the login username — no separate username), `passwordHash` (BCrypt),
  `role`, and `@CreationTimestamp`/`@UpdateTimestamp` audit columns.
  (`src/main/java/com/nextslope/user/User.java:32-52`)
- **Role is a single nested enum** on the user row — exactly one role per user, stored as a
  string: `USER`, `ADMIN`. (`src/main/java/com/nextslope/user/User.java:54-57`)
- **Authority mapping**: `AppUserDetailsService` builds `UserDetails` with
  `.roles(user.getRole().name())`, and Spring's builder prefixes `ROLE_`, yielding `ROLE_USER`
  / `ROLE_ADMIN` (asserted in `AppUserDetailsServiceTests`). The principal username is the
  normalized (trim+lowercase) email. (`src/main/java/com/nextslope/user/AppUserDetailsService.java:17-27`,
  `src/main/java/com/nextslope/user/EmailNormalizer.java:8-13`)
- **Registration always assigns `USER`** — `RegistrationForm` has no role field; signup can
  never create an ADMIN. (`src/main/java/com/nextslope/user/UserRegistrationService.java:22-26`)
- **ADMIN is created only by `AdminBootstrap`** (an `ApplicationRunner` reading `ADMIN_EMAIL`/
  `ADMIN_PASSWORD` env vars; create-if-missing, no auto-promotion, no-op when unset). There is
  **no Flyway seed** and no admin UI. (`src/main/java/com/nextslope/config/AdminBootstrap.java:42-61`)
- **No ownership anywhere**: no `user_id`/owner FK, no `@ManyToOne User`, no profile/visited/
  resort entity. The only repository is `UserRepository` (`findByEmail` + `JpaRepository`
  defaults). (`src/main/java/com/nextslope/user/UserRepository.java:7-10`) The resources Risk #5
  protects **do not exist yet**; they arrive with S-02 (profile) and S-04 (visited).
- **Schema**: single migration `V1__create_users.sql`, `role VARCHAR(32) NOT NULL`, **no seed
  inserts**. (`src/main/resources/db/migration/V1__create_users.sql:1-9`)

### Existing test patterns & wiring (what to extend)

Ten test classes; the security-relevant building blocks:

- **Web-slice gating** — `RouteGatingTests` (`@WebMvcTest` + `@Import({SecurityConfig.class,
  AppUserDetailsService.class})`, `UserRepository`/`UserRegistrationService` as `@MockitoBean`):
  anonymous `GET /whatever` → `is3xxRedirection()` + `redirectedUrl("/login")`; `@WithMockUser`
  → 404 (past security). This is the template to generalize. (`src/test/java/com/nextslope/RouteGatingTests.java:1-47`)
- **Web-slice controller** — `SignupWebMvcTests` (`@WebMvcTest(controllers=AuthController.class)`
  + `@Import(SecurityConfig.class)`; collaborators mocked; `POST` uses `.with(csrf())`).
  (`src/test/java/com/nextslope/SignupWebMvcTests.java`)
- **Full-stack auth integration** — `AuthenticationIntegrationTests` (`@SpringBootTest` +
  `@AutoConfigureMockMvc`, real `UserRepository`/`PasswordEncoder`, `@BeforeEach deleteAll()`):
  persists users via `User.builder()`, drives `SecurityMockMvcRequestBuilders.formLogin()`,
  shares a `MockHttpSession`, asserts `authenticated()`/`unauthenticated()`. **This is the model
  for the two-user IDOR harness.** (`src/test/java/com/nextslope/AuthenticationIntegrationTests.java`)
- **Prod-engine** — `UserRepositoryPostgresTests` (`@SpringBootTest @Testcontainers`,
  `postgres:16-alpine`, `@ServiceConnection`). (`src/test/java/com/nextslope/user/UserRepositoryPostgresTests.java:1-44`)
- **Role authorities** are asserted **only** at the service layer (`AppUserDetailsServiceTests`
  → `ROLE_USER`/`ROLE_ADMIN`); **no HTTP-level role-denial (403) test exists**.
- **spring-security-test gaps for this phase**: no `@WithMockUser(roles="ADMIN")`, no
  `@WithUserDetails`, no `user()` post-processor, no two-distinct-user session test, no `403`
  assertion (only redirect-to-login and 404), and **no shared test base / `src/test/resources/`
  / fixtures** — every test inlines `User.builder()` and its own `deleteAll()`.
- **Stack**: `build.gradle` pulls `spring-boot-starter-security-test` and the Testcontainers
  trio via the Spring Boot 4.0.6 BOM; AssertJ/Mockito are transitive. Boot-4 slice import paths
  apply (`org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`,
  `org.springframework.test.context.bean.override.mockito.MockitoBean`). (`build.gradle:36-52`)

## Code References

- `src/main/java/com/nextslope/config/SecurityConfig.java:50-54` — the permit-list + `anyRequest().authenticated()` (the Risk #4 surface; the thing a permit-list lock must pin).
- `src/main/java/com/nextslope/config/SecurityConfig.java:22-31` — H2-console chain (non-prod only); a regression test must not accidentally treat it as a prod surface.
- `src/main/java/com/nextslope/web/HomeController.java:9-12`, `src/main/java/com/nextslope/web/AuthController.java:34-84` — the complete (all-public) route inventory; no gated application route exists yet.
- `src/main/java/com/nextslope/user/AppUserDetailsService.java:17-27` — role → `ROLE_*` authority mapping; principal = normalized email.
- `src/main/java/com/nextslope/user/User.java:54-57` — `Role { USER, ADMIN }`.
- `src/main/java/com/nextslope/config/AdminBootstrap.java:42-61` — the only path to an ADMIN account (env-var bootstrap; for an integration test, persist an ADMIN via the repository directly).
- `src/test/java/com/nextslope/RouteGatingTests.java:34-46` — the `/whatever` proxy pattern to generalize/replace.
- `src/test/java/com/nextslope/AuthenticationIntegrationTests.java` — two-phase real-auth template to grow into the two-user IDOR harness.
- `src/main/resources/db/migration/V1__create_users.sql:1-9` — users table; no seed.
- `build.gradle:36-52` — test dependencies + `useJUnitPlatform()`.

## Architecture Insights

- **Authentication ≠ authorization is real here, and deliberate.** S-01 modeled the role but
  deferred *enforcement* to S-06 (roadmap line 91; archived `plan.md:68-69`). So "authenticated
  == authorized" is not just an assumption to challenge in tests — it is **literally the current
  production behavior**: an authenticated `USER` can reach any non-permit-listed route. There are
  simply no admin/owned routes for that to be dangerous on yet.
- **Risk #4 has two distinct failure modes**, and the cheap test for each differs: (a) a *new*
  gated route ships *without* a handler-or-rule that keeps it gated — caught by a per-route
  "anonymous → /login" assertion authored alongside the slice; (b) a permit-list *edit*
  accidentally widens `permitAll` — caught by a **permit-list lock** that asserts the exact
  public set. Only (b) is fully buildable today; (a) needs real routes.
- **The §6.4 cookbook pattern is the actual deliverable.** Because the protected resources don't
  exist yet, the durable value Phase 1 can ship now is the *harness*: a two-user fixture
  (`User.builder()` ×2 with distinct ids/emails, one `ADMIN`), helpers to log in as each, a
  reusable "anonymous → redirect / wrong-owner → denied / non-admin → 403" assertion vocabulary,
  and the documented recipe. Later slices then add one row each rather than re-deriving security
  testing.
- **No shared test scaffolding exists** — introducing a small test-support package (fixtures +
  a base for the two-user integration tests) is itself a Phase 1 decision, consistent with the
  "reusable pattern" goal.

## Historical Context (from prior changes)

- `context/archive/2026-06-19-account-authentication/plan.md:68-69` & `:366-369` — S-01 loads the
  role model but **does not enforce per-route roles**; admin enforcement/UI is explicitly S-06;
  no auto-promotion of an existing `USER` to ADMIN.
- `context/archive/2026-06-19-account-authentication/reviews/impl-review.md:42` — open finding
  **F13**: `RouteGatingTests` asserts 404 for the authed user on `/whatever` rather than 200 on a
  real protected endpoint; flagged as a Phase 1 test-suite gap. (This is the concrete thing
  Phase 1 should fix once a real gated route exists, or mitigate now via the permit-list lock.)
- `context/archive/2026-06-19-account-authentication/plan.md:343-369` — `AdminBootstrap` design
  (env-var, create-if-missing, idempotent); the canonical way to obtain an ADMIN principal.
- `context/archive/2026-06-16-persistence-migration-baseline/plan.md:25` — F-01 mandate: "the
  users schema must support per-user ownership later; no cross-user leakage is introduced here"
  — i.e. ownership columns are expected to arrive with S-02/S-04, not before.
- `context/changes/preference-profile/change.md`, `context/changes/resort-catalog-browse/change.md`
  — stubs only; no ownership/IDOR implementation decisions yet.
- `context/changes/deployment/deployment-plan.md:186-205` — pre-S-01 `SecurityConfig` baseline
  permit-list (health + static + landing) so health checks don't 302; context for why those
  entries are public.

### PRD requirements the later per-route assertions must eventually prove

- `context/foundation/prd.md:55` & `:142` — profile + visited-list are visible **only to that
  user**; **admins cannot see them through any product surface** (privacy guardrail / NFR).
- `context/foundation/prd.md:83` — visited list is per-user; no other user, **including admins**,
  can see it (US-02 AC).
- `context/foundation/prd.md:96` — a non-admin reaching the admin view gets **access-denied, not
  the form** (US-03 AC) → the 403 assertion target, once S-06 ships the route.
- `context/foundation/prd.md:156-169` — Access Control section: two roles, admin is a superset,
  admin assignment is out-of-band (not self-service); unauthenticated → redirected to sign-in.

## Related Research

- None. `context/archive/2026-06-19-account-authentication/` has no `research.md`
  (`frame.md:119`: "Related research: none"). This is the first research artifact in the
  testing-rollout line.

## Open Questions

1. **Phase 1 scope decision (for `/10x-plan`):** Given no admin routes and no user-owned
   resources exist yet, should Phase 1 = **(A)** lock + harden the *current* surface
   (permit-list lock; replace the `/whatever` proxy with real permit-list + at least one
   real-gated-route assertion) **plus** ship the reusable §6.4 harness/fixtures — and defer the
   actual per-route IDOR (S-02/S-04) and admin-403 (S-06) assertions to those slices, which
   extend the harness? (Recommended, and consistent with `test-plan.md` §3.) Or **(B)** attempt
   broader coverage now (not possible without the slices)?
2. **Permit-list lock fidelity:** assert the exact public set by enumerating representative
   sample paths per pattern (`/css/x`, `/js/x`, `/webjars/x`) and a curated "must-stay-gated"
   list (e.g. `/error`, a representative future route prefix), versus reflecting over the
   `SecurityFilterChain`? The sample-path approach matches the existing MockMvc idiom and is
   cheaper; confirm that's acceptable.
3. **Test-support package:** is introducing `src/test/java/com/nextslope/support/` (two-user
   fixtures + an integration base class) in-scope for Phase 1, or should the harness stay inline
   to match the current "no shared base" convention? (The "reusable pattern" goal argues for the
   package.)
4. **H2-console chain:** should a regression test pin that `/h2-console/**` is permit-all only
   under non-prod profiles (and absent in prod), to prevent it ever becoming a prod exposure?
