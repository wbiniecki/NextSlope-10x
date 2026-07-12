# Test Plan

> Phased test rollout for this project. Strategy is frozen at the top
> (§1–§5); cookbook patterns at the bottom (§6) fill in as phases ship.
> Read before writing any new test.
>
> Refresh: re-run `/10x-test-plan --refresh` when stale (see §8).
>
> Last updated: 2026-07-02 (strategy change: a small, isolated, CI-gated browser
> smoke tier is now permitted for client-side HTMX behavior MockMvc can't see)

## 1. Strategy

Tests follow three non-negotiable principles for this project:

1. **Cost × signal.** The cheapest test that gives a real signal for the
   risk wins. This is a server-rendered Thymeleaf + HTMX app with no SPA
   tier, so user flows are exercised with Spring `MockMvc`/integration tests
   — do not reach for a browser/e2e tool when a `MockMvc` request→response
   assertion already catches the regression. **One narrow exception (strategy
   change, 2026-07-02):** a small browser smoke tier is permitted *only* for
   the client-side behavior `MockMvc` structurally cannot observe — the HTMX
   in-place DOM swaps (mark-visited toggle, "Recommend resorts" → three-result
   render + progress indicator). `MockMvc` proves the server *returns* the right
   fragment; only a real browser proves it actually *swaps into the DOM* without
   a full reload. That incremental signal is the sole justification — the tier
   stays scoped to the 1–2 highest-signal HTMX journeys and never re-tests flows
   the server-side layer already proves.
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
| 1 | Access-control & privacy regression net | Lock the current auth surface and ship a reusable per-route gating + ownership/IDOR + admin-authz test pattern every later slice extends | #4, #5 | web-slice + integration | complete | context/archive/2026-06-23-testing-access-control-privacy-net/ |
| 2 | Recommender correctness suite | Prove all-axes matching, the visited/new-only hard filter, and a truthful rationale for the north-star recommendation flow, plus a recommender-scoped mutation-testing gate (gated on S-05 shipping) | #1, #2, #3 | unit + integration + mutation (PIT, recommender packages only) | not started | — |
| 3 | End-to-end user-flow coverage | Walk the real journeys (signup → profile → browse → mark-visited → recommend; admin-create → appears in browse) with `MockMvc`/integration, **plus a small isolated browser smoke tier for the HTMX in-place swaps `MockMvc` can't see** (mark-visited toggle; recommend → three-result render + progress indicator). Prereqs S-02/03/04/05 all `done` → unblocked | #1–#5 (flow-level) | integration + browser smoke (isolated source set / task) | implementing | context/archive/2026-07-12-testing-browser-e2e-smoke/ (browser-smoke half — shipped; server-side `MockMvc` journey half — still open) |

**Status vocabulary** (fixed — parser literals): `not started` →
`change opened` → `researched` → `planned` → `implementing` → `complete`.

**Sequencing reality.** Phase 1 is complete. Phase 2 depends on the recommender
slice (S-05) existing; Phase 3 depends on the profile/catalog/visited/recommend
slices (S-02/S-03/S-04/S-05). As of 2026-07-02 **all of S-02/03/04/05 are `done`**,
so Phase 3 is unblocked (its browser smoke tier needs the shipped mark-visited and
recommend HTMX surfaces to drive). Do not open Phase 2 or 3 against code that has
not shipped; if a product slice ships its own guardrail tests via `/10x-implement`
first, the corresponding phase narrows to the gaps that remain. The Phase-3
browser tier is deliberately **isolated** (its own source set / Gradle task + a
dedicated headless CI step) so the fast `./gradlew test` stays fast.

## 4. Stack

The classic test base for this project. Recommendations are grounded in
`build.gradle` plus the MCP/tools exposed in the current session.

| Layer | Tool | Version | Notes |
|---|---|---|---|
| unit + integration | JUnit 5 + AssertJ + Mockito | (Spring Boot 4.0.6 BOM) | `useJUnitPlatform()`; base for all logic tests |
| web slice | Spring `@WebMvcTest` + spring-security-test | (BOM) | Route-gating / controller tests; `@WithMockUser`, `@MockitoBean` |
| data slice | `@DataJpaTest` + H2 (PostgreSQL mode) | (BOM) | Repository/entity mapping against local engine |
| prod-engine integration | `@SpringBootTest @Testcontainers` Postgres 16 | (BOM) | Dual-engine migration proof; CI gate (AGENTS.md mandate) |
| e2e / browser smoke (HTMX only) | **Playwright for Java** (`com.microsoft.playwright:playwright`); Selenium/Selenide = heavier alternative, HtmlUnit rejected (JS engine too weak for modern HTMX) | 1.61.0 (Maven Central latest, `lastUpdated 2026-06-29`); checked: 2026-07-02 | **Strategy change 2026-07-02.** Small isolated tier for the client-side HTMX swaps `MockMvc` can't see. Playwright-Java recommended for its bundled browser download, first-class headless on `ubuntu-latest`, and auto-waiting (reduces HTMX-swap flakiness). Tool choice is now **decided and shipped** (`testing-browser-e2e-smoke`, 2026-07-12) — a committed CI dependency. Runs as `@SpringBootTest(webEnvironment = RANDOM_PORT)` + seeded H2, isolated as its own source set / Gradle task with a dedicated headless CI step (keep `./gradlew test` fast). **Not the `cursor-ide-browser` MCP** — that is an in-session interactive driver, not a committed CI dependency |
| mutation testing (recommender only) | `info.solidsoft.pitest` Gradle plugin + `pitest-junit5-plugin` | plugin 1.19.0 / `junit5PluginVersion` 1.2.3 | Java 21 ✓ (plugin needs 17+); Gradle 9.4.1 ✓ (≥ plugin min 8.4, but plugin's Gradle-9 support is "initial"/smoke-tested vs 9.0 at release → smoke-verify `./gradlew pitest` once at S-05 wiring). `pitest-junit5-plugin` documents JUnit-Platform support to 1.10 "and probably above"; Spring Boot 4 ships a newer platform → verify at wiring. **Not wired today** — deferred to S-05 (`three-resort-recommendation`); scoped to recommender packages only, never repo-wide |

**Stack grounding tools (current session):**
- Docs: **Context7** — can validate Spring Boot 4 / Thymeleaf / spring-security-test APIs and HTMX fragment patterns when wiring Phase 2/3 tests; checked: 2026-06-22
- Search: **none** in session — fall back to Context7 + local config; checked: 2026-06-22
- Runtime/browser: **`cursor-ide-browser` MCP present** in this session (previously none) — an in-session, interactive browser driver useful for *prototyping/verifying* a Phase-3 HTMX journey during implementation. **It is NOT the committed suite:** it runs only inside the agent session, never on the headless `ubuntu-latest` CI runner, so the CI-gated browser smoke tier needs a real JVM dependency (Playwright-Java, §4 row above). Phase-3 server-side flows still use `MockMvc`; only the HTMX-swap smoke journeys use the browser; checked: 2026-07-02
- Provider/platform: **Linear** — issue tracking; possible quality-gate relevance (CI/issue status), not used as a test surface; checked: 2026-06-22
- Mutation: **`info.solidsoft.pitest` 1.19.0 + `pitest-junit5-plugin` 1.2.3** — version contract grounded against the Java 21 / Gradle 9.4.1 / Spring Boot 4.0.6 stack; wiring deferred to S-05 (smoke-verify on Gradle 9.4.1 + confirm JUnit-Platform compat at that time); checked: 2026-06-25

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
| recommender mutation-score gate (PIT, scoped to recommender packages) | local + CI | required after §3 Phase 2 | surviving mutants in scorer/filter/rationale logic (weak/tautological assertions). Threshold: a high package-scoped mutation score, exact % calibrated when S-05 lands — never repo-wide |
| user-flow integration | CI on PR | required after §3 Phase 3 | broken end-to-end journeys (server-side, via `MockMvc`/integration) |
| HTMX browser smoke (isolated) | CI on PR — **own headless steps** (`playwrightInstall` → `e2eTest` Gradle tasks in `.github/workflows/ci.yml`), not folded into `./gradlew test` | **required (enforced 2026-07-12** via `testing-browser-e2e-smoke`; blocking, no `continue-on-error`) | broken client-side HTMX in-place swaps (mark-visited toggle, recommend render + progress indicator) that server-side tests structurally can't see. Must run headless on `ubuntu-latest`; the in-session `cursor-ide-browser` MCP does NOT satisfy this gate |

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

- **All-axes differential pattern**: change exactly one preference axis (region / novelty /
  difficulty / experience) in isolation and assert the candidate set or ordering changes —
  proves every axis is actually wired into scoring (Risk #2). Avoid asserting exact score
  numbers copied from the scorer; avoid over-mocking so an axis never executes.
- **Visited / new-only hard filter**: a `new-only` user with a visited resort never sees it in
  the result; a `revisit-okay` user still can. Cover the empty/all-visited edge, not just the
  revisit-okay happy path (Risk #3).
- **Rationale-truthfulness oracle**: assert the "why this matched you" line names a preference
  axis the user actually set **and** corresponds to the matched resort's real scoring reasons
  (Risk #1). Derive the expected axis from the user's input — **never** from the rationale
  generator's own output (that is the oracle problem / a tautology).
- **Mutation gate** (the package-scoped PIT run, §5): wire `info.solidsoft.pitest` to kill
  surviving mutants in the recommender scorer/filter/rationale logic. Scope `targetClasses` to
  `com.nextslope.recommendation.*` (the `.filter` / `.scorer` / `.rationale` subpackages) with
  `targetTests` mirroring the same packages; **exclude** `user` / `config` / `web` / `support`
  and the root app. PIT earns signal only if the assertions encode an independent oracle
  (expected values from user input, never the generator) — a tautological assertion lets
  mutants survive silently. Keep recommender unit tests plain JUnit 5 + AssertJ (no full Spring
  context) so mutants are killed fast.
- **Deferred to S-05**: the gate is **not wired today**. The `pitest {}` block, the exact
  `mutationThreshold` %, the final recommender package names, the CI cadence, and the Gradle
  9.4.1 smoke check are all resolved in the S-05 (`three-resort-recommendation`) plan when the
  recommender code exists. Extend that scaffolding then; don't re-derive it.

### 6.6 Adding an end-to-end user-flow test

Two layers; the browser-smoke half shipped 2026-07-12 via
`context/archive/2026-07-12-testing-browser-e2e-smoke/`, the server-side half is still TBD:

- **Server-side journeys**: TBD — see §3 Phase 3 (still open). Multi-step
  `MockMvc`/integration walks (signup → profile → browse → mark-visited →
  recommend; admin-create → appears in browse).
- **Browser smoke (HTMX only, strategy change 2026-07-02) — shipped**:
  - **Location**: `src/e2eTest/java/com/nextslope/e2e/` — a dedicated `e2eTest`
    source set that `./gradlew test`/`check`/`build` never touch (Playwright is
    scoped to `e2eTestImplementation` only).
  - **Type**: `@SpringBootTest(webEnvironment = RANDOM_PORT)` on the default
    profile (in-memory H2 + Flyway + 150-resort seed) + Playwright-Java (§4)
    driving headless Chromium; reuses `src/test` fixtures (`UserFixtures`) via
    source-set wiring.
  - **Reference test**: `src/e2eTest/java/com/nextslope/e2e/HtmxSmokeE2eTests.java`
    — one chained journey: real form login → save profile → recommend (3 cards
    swapped in, no reload) → visited toggle on/off (button swap + `htmx:afterSwap`
    row highlight), plus a second admin test: active-toggle off/on (button swap +
    `htmx:afterSwap` row restyle, seed data restored in-test). Uses Playwright
    auto-waiting assertions (no sleeps; plus two condition-based waits — an
    htmx-readiness guard, see the plan's Critical Implementation Details
    addendum, and an `htmx:afterSettle` guard against the post-swap
    listener-rebind race) and a `window.__e2eMarker` survival check to prove
    in-place swap rather than full reload.
  - **Run locally**: `./gradlew e2eTest`. First run downloads Chromium (~150 MB)
    to `~/.cache/ms-playwright` (Linux default; `~/Library/Caches/ms-playwright`
    on macOS) and is correspondingly slower; subsequent runs reuse the local
    download. `e2eTest` is intentionally never `UP-TO-DATE`, so each invocation
    really reruns the browser smoke suite. To provision explicitly (what CI does):
    `./gradlew playwrightInstall`.
  - **CI**: blocking per-PR steps in `.github/workflows/ci.yml` —
    `./gradlew playwrightInstall --no-daemon` (Chromium + Linux deps,
    deliberately uncached) then `./gradlew e2eTest --no-daemon`.
  - **Accepted risk (CDN dependency in CI)**: this smoke check depends on live
    `cdn.jsdelivr.net` availability because the app loads pinned Bootstrap 5 and
    HTMX 2.0.4 assets from jsDelivr at page load. SRI hashes prevent false-greens
    (unexpected CDN content cannot be accepted), so the failure mode is false-red:
    the CI `Browser e2e smoke` step times out in `awaitHtmxReady()`. Triage:
    check [status.jsdelivr.com](https://status.jsdelivr.com) and re-run once
    before suspecting product code. Revisit this decision if CDN-caused false reds
    occur more than rarely: vendor the three pinned assets (Bootstrap CSS + JS
    bundle, HTMX JS) under
    `src/main/resources/static/` for all environments (still no build step),
    rather than maintaining a split E2E profile.
  - **Recorded exclusions (targeted review, 2026-07-13)**: `hx-disabled-elt`
    request-time button disabling stays unasserted (same anti-flake rationale as
    the transient spinner; structural wiring is asserted). Profile-form checkbox
    JavaScript remains unasserted as low-risk progressive enhancement exercised
    implicitly by the journey's real profile save. Session-expiry HTMX failure is
    a known product gap (AJAX POST can 302 to `/login` with no
    `htmx:responseError`/redirect handling); it needs a dedicated change
    (auth-aware HX-Request handling in `SecurityConfig` + a client handler) and
    is deliberately not patched in this chore branch.
  - **Watch items (targeted review, 2026-07-13)**: shared named-H2
    (`jdbc:h2:mem:nextslope;MODE=PostgreSQL;DB_CLOSE_DELAY=-1`) is safe with one
    E2E class but becomes an isolation hazard when a second class arrives — then
    move shared setup/cleanup into a base harness or enforce per-class unique
    fixture emails. CI's single-job `timeout-minutes: 10` currently has margin
    (measured healthy runs are ~3–5 min including the Playwright steps), but
    slow apt/CDN/Chromium-download days can consume it; raise the timeout if a
    healthy run is ever cancelled.
  - Cover only the 1–2 HTMX in-place swaps `MockMvc` can't see. **Out of scope**
    (keep it small): pixel/visual snapshots (§7), cross-browser matrices,
    exhaustive page coverage, and re-testing flows already proven server-side.
    The `cursor-ide-browser` MCP may help prototype a journey interactively, but
    it is not the committed runner.

## 7. What We Deliberately Don't Test

Exclusions agreed during the rollout (Phase 2 interview, Q5). Future
contributors should respect these unless the underlying assumption changes.

- **Pixel / visual snapshots of pages** — break on every CSS tweak, catch nothing. Re-evaluate only if a visual regression actually ships. (Source: interview Q5.) *Still excluded even with the new browser tier — the browser smoke tests assert DOM/behavior, never pixels.*
- **Third-party CDN/library behavior (Bootstrap, HTMX)** — that is the library's job, not ours. (Source: interview Q5.) **Narrowed 2026-07-02 (strategy change):** we still don't test HTMX-the-library, but we *do* smoke-test that **our** pages wire HTMX correctly end-to-end in a real browser — i.e. that the mark-visited toggle and the recommend button actually trigger the expected in-place DOM swap (+ progress indicator) rather than a full reload or no-op. The exclusion now covers "does HTMX work," not "does our page use HTMX correctly." (See §1 exception, §3 Phase 3, §4 browser row.)
- **Exhaustive admin-form permutations** — one trusted admin, low blast radius; minimal validation tests (percentages sum to 100, non-negative ints) live inside the S-06 slice's own tests, not a dedicated phase. (Source: interview Q5; PRD US-03 AC.)
- **Standalone configuration / infrastructure tests** — not a budget line. Exception: the existing dual-engine Flyway/Postgres migration check stays in CI (AGENTS.md mandate, not new budget). (Source: interview Q5.)
- **Repo-wide mutation testing** — PIT is deliberately scoped to the recommender (scorer/filter/rationale) only; the auth/web/config surfaces are guarded by cheaper slice tests where a surviving mutant is near-zero signal. (Source: cost × signal, §1 principle #1; test-plan-refresh-2026-06-25.)

## 8. Freshness Ledger

- Strategy (§1–§5) last reviewed: 2026-07-02 (deliberate change — permitted a small, isolated, CI-gated browser smoke tier for HTMX in-place swaps; §1/§3/§4/§5 amended)
- Stack versions last verified: 2026-07-02 (added e2e/browser row — Playwright-Java 1.61.0 grounded via Maven Central; earlier rows unchanged since 2026-06-22 / PIT 2026-06-25)
- AI-native tool references last verified: 2026-07-02 (`cursor-ide-browser` MCP now present in session — noted as in-session-only, not the committed CI runner)
- PIT/mutation tooling grounded: 2026-06-25

Refresh (`/10x-test-plan --refresh`) when:

- a new top-3 risk surfaces from the roadmap or archive,
- a recommended tool's `checked:` date is older than three months,
- the project's tech stack changes (new framework, new test runner),
- §7 negative-space no longer matches what the team believes.
