# Access-control & Privacy Regression Net (Test-Plan Rollout Phase 1) Implementation Plan

## Overview

Lock the authentication surface that exists today against regression, and ship the reusable
security-test harness (test-plan cookbook §6.4) that every later product slice extends. This is a
**test-only change**: it adds tests and a test-support package; it changes **no production code**.

It covers test-plan Risk #4 (a permit-list edit silently exposes a gated route / a new gated route
ships unprotected) and seeds Risk #5 (privacy / IDOR + admin-authz) by building the harness and a
*demonstrated* assertion vocabulary — the actual per-route IDOR and admin-403 assertions land with
the slices that introduce those routes (S-02 profile, S-04 visited, S-06 admin).

## Current State Analysis

The entire authorization model lives in `SecurityConfig.java` as two filter chains and is
**authentication-only and binary**:

- Main chain (`@Order(2)`): one permit-list — `/`, `/index`, `/login`, `/signup`,
  `/actuator/health`, `/css/**`, `/js/**`, `/webjars/**` — then `anyRequest().authenticated()`.
  No `hasRole`/`hasAuthority`, no method security, no per-route role matcher.
  (`src/main/java/com/nextslope/config/SecurityConfig.java:50-54`)
- H2-console chain (`@Order(1)`, `@Profile("!prod")`): `securityMatcher("/h2-console/**")`,
  `permitAll`, CSRF disabled, `frameOptions sameOrigin`; **absent entirely in prod**.
  (`SecurityConfig.java:22-31`)
- CSRF is **enabled** on the main chain; form login at `/login`, `defaultSuccessUrl("/", true)`.
  (`SecurityConfig.java:55-61`)

The route inventory is two controllers, **zero** gated *application* routes: `HomeController`
(`GET /`, `GET /index`) and `AuthController` (`GET /login`, `GET /signup`, `POST /signup`) are all
public. The only genuinely **gated** routes that exist today are framework routes not on the
permit-list — most usefully `/error` (Spring Boot `BasicErrorController`).

The role model exists but is never enforced by an authorization rule: `User.Role { USER, ADMIN }`
maps to `ROLE_USER`/`ROLE_ADMIN` in `AppUserDetailsService`, but no rule consults it. ADMIN is
created only by `AdminBootstrap` (env vars). **No user-owned resource exists** — no `user_id` FK,
no profile/visited entity. The resources Risk #5 protects do not exist yet.

Existing test building blocks:

- `RouteGatingTests` (`src/test/java/com/nextslope/RouteGatingTests.java`) — `@WebMvcTest` +
  `@Import({SecurityConfig.class, AppUserDetailsService.class})`, collaborators as `@MockitoBean`.
  Asserts anonymous `GET /whatever` → redirect `/login`, and `@WithMockUser` → 404. The 404-on-a-
  synthetic-path is **impl-review finding F13**: a proxy, not a real gated route.
- `AuthenticationIntegrationTests` — `@SpringBootTest @AutoConfigureMockMvc`, real
  `UserRepository`/`PasswordEncoder`, `@BeforeEach deleteAll()`, persists via `User.builder()`,
  drives `SecurityMockMvcRequestBuilders.formLogin()`. **The template for the two-user harness.**
- No shared test base / fixtures / `src/test/resources/` exist — every test inlines its setup.

## Desired End State

After this plan:

- A `com.nextslope.support` test package provides a two-user fixture (one USER, one ADMIN) and an
  integration base class; later slices reuse it instead of re-deriving security setup.
- A permit-list lock test fails CI if a future edit widens `permitAll()` to expose one of a curated
  set of high-value route samples. (Coverage is sample-based, not exhaustive — the chosen strategy
  avoids reflecting over `SecurityFilterChain`; a widened path outside the curated set is not caught
  until a slice adds its route-specific assertion.)
- F13 is closed: a *real* gated route (`/error`) replaces the synthetic proxy — anonymous → `/login`
  (real-path gating), and an authenticated request **reaches `BasicErrorController` past the security
  filter**. `ErrorMvcAutoConfiguration` is part of the Boot 4 `@WebMvcTest` slice (verified in
  `AutoConfigureWebMvc.imports`), so the controller is present and the handler is genuinely reached —
  assert this as a non-redirect to `/login` rather than pinning the exact error status (200 vs 500 is
  an impl detail of a direct `/error` hit; see Critical Implementation Details). The synthetic
  `/whatever` is kept only as an unknown-path canary.
- CSRF-enforcement on the main chain and the H2-console profile contract (open in non-prod, absent
  in prod) are both pinned.
- The Risk #5 enforcement vocabulary (anonymous→redirect / `USER`→403 / `ADMIN`→200) is proven
  green against a test-only role-gated demo route. The `wrong-owner→denied` outcome is **seeded and
  documented only** (a placeholder assertion + the two-user ownership shape) — a *real* wrong-owner
  denial cannot be proven yet because no owned resource exists; it remains required in S-02/S-04 when
  profile/visited routes land.
- `./gradlew test` is green; `test-plan.md` cookbook §6.4 is filled in. The §5 "access-control +
  IDOR suite" gate is satisfied for the surface that exists today (permit-list lock, gated-route,
  CSRF, H2-profile, role vocabulary); its per-route IDOR/wrong-owner obligations are explicitly
  deferred to S-02/S-04/S-06.

### Key Discoveries:

- `SecurityConfig.java:50-54` — the permit-list + `anyRequest().authenticated()` is the entire
  Risk #4 surface; the permit-list lock pins exactly this set.
- `/error` is a real, non-permit-listed (therefore gated) route that exists today — the vehicle to
  close F13 without inventing a production route.
- `SecurityConfig.java:22-31` — H2-console chain is `@Profile("!prod")`; pinning prod-absence
  guards a real accidental-exposure path.
- `AuthenticationIntegrationTests.java` persists `User.builder()` + `formLogin()` + shared
  `MockHttpSession` — the exact shape to lift into the two-user harness.
- Boot-4 slice import paths apply: `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`,
  `org.springframework.test.context.bean.override.mockito.MockitoBean`. (`build.gradle:36-52`)
- ADMIN principal: persist `User.builder().role(ADMIN)` directly in integration tests;
  `@WithMockUser(roles="ADMIN")` in web-slice tests. AdminBootstrap is not exercised here.

## What We're NOT Doing

- **No production code changes.** No new controllers, no `hasRole` rules, no permit-list edits in
  `SecurityConfig`. (The role-gated demo route in Phase 3 is a **test-scoped** controller only.)
- **No real per-route IDOR or admin-403 assertions** against profile/visited/admin endpoints — those
  routes don't exist; the assertions land with S-02/S-04/S-06, extending this harness.
- **No reflection over the SecurityFilterChain** — the lock uses MockMvc sample-path assertions.
- **No browser/e2e tooling** — server-rendered app; MockMvc is the correct layer.
- **No AdminBootstrap test, no Flyway admin seed, no admin UI.**
- **No exhaustive permutation testing** — representative sample paths per pattern, per §7 of the plan.

## Implementation Approach

Three phases, foundation-first. Phase 1 builds the reusable `support` package the other phases
consume. Phase 2 is a self-contained web-slice that locks the Risk #4 surface. Phase 3 uses the
Phase 1 harness to demonstrate the Risk #5 vocabulary against a test-only role-gated route and
writes the cookbook recipe. Each phase ends green under `./gradlew test`.

## Critical Implementation Details

- **`/error` status under MockMvc**: `ErrorMvcAutoConfiguration` is in the Boot 4 `@WebMvcTest`
  slice (`AutoConfigureWebMvc.imports`), so `BasicErrorController` **is** registered and reachable in
  the web-slice — no full-context boot needed. An anonymous request to `/error` returns the login
  redirect (302 → `/login`) before reaching the controller; the authenticated request passes security
  and hits `BasicErrorController`, which does **not** redirect to `/login`. Assert "did NOT redirect
  to `/login`" for the authed case rather than a specific status — a direct `GET /error` (no upstream
  forwarded error) has no `jakarta.servlet.error.status_code` attribute, so its code is an impl detail
  (typically 500); the point is "reached the real handler past the security filter," not the page's
  status code.
- **Prod-profile H2 assertion**: the H2 chain bean is `@Profile("!prod")`, so under
  `@ActiveProfiles("prod")` it is not registered and `/h2-console/**` falls through to the main
  chain's `authenticated()`. A prod-profile context may require prod datasource/Flyway env that the
  test must avoid booting — prefer the lightest context that still proves the chain is absent (see
  Phase 2 contract); do not pull in the Neon/`SPRING_FLYWAY_URL` prod wiring.
- **Demo route is test-scoped only**: register it inside the test's own `@Configuration`/
  `@TestConfiguration` or a `@WebMvcTest(controllers = …)` target so it never ships in `src/main`.
  Label it clearly as a pattern fixture.

## Phase 1: Reusable Test-Support Harness

### Overview

Create the `com.nextslope.support` test package: a two-user fixture, an integration base class, and
the reusable assertion-vocabulary helpers. This is the durable Phase 1 deliverable — the seam every
later slice plugs into.

### Changes Required:

#### 1. Two-user fixture factory

**File**: `src/test/java/com/nextslope/support/UserFixtures.java`

**Intent**: Provide canonical, distinct test users so ownership/IDOR tests always have two real
identities and one ADMIN, instead of each test inlining `User.builder()`. Centralizes emails,
passwords, and roles.

**Contract**: Static factory exposing at least: a builder/`User` for "user A" (role `USER`), "user
B" (role `USER`, distinct email), and an "admin" (role `ADMIN`), plus the known plaintext passwords
for `formLogin`. Mirrors the `User.builder().email(...).passwordHash(...).role(...)` shape used in
`AuthenticationIntegrationTests`. Password hashing is the caller's concern (inject `PasswordEncoder`)
so the factory stays free of Spring context.

#### 2. Integration base class

**File**: `src/test/java/com/nextslope/support/TwoUserIntegrationTestBase.java`

**Intent**: A `@SpringBootTest @AutoConfigureMockMvc` base that persists the two-user + admin set
before each test and cleans up after, so subclasses focus on assertions, not setup.

**Contract**: Abstract base with `@Autowired MockMvc`, `UserRepository`, `PasswordEncoder`;
`@BeforeEach` persists the `UserFixtures` set (hashing passwords) and `deleteAll()` for isolation;
helper(s) to log a given fixture user in via `SecurityMockMvcRequestBuilders.formLogin()` and return
the authenticated `MockHttpSession`. Follows `AuthenticationIntegrationTests`'s session pattern.

#### 3. Reusable assertion-vocabulary helpers

**File**: `src/test/java/com/nextslope/support/AccessControlAssertions.java`

**Intent**: Name the security outcomes once so later slices read as a vocabulary, not raw matchers.

**Contract**: Static helpers wrapping `ResultActions`/`MockMvc` expectations for: `anonymous →
redirect to /login`, `authenticated → not redirected to /login (reached past security)`,
`forbidden (403)`, and a placeholder for `wrong-owner → denied` (documented for slices to specialize
once an owned resource exists). Reuses `MockMvcResultMatchers` / `SecurityMockMvcResultMatchers`.

### Success Criteria:

#### Automated Verification:

- Support package compiles: `./gradlew compileTestJava`
- Full suite still green: `./gradlew test`

#### Manual Verification:

- `UserFixtures` users are distinct (emails/roles) and the admin carries `Role.ADMIN`.
- The base class isolates state between tests (no cross-test user bleed).
- The helper names read as the §6.4 vocabulary a later-slice author would reach for.

**Implementation Note**: After this phase and all automated verification passes, pause for manual
confirmation before proceeding.

---

## Phase 2: Permit-List Lock & Gating Regression Net (Risk #4)

### Overview

A web-slice test that pins the public surface, replaces the F13 `/whatever` proxy with a real gated
route (`/error`), and pins the CSRF and H2-console contracts. Self-contained; does not depend on
Phase 1.

### Changes Required:

#### 1. Permit-list lock + real gated-route test

**File**: `src/test/java/com/nextslope/PermitListLockTests.java` (web-slice; **replaces**
`RouteGatingTests` — carry its `/whatever` anonymous catch-all canary into this test, then delete
`RouteGatingTests` so there is no duplicate canary coverage)

**Intent**: Fail CI if a future `permitAll()` edit widens the public set, and prove a real gated
route is gated/reachable — closing F13.

**Contract**: `@WebMvcTest` + `@Import({SecurityConfig.class, AppUserDetailsService.class})`,
`UserRepository`/`UserRegistrationService` as `@MockitoBean` (matching `RouteGatingTests`). Assert:
- Each permit-listed pattern stays public via a representative path — `/`, `/index`, `/login`,
  `/signup`, `/actuator/health`, `/css/x`, `/js/x`, `/webjars/x` — anonymous request is NOT
  redirected to `/login`.
- A curated must-stay-gated set of high-value route samples — `/error` plus representative future
  prefixes `/profile`, `/visited`, `/recommend`, `/admin` — anonymous request redirects to `/login`.
  (These are samples, not exhaustive coverage; each future slice must add its own route-specific
  gating assertion as it introduces real routes. Keep the prefix list aligned with the roadmap.)
- `/error` as the **real gated route**: authenticated (`@WithMockUser`) request is NOT redirected to
  `/login` (reached past security — see Critical Implementation Details for the status caveat).
- An unknown path (`/whatever`) stays gated for anonymous (the catch-all canary), retained from
  `RouteGatingTests`.

#### 2. CSRF-enforcement assertion

**File**: same file or `src/test/java/com/nextslope/CsrfEnforcedTests.java`

**Intent**: Pin that CSRF protection stays on — a regression that disables it must fail.

**Contract**: A state-changing `POST` (e.g. `/logout`, or `/signup`) **without** a CSRF token is
forbidden (`status().isForbidden()`), and the same `POST` `.with(csrf())` is accepted (no 403).
Uses `SecurityMockMvcRequestPostProcessors.csrf()`.

#### 3. H2-console profile contract

**File**: `src/test/java/com/nextslope/H2ConsoleProfileTests.java`

**Intent**: Guard against the H2 console ever becoming a prod exposure.

**Contract**: Two assertions, profile-scoped:
- Under a non-prod profile, `/h2-console/**` is permit-all (anonymous reaches it; not redirected to
  `/login`).
- Under `@ActiveProfiles("prod")`, the H2 chain bean is absent so `/h2-console/...` falls through to
  `authenticated()` (anonymous → redirect `/login`). Use the lightest context that proves bean
  absence without booting prod Neon/Flyway wiring (see Critical Implementation Details); if a full
  prod-profile MockMvc context is too heavy, assert the chain bean's conditional registration
  directly via an `ApplicationContextRunner`/bean-presence check.

### Success Criteria:

#### Automated Verification:

- All Phase 2 web-slice tests pass: `./gradlew test --tests com.nextslope.PermitListLockTests --tests com.nextslope.CsrfEnforcedTests --tests com.nextslope.H2ConsoleProfileTests` (drop the CSRF `--tests` if CSRF assertions live inside `PermitListLockTests`)
- Full suite green: `./gradlew test`

#### Manual Verification:

- Temporarily adding a path to `permitAll()` (e.g. `/profile`) makes the lock test fail — the lock
  actually catches a widened permit-list.
- Removing `.with(csrf())` from a real POST flow is caught by the CSRF test.
- The H2 prod-absence assertion fails if the `@Profile("!prod")` guard were removed.

**Implementation Note**: After this phase and all automated verification passes, pause for manual
confirmation before proceeding.

---

## Phase 3: IDOR & Admin-Authz Pattern Seed + Cookbook (Risk #5)

### Overview

Use the Phase 1 harness to demonstrate the Risk #5 vocabulary green against a **test-only**
role-gated route, demonstrate the two-user ownership shape, and write the executable cookbook §6.4
recipe so S-02/S-04/S-06 extend it rather than re-deriving it.

### Changes Required:

#### 1. Role-gated demo route + full-vocabulary assertions

**File**: `src/test/java/com/nextslope/support/RoleGatingPatternTests.java` (+ a test-scoped
`@TestConfiguration`/controller)

**Intent**: Prove the anonymous→login / `USER`→403 / `ADMIN`→200 pattern actually runs, giving S-06
a green template to copy. The route is synthetic and clearly labeled a pattern fixture.

**Contract**: Import production `SecurityConfig` for the real anonymous-redirect / authenticated
flow, and add the role rule **test-locally via method security** — **not** by editing production
`SecurityConfig`. Concretely: a `@TestConfiguration` annotated `@EnableMethodSecurity` registers a
minimal test-scoped demo controller whose handler is annotated `@PreAuthorize("hasRole('ADMIN')")`.
Method security is required because production's `anyRequest().authenticated()` alone would let an
authenticated `USER` through (no `USER → 403`). Assert: anonymous → redirect `/login`;
`@WithMockUser(roles="USER")` → 403; `@WithMockUser(roles="ADMIN")` → 200. Uses
`AccessControlAssertions` from Phase 1.

**Note for S-06**: this fixture uses method-security (`@PreAuthorize`) to produce the role
vocabulary. If S-06 instead gates admin via URL authorization (`requestMatchers(...).hasRole("ADMIN")`
in production `SecurityConfig`), the assertion vocabulary is identical — only the enforcement seam
differs. Call out the chosen seam when S-06 lands.

#### 2. Two-user ownership demonstration

**File**: `src/test/java/com/nextslope/support/OwnershipPatternIntegrationTests.java` (extends
`TwoUserIntegrationTestBase`)

**Intent**: Demonstrate the two-distinct-persisted-user pattern an IDOR test will use, so S-02/S-04
add a single assertion against a real owned resource.

**Contract**: Extends the Phase 1 base; logs in as user A and user B via the base helper; asserts
each authenticates as a distinct principal (distinct usernames/sessions) and that the admin fixture
carries `ROLE_ADMIN`. Documents (in comments + cookbook) where a real "user B → A's resource →
denied" assertion will slot once an owned route exists. No real owned route is asserted (none
exists).

#### 3. Cookbook §6.4 + test-plan/AGENTS note

**File**: `context/foundation/test-plan.md` (§6.4), and a short note in `AGENTS.md` Testing section

**Intent**: Replace the §6.4 "TBD" with the real, executable recipe and reference tests; record the
new shared-test-support convention so the next contributor follows it intentionally.

**Contract**: §6.4 names the harness (`support/UserFixtures`, `TwoUserIntegrationTestBase`,
`AccessControlAssertions`), the reference tests (`PermitListLockTests`, `RoleGatingPatternTests`,
`OwnershipPatternIntegrationTests`), the run command, and the "extend, don't re-derive" instruction
for later slices. AGENTS.md Testing section gains one line pointing at the `support` package as the
canonical security-test scaffolding.

### Success Criteria:

#### Automated Verification:

- Pattern tests pass: `./gradlew test --tests "com.nextslope.support.*"`
- Full suite green: `./gradlew test`

#### Manual Verification:

- The demo-route test shows all three outcomes (anonymous/USER/ADMIN) green — an S-06 author can
  copy it verbatim and swap in the real admin route.
- Cookbook §6.4 reads as a complete recipe (no "TBD"), pointing at real reference tests.
- A reader of `test-plan.md` §5 can see the "access-control + IDOR suite" gate is satisfied for
  what exists today, with the deferred per-route assertions clearly attributed to S-02/S-04/S-06.

**Implementation Note**: After this phase and all automated verification passes, pause for manual
confirmation.

---

## Testing Strategy

### Unit Tests:

- None new (no production logic added). `UserFixtures` is exercised indirectly by the integration
  tests that consume it.

### Integration Tests:

- `TwoUserIntegrationTestBase` + `OwnershipPatternIntegrationTests` — two distinct persisted users +
  admin, real `UserRepository`/`PasswordEncoder`, `formLogin` sessions.

### Web-slice Tests:

- `PermitListLockTests` (permit-list lock + `/error` real gated route + `/whatever` canary), CSRF
  enforcement, `H2ConsoleProfileTests`, `RoleGatingPatternTests` (role-gated demo route).

### Manual Testing Steps:

1. Temporarily add `/profile` to `permitAll()` in `SecurityConfig` → `PermitListLockTests` fails.
   Revert.
2. Temporarily remove the `@Profile("!prod")` guard on the H2 chain → `H2ConsoleProfileTests`
   prod-absence assertion fails. Revert.
3. Run `./gradlew test` → entire suite green, including the new access-control net.

## Performance Considerations

Negligible — a handful of MockMvc/web-slice tests plus a couple of `@SpringBootTest` integration
tests. The integration base reuses the existing `@SpringBootTest` context shape already present in
`AuthenticationIntegrationTests`, so no new heavy context type is introduced (the prod-profile H2
check uses the lightest viable context — bean-presence or `ApplicationContextRunner`, not a full
prod boot).

## Migration Notes

None — test-only change, no schema or data migration. `RouteGatingTests` is superseded by
`PermitListLockTests`: fold its `/whatever` anonymous catch-all canary assertion into
`PermitListLockTests`, then **delete `RouteGatingTests`** so the F13 proxy is not left as duplicate
or sole gating coverage.

## References

- Research: `context/changes/testing-access-control-privacy-net/research.md`
- Test plan / risk source: `context/foundation/test-plan.md` (Risks #4/#5, cookbook §6.4, gate §5)
- Reference tests: `src/test/java/com/nextslope/RouteGatingTests.java`,
  `src/test/java/com/nextslope/AuthenticationIntegrationTests.java`
- Security surface: `src/main/java/com/nextslope/config/SecurityConfig.java:22-62`
- Role model: `src/main/java/com/nextslope/user/User.java:54-57`,
  `src/main/java/com/nextslope/user/AppUserDetailsService.java:17-27`
- F13 origin: `context/archive/2026-06-19-account-authentication/reviews/impl-review.md:42`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Reusable Test-Support Harness

#### Automated

- [x] 1.1 Support package compiles: `./gradlew compileTestJava`
- [x] 1.2 Full suite still green: `./gradlew test`

#### Manual

- [x] 1.3 `UserFixtures` users are distinct and admin carries `Role.ADMIN`
- [x] 1.4 Base class isolates state between tests
- [x] 1.5 Helper names read as the §6.4 vocabulary

### Phase 2: Permit-List Lock & Gating Regression Net

#### Automated

- [ ] 2.1 All Phase 2 web-slice tests pass: `./gradlew test --tests com.nextslope.PermitListLockTests --tests com.nextslope.CsrfEnforcedTests --tests com.nextslope.H2ConsoleProfileTests`
- [ ] 2.2 Full suite green: `./gradlew test`

#### Manual

- [ ] 2.3 Adding a path to `permitAll()` makes the lock test fail
- [ ] 2.4 Removing `.with(csrf())` from a real POST flow is caught
- [ ] 2.5 H2 prod-absence assertion fails if the `@Profile("!prod")` guard is removed

### Phase 3: IDOR & Admin-Authz Pattern Seed + Cookbook

#### Automated

- [ ] 3.1 Pattern tests pass: `./gradlew test --tests "com.nextslope.support.*"`
- [ ] 3.2 Full suite green: `./gradlew test`

#### Manual

- [ ] 3.3 Demo-route test shows anonymous/USER/ADMIN outcomes green
- [ ] 3.4 Cookbook §6.4 reads as a complete recipe (no "TBD")
- [ ] 3.5 `test-plan.md` §5 access-control gate satisfied for the surface that exists today; per-route IDOR/wrong-owner obligations documented as deferred to S-02/S-04/S-06
