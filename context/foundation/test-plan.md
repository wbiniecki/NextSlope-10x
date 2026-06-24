# Test Plan

> Phased test rollout for this project. Strategy is frozen at the top
> (§1–§5); cookbook patterns at the bottom (§6) fill in as phases ship.
> Read before writing any new test.
>
> Refresh: re-run `/10x-test-plan --refresh` when stale (see §8).
>
> Last updated: 2026-06-22 (Phase 1 change opened)

## 1. Strategy

Tests follow three non-negotiable principles for this project:

1. **Cost × signal.** The cheapest test that gives a real signal for the
   risk wins. This is a server-rendered Thymeleaf + HTMX app with no SPA
   tier, so user flows are exercised with Spring `MockMvc`/integration tests
   — do not reach for a browser/e2e tool when a `MockMvc` request→response
   assertion already catches the regression.
2. **User concerns are first-class evidence.** Risks anchored in "the
   developer is worried about X, and the failure would surface somewhere in
   <area>" carry the same weight as PRD lines. The recommender risks here
   come straight from the Phase 2 interview.
3. **Risks are scenarios, not code locations.** This plan documents *what
   could fail* and *why we believe it's likely* — drawn from the PRD,
   interview, and codebase *signal* (churn, structure, test base). It does
   NOT claim to know which line owns the failure. That knowledge is produced
   by `/10x-research` during each rollout phase. If the plan and research
   disagree about where the failure lives, research is the ground truth.

Hot-spot scope used for likelihood weighting: `src/main` + `src/test` under
`com/nextslope/*`, excluding build output. Note: 30-day churn is dominated
entirely by the just-shipped S-01 auth work (now well-tested); it is **not**
used as likelihood evidence for the recommender risks, whose slices are not
yet built.

## 2. Risk Map

The top failure scenarios this project must protect against, ordered by
risk = impact × likelihood. Risks are failure scenarios in user / business
terms, not test names. The Source column cites the *evidence that surfaced
this risk* — never a specific file as "where the failure lives" (that is
research's job, see §1 principle #3).

| # | Risk (failure scenario) | Impact | Likelihood | Source (evidence — not anchor) |
|---|---|---|---|---|
| 1 | **Rationale lies** — the "why this matched you" line doesn't reflect the actual ranking, or cites a preference axis the user never set | High | High | PRD Guardrails (truthful rationale), FR-009, US-01 AC; interview Q1 |
| 2 | **Incomplete matching** — the recommender ignores one or more preference axes the user set (region, novelty, difficulty mix, experience) | High | High | PRD Business Logic (two-stage, all axes), FR-008; interview Q1, Q3 |
| 3 | **Visited / new-only guardrail broken** — a `new-only` user receives a resort they marked visited in their top three | High | Medium | PRD Guardrails (hard filter), US-01 AC, FR-005/FR-008 |
| 4 | **Access-control regression** — a new gated route (profile, resort, visited, recommend, admin) ships unprotected, or a permit-list change silently exposes a gated route to anonymous users | High | Medium | PRD Access Control; interview Q1; existing `RouteGatingTests` precedent |
| 5 | **Privacy / IDOR** — one user reads or edits another user's profile or visited list; an admin sees a user's private data; a non-admin reaches the admin surface *(abuse lens)* | High | Medium | PRD Guardrails (privacy), US-02 AC, US-03 AC, NFR (profile/visited privacy) |

**Impact × Likelihood rubric.** High/Medium/Low only — the goal is a
defensible order, not false precision. The recommender rows (1–2) are
High × High; they are the product's reason to exist and the developer named
them as top fears. The guardrail/security rows (3–5) are High impact but
Medium likelihood: the logic does not exist yet, so likelihood rests on PRD
intent and the general ease of regressing a hard filter or an authz check,
not on observed churn.

**Abuse / security lens.** The product has authentication and accepts user
input (profile, admin resort form), so Risk #5 is the mandatory abuse row:
it checks *ownership*, not just *authentication* — "logged in" must never
imply "may see this resource." Server-side validation parity for the admin
form lives inside the S-06 slice's own tests (see §7), not a dedicated phase.

### Risk Response Guidance

| Risk | What would prove protection | Must challenge | Context `/10x-research` must ground | Likely cheapest layer | Anti-pattern to avoid |
|---|---|---|---|---|---|
| #1 | Rationale names a preference axis the user actually set **and** corresponds to the matched resort's real scoring reasons | "Rationale text exists" ≠ "rationale is true" | Where the rationale string is produced vs. where ranking is decided; the user-input preferences that form the oracle | Unit + integration on recommend output | **Oracle problem** — asserting the rationale equals what the rationale generator emitted (tautology); derive the expected axis from the user's input, not from the generator |
| #2 | Changing one axis in isolation (region / novelty / difficulty / experience) changes the candidate set or ordering | "Happy-path recommend works" ⇒ all four axes are wired into scoring | The hard-filter vs. weighted-score split; which axes feed which stage | Unit on scorer/filter + differential integration | Asserting exact score numbers copied from the scorer; over-mocking so an axis never actually executes |
| #3 | A `new-only` user with a visited resort never sees it in the result; a `revisit-okay` user still can | "revisit-okay path works" ⇒ new-only filter works | How the visited list is joined to the candidate set; novelty-preference branch | Unit on the hard filter + integration | Testing only the revisit-okay path; ignoring the empty/all-visited edge |
| #4 | For each **new** gated route: anonymous → redirect to `/login`; authenticated-but-wrong-role → denied | "authenticated" ⇒ "authorized"; "the generic gate test covers it" | The actual route list per slice and the security permit-list entries | `@WebMvcTest` per slice (pattern already exists) | Testing only the generic `/whatever` route and never the real new routes |
| #5 | User B is blocked from A's profile/visited resources; admin has no surface to A's private data; non-admin → 403 on admin routes | "logged in" ⇒ "owns this resource" | The ownership/identity check on each user-scoped read+write; admin route guard | Integration with two distinct users + role checks | Own-data happy path only; over-mocking the security layer so real authz never runs |

## 3. Phased Rollout

Each row is a discrete rollout phase that will open its own change folder
via `/10x-new`. Status moves left-to-right through the values below; the
orchestrator updates Status as artifacts appear on disk.

| # | Phase name | Goal (one line) | Risks covered | Test types | Status | Change folder |
|---|---|---|---|---|---|---|
| 1 | Access-control & privacy regression net | Lock the current auth surface and ship a reusable per-route gating + ownership/IDOR + admin-authz test pattern every later slice extends | #4, #5 | web-slice + integration | change opened | context/changes/testing-access-control-privacy-net/ |
| 2 | Recommender correctness suite | Prove all-axes matching, the visited/new-only hard filter, and a truthful rationale for the north-star recommendation flow (gated on S-05 shipping) | #1, #2, #3 | unit + integration | not started | — |
| 3 | End-to-end user-flow coverage | Walk the real journeys (signup → profile → browse → mark-visited → recommend; admin-create → appears in browse) with `MockMvc`/integration, no browser (gated on S-02/03/04 shipping) | #1–#5 (flow-level) | integration | not started | — |

**Status vocabulary** (fixed — parser literals): `not started` →
`change opened` → `researched` → `planned` → `implementing` → `complete`.

**Sequencing reality.** Only Phase 1 is implementable today — it covers the
sole fully-built surface (auth/route-gating) and seeds the security test
cookbook before the route count grows. Phase 2 depends on the recommender
slice (S-05) existing; Phase 3 depends on the profile/catalog/visited slices
(S-02/S-03/S-04). Do not open Phase 2 or 3 against code that has not shipped;
if a product slice ships its own guardrail tests via `/10x-implement` first,
the corresponding phase narrows to the gaps that remain.

## 4. Stack

The classic test base for this project. Recommendations are grounded in
`build.gradle` plus the MCP/tools exposed in the current session.

| Layer | Tool | Version | Notes |
|---|---|---|---|
| unit + integration | JUnit 5 + AssertJ + Mockito | (Spring Boot 4.0.6 BOM) | `useJUnitPlatform()`; base for all logic tests |
| web slice | Spring `@WebMvcTest` + spring-security-test | (BOM) | Route-gating / controller tests; `@WithMockUser`, `@MockitoBean` |
| data slice | `@DataJpaTest` + H2 (PostgreSQL mode) | (BOM) | Repository/entity mapping against local engine |
| prod-engine integration | `@SpringBootTest @Testcontainers` Postgres 16 | (BOM) | Dual-engine migration proof; CI gate (AGENTS.md mandate) |
| e2e / browser | none — flows via `MockMvc`/integration | n/a | Server-rendered Thymeleaf+HTMX; no SPA tier, no browser MCP in session |

**Stack grounding tools (current session):**
- Docs: **Context7** — can validate Spring Boot 4 / Thymeleaf / spring-security-test APIs and HTMX fragment patterns when wiring Phase 2/3 tests; checked: 2026-06-22
- Search: **none** in session — fall back to Context7 + local config; checked: 2026-06-22
- Runtime/browser: **none** (no Playwright/browser MCP) — Phase 3 flows use `MockMvc`, not a browser; checked: 2026-06-22
- Provider/platform: **Linear** — issue tracking; possible quality-gate relevance (CI/issue status), not used as a test surface; checked: 2026-06-22

## 5. Quality Gates

The full set of gates that must pass before a change reaches production.
"Required after §3 Phase N" means the gate is enforced once that rollout
phase lands; before that, the gate is `planned`.

| Gate | Where | Required? | Catches |
|---|---|---|---|
| compile + `./gradlew test` | local + CI | required | logic regressions, wiring breaks |
| dual-engine migration check (Testcontainers Postgres) | CI on push/PR | required (AGENTS.md mandate) | H2↔Postgres DDL drift, entity↔schema mismatch |
| access-control + IDOR suite | local + CI | required after §3 Phase 1 | unprotected new routes, cross-user data exposure |
| recommender correctness suite | local + CI | required after §3 Phase 2 | guardrail violations (untruthful rationale, dropped axis, visited leak) |
| user-flow integration | CI on PR | required after §3 Phase 3 | broken end-to-end journeys |

## 6. Cookbook Patterns

How to add new tests in this project. Sub-sections seeded from the shipped
S-01 patterns carry a real reference test; the rest read
"TBD — see §3 Phase N" until that phase ships.

### 6.1 Adding a unit test (pure logic / form validation)

- **Location**: `src/test/java/com/nextslope/<domain>/`.
- **Naming**: `<Thing>Tests.java` (class suffix `*Tests`).
- **Reference test**: `src/test/java/com/nextslope/user/RegistrationFormValidationTests.java` (bean-validation), `.../user/UserRegistrationServiceTests.java` (service logic with Mockito).
- **Run locally**: `./gradlew test --tests com.nextslope.user.UserRegistrationServiceTests`.

### 6.2 Adding a web-slice test (controller + security)

- **Type**: `@WebMvcTest` + `@Import(SecurityConfig.class)`, collaborators as `@MockitoBean`.
- **Pattern**: drive `MockMvc`, assert status/redirect/fragment; use `@WithMockUser` for the authenticated case and a plain request for anonymous.
- **Reference test**: `src/test/java/com/nextslope/PermitListLockTests.java`, `.../SignupWebMvcTests.java`.
- **Run locally**: `./gradlew test --tests com.nextslope.PermitListLockTests`.

### 6.3 Adding a data-slice / prod-engine test

- **Type**: `@DataJpaTest` (H2) for mapping/queries; `@SpringBootTest @Testcontainers` (Postgres 16) for prod-engine proof.
- **Reference test**: `src/test/java/com/nextslope/user/UserRepositoryPostgresTests.java`.
- **Run locally**: `./gradlew test --tests com.nextslope.user.UserRepositoryPostgresTests` (requires Docker).

### 6.4 Adding an access-control / IDOR test (per new gated route)

- **Harness** (`src/test/java/com/nextslope/support/`): `UserFixtures` (two distinct
  `USER`s + one `ADMIN`, with plaintext passwords for `formLogin`),
  `TwoUserIntegrationTestBase` (`@SpringBootTest @AutoConfigureMockMvc`; persists the
  fixture set per test, cleans up, exposes `loginAsUserA/B/Admin()` →
  `MockHttpSession`), and `AccessControlAssertions` (the vocabulary:
  `assertRedirectedToLogin`, `assertReachedPastSecurity`, `assertForbidden`,
  `assertWrongOwnerDenied`).
- **Anonymous + permit-list gating** (web-slice): `@WebMvcTest` +
  `@Import(SecurityConfig.class)`. For each new route assert anonymous →
  `assertRedirectedToLogin`. Add the route to the must-stay-gated sample set in
  `PermitListLockTests` (the sample-based permit-list lock — see reference below).
- **Wrong-role → 403** (web-slice): copy `RoleGatingPatternTests` — import production
  `SecurityConfig` and gate the role test-locally via a `@TestConfiguration`
  (`@EnableMethodSecurity`) + `@PreAuthorize("hasRole('ADMIN')")` demo handler. Assert
  anonymous → `/login`, `@WithMockUser(roles="USER")` → 403,
  `@WithMockUser(roles="ADMIN")` → 200. When S-06 lands, swap the demo handler for the
  real admin route and note whether enforcement is method-security or URL
  authorization.
- **Cross-user / IDOR → denied** (integration): extend `TwoUserIntegrationTestBase`
  (see `OwnershipPatternIntegrationTests`). Log in as user B, request user A's owned
  resource, assert `AccessControlAssertions.assertWrongOwnerDenied`. This assertion is
  a documented placeholder today (no owned resource exists); S-02 (profile) / S-04
  (visited) specialize it against the first real owned route.
- **Reference tests**: `PermitListLockTests` (permit-list lock + real gated `/error`
  route + `/whatever` canary), `RoleGatingPatternTests` (anonymous/USER/ADMIN
  vocabulary), `OwnershipPatternIntegrationTests` (two-user ownership shape),
  `CsrfEnforcedTests`, `H2ConsoleProfileTests`.
- **Run locally**: `./gradlew test --tests "com.nextslope.support.*" --tests com.nextslope.PermitListLockTests`.
- **Extend, don't re-derive**: every later slice (S-02/S-04/S-06) plugs its
  route-specific assertions into this harness instead of inlining new security setup.

### 6.5 Adding a recommender correctness test

- TBD — see §3 Phase 2. Will cover the all-axes differential pattern, the visited/new-only hard filter, and the rationale-truthfulness oracle (expected axis derived from user input, never from the generator).

### 6.6 Adding an end-to-end user-flow test

- TBD — see §3 Phase 3. Will cover multi-step `MockMvc` journeys (signup → profile → browse → mark-visited → recommend) with no browser.

## 7. What We Deliberately Don't Test

Exclusions agreed during the rollout (Phase 2 interview, Q5). Future
contributors should respect these unless the underlying assumption changes.

- **Pixel / visual snapshots of pages** — break on every CSS tweak, catch nothing. Re-evaluate only if a visual regression actually ships. (Source: interview Q5.)
- **Third-party CDN/library behavior (Bootstrap, HTMX)** — that is the library's job, not ours. (Source: interview Q5.)
- **Exhaustive admin-form permutations** — one trusted admin, low blast radius; minimal validation tests (percentages sum to 100, non-negative ints) live inside the S-06 slice's own tests, not a dedicated phase. (Source: interview Q5; PRD US-03 AC.)
- **Standalone configuration / infrastructure tests** — not a budget line. Exception: the existing dual-engine Flyway/Postgres migration check stays in CI (AGENTS.md mandate, not new budget). (Source: interview Q5.)

## 8. Freshness Ledger

- Strategy (§1–§5) last reviewed: 2026-06-22
- Stack versions last verified: 2026-06-22
- AI-native tool references last verified: 2026-06-22

Refresh (`/10x-test-plan --refresh`) when:

- a new top-3 risk surfaces from the roadmap or archive,
- a recommended tool's `checked:` date is older than three months,
- the project's tech stack changes (new framework, new test runner),
- §7 negative-space no longer matches what the team believes.
