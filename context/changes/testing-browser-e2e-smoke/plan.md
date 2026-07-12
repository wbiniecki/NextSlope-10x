# Browser-driven E2E Smoke Suite Implementation Plan

## Overview

Add a small, isolated, CI-gated browser-driven e2e smoke suite: Playwright for
Java drives a headless Chromium through one chained journey against the real
running app, verifying the two client-side HTMX behaviors that `MockMvc`
structurally cannot see — the recommend fragment swap (+ indicator wiring) and
the visited-toggle swap with its custom `htmx:afterSwap` row-highlight JS.

This implements the browser-smoke half of test-plan §3 Phase 3, under the
strategy amendment of 2026-07-02 (`context/foundation/test-plan.md`
§1/§3/§4/§5/§6.6/§7).

## Current State Analysis

- **No browser tooling exists.** `build.gradle` has only default `main`/`test`
  source sets; deps are JUnit 5/MockMvc/Testcontainers/PIT. CI
  (`.github/workflows/ci.yml`) runs `./gradlew test` + `./gradlew pitest` on
  `ubuntu-latest`.
- **No real-HTTP precedent.** All 42 existing test files use `@SpringBootTest`
  (MOCK) or slices; nothing boots `webEnvironment = RANDOM_PORT`. Nothing blocks
  it either: a default-profile boot uses in-memory H2, runs Flyway, and
  `ResortSeedLoader` (an `ApplicationRunner` with an empty-table guard,
  `ResortSeedLoader.java:26-74`) seeds **150 resorts**.
- **HTMX surface is assertion-ready** (see Key Discoveries).
- **Auth fixtures exist**: `UserFixtures` (`src/test/java/com/nextslope/support/UserFixtures.java:23-48`)
  exposes plaintext passwords + BCrypt persistence exactly for form-login tests.
- Frame brief: `context/changes/testing-browser-e2e-smoke/frame.md` (framing,
  scope philosophy, and the strategy-amendment prerequisite — already done).

## Desired End State

`./gradlew e2eTest` boots the app on a random port, launches headless Chromium,
and runs one chained journey green: seeded user logs in through the real form →
saves a preference profile through the real form → on `/resorts` clicks
"Recommend resorts" and sees exactly three recommendation cards swapped in
without a page reload → toggles a visited button and sees the button swap +
row highlight flip. The suite runs as its own blocking CI step on every PR;
`./gradlew test` is untouched and exactly as fast as today.

### Key Discoveries:

- **Visited toggle**: `POST /resorts/{id}/visited` returns only the button
  fragment; `hx-swap="outerHTML"` on `button.visited-toggle`
  (`templates/resorts/list.html:88-95`). Row highlight (`tr.table-active`) is
  applied by a custom `htmx:afterSwap` listener reading `data-visited`
  (`templates/fragments/layout.html:56-65`) — **the most browser-only logic in
  the app**; MockMvc can never execute it.
- **Recommend**: `POST /recommend` button targets `#recommend-results`,
  `hx-swap="innerHTML"`, `hx-indicator="#recommend-indicator"`
  (`templates/resorts/list.html:22-34`). Success branch renders 3 ×
  `#recommend-results .row .col` cards with `h2.card-title` and
  `p.card-text.fst-italic` rationale (`list.html:108-133`). No-profile branch
  renders `.alert.alert-info` (`list.html:138-141`) — the journey must save a
  profile first.
- **CSRF**: enabled everywhere except `/h2-console`. Layout pages publish meta
  tags + an `htmx:configRequest` header hook (`layout.html:41-49`). Login/signup
  forms use `th:action`, so Thymeleaf's Spring integration auto-injects the
  hidden `_csrf` input server-side — a real browser just works, but no test has
  ever proven that path (all MockMvc tests inject `.with(csrf())`). This suite
  is the first proof.
- **Login form fields**: `name="username"` (email), `name="password"`
  (`templates/login.html:22-34`); success redirect `/`
  (`SecurityConfig.java:60-63`).
- **Profile form params**: `experienceLevel`, `difficultyBand`,
  `noveltyPreference`, `anyRegion`, `regionCountries`; save redirects to
  `/resorts` (`ProfileController.java:42-74`).
- **Playwright for Java 1.61.0** verified current on Maven Central 2026-07-02
  (test-plan §4).

## What We're NOT Doing

- **Server-side MockMvc journey coverage** — the other half of test-plan §3
  Phase 3 stays open; this change ships only the browser smoke tier.
- Pixel/visual snapshot assertions (§7 still excludes them).
- Cross-browser matrices — Chromium only.
- Driving the signup UI (proven by `SignupIntegrationTests`; violates cost×signal
  to re-test in a browser) or any admin flows (S-06 not shipped).
- Re-testing anything the existing MockMvc/integration tier already proves
  (route gating, ownership, recommender correctness).
- Testing HTMX-the-library — only *our* pages' wiring of it.
- Wiring `e2eTest` into `check`/`build` — it runs only when invoked explicitly
  (locally or by its CI step).

## Implementation Approach

One chained journey in one test class, in a dedicated `e2eTest` source set that
`./gradlew test` never touches. The app boots once per class via
`@SpringBootTest(webEnvironment = RANDOM_PORT)` on the default profile
(in-memory H2 + Flyway + 150-resort seed); the test persists a user with the
existing `UserFixtures` pattern and drives everything else through the real UI.
Playwright's auto-waiting assertions absorb HTMX swap latency (no manual
sleeps). CI explicitly provisions Chromium and its Linux dependencies before
running the new blocking browser-test step.

Decisions locked during planning: Playwright-Java 1.61.0; both HTMX flows in a
single chained journey; pre-seeded user + real form login; separate source
set/task; per-PR blocking CI gate.

## Critical Implementation Details

- **Do not assert the spinner's transient visibility.** `/recommend` against
  in-memory H2 completes in milliseconds; the `htmx-indicator` may never be
  visibly shown long enough for a stable assertion. Assert the *structural*
  wiring instead (button has `hx-indicator="#recommend-indicator"`, the
  indicator element exists with class `htmx-indicator`) plus the final swap.
  Asserting transient visibility is the suite's #1 flake risk.
- **Prove "swap, not reload" with a JS marker.** Before clicking, set
  `window.__e2eMarker = true` via `page.evaluate(...)`; after asserting the
  swap, assert the marker survives. A full page reload wipes `window`, so a
  surviving marker proves the HTMX in-place swap actually happened — this is
  the whole point of the browser tier and is not derivable from the DOM alone.
- **Reuse `src/test` fixtures via source-set wiring.** The e2e test needs
  `UserFixtures`; wire `e2eTest`'s classpaths to include `sourceSets.test.output`
  rather than duplicating the fixture. Keep Playwright deps on
  `e2eTestImplementation` only so they never leak onto the unit-test classpath.
- **Playwright downloads browsers automatically on first local run** (to
  `~/.cache/ms-playwright`) and reuses that local download afterward. CI does
  not cache this directory: it always runs the repository's `playwrightInstall`
  task (`com.microsoft.playwright.CLI install --with-deps chromium`) before
  `e2eTest`, deterministically provisioning both Chromium and Linux system
  dependencies.
- **Seed data caveat**: `ResortSeedLoader` seeds only when the `resorts` table
  is empty. The e2e class boots its own context, so it gets the full 150-resort
  seed — but do not add `resortRepository.deleteAll()`-style cleanup (the
  pattern some integration tests use), or the recommend journey loses its
  candidate set.

## Phase 1: Playwright harness + chained journey (local green)

### Overview

Wire the isolated `e2eTest` source set with Playwright-Java, and land the single
chained journey test passing locally via `./gradlew e2eTest`, with
`./gradlew test` provably unaffected.

### Changes Required:

#### 1. Gradle: `e2eTest` source set, configuration, and task

**File**: `build.gradle`

**Intent**: Create the isolated tier — new source set at `src/e2eTest/java`,
Playwright dependency scoped to it, and a dedicated task — so browser tests
never run with (or slow down) the standard suite.

**Contract**: `sourceSets.e2eTest` with compile/runtime classpath extending
`main` output **and** `sourceSets.test.output` (to reuse `UserFixtures`);
`e2eTestImplementation` extends `testImplementation`;
`e2eTestRuntimeOnly` extends `testRuntimeOnly` (thereby inheriting application
`runtimeOnly` dependencies such as H2, plus the test-only JUnit Platform
launcher);
`com.microsoft.playwright:playwright:1.61.0` on `e2eTestImplementation` only;
a `Test`-type task `e2eTest` with
`testClassesDirs = sourceSets.e2eTest.output.classesDirs`,
`classpath = sourceSets.e2eTest.runtimeClasspath`, and `useJUnitPlatform()`,
**not** added to `check`; and a `JavaExec` task `playwrightInstall` using the
e2e runtime classpath, main class `com.microsoft.playwright.CLI`, and arguments
`install --with-deps chromium`. `tasks.named('test')` remains untouched.

#### 2. E2E journey test

**File**: `src/e2eTest/java/com/nextslope/e2e/HtmxSmokeE2eTests.java`

**Intent**: The one chained journey. Boot the app once
(`@SpringBootTest(webEnvironment = RANDOM_PORT)`, default profile), persist a
user via `UserFixtures` + `PasswordEncoder`, launch headless Chromium once per
class, then in order: (1) log in through the real `/login` form
(`username`/`password` fields); (2) save a preference profile through the real
`/profile` form; (3) on `/resorts`, set the reload marker, click
"Recommend resorts", assert exactly 3 cards render in `#recommend-results`
(name + non-empty rationale per card), indicator wiring is structurally
present, and the marker survived; (4) click the first `.visited-toggle`,
assert the button swaps (`data-visited="true"`, text `Visited ✓`), its
`#resort-row-{id}` gains `table-active` (proving the `htmx:afterSwap`
listener), the marker still survives — then toggle back off and assert the
reverse (row highlight removed).

**Contract**: Selectors from Key Discoveries (`#recommend-results .row .col`,
`button.visited-toggle[data-visited]`, `#resort-row-{id}.table-active`,
`#recommend-indicator.htmx-indicator`). Use Playwright auto-waiting assertions
(`assertThat(locator)...`) exclusively — no sleeps, no manual waits. Clean up
after the class in FK-safe order: visited rows → preference profile → user.
Never delete resorts.

#### 3. Cookbook-ready run documentation

**File**: `context/changes/testing-browser-e2e-smoke/change.md` (Notes)

**Intent**: Record the local run command (`./gradlew e2eTest`) and the
first-run browser-download expectation, so Phase 2 can lift the wording into
test-plan §6.6 verbatim.

**Contract**: A short "How to run" note in the change folder; no code.

### Success Criteria:

#### Automated Verification:

- `./gradlew e2eTest` passes locally (chained journey green, headless)
- `./gradlew test` still passes and does not compile or execute anything under `src/e2eTest`
- `./gradlew build` succeeds without running `e2eTest` (not wired into `check`)

#### Manual Verification:

- Journey visually confirmed once in headed mode (or via the `cursor-ide-browser` MCP prototype) — cards and row highlight behave as asserted
- Test source reviewed: no sleeps/fixed waits; reload-marker assertions present for both HTMX interactions

**Implementation Note**: After completing this phase and all automated
verification passes, pause here for manual confirmation from the human that
the manual testing was successful before proceeding to the next phase.

---

## Phase 2: CI gate + test-plan bookkeeping

### Overview

Make the suite a blocking per-PR gate with deterministic browser/dependency
provisioning, and update the test plan so the docs match the shipped reality.

### Changes Required:

#### 1. CI step for the browser suite

**File**: `.github/workflows/ci.yml`

**Intent**: Explicitly install Chromium plus Linux system dependencies, then run
`./gradlew e2eTest --no-daemon` as a new blocking step in the existing `Test`
job after the PIT step.

**Contract**: Run `./gradlew playwrightInstall --no-daemon` (the Gradle
`JavaExec` wrapper around `com.microsoft.playwright.CLI install --with-deps
chromium`) before the `e2eTest` step. Do not cache
`~/.cache/ms-playwright`; Playwright's guidance notes that cache restoration is
often comparable to downloading, while Linux system dependencies are not
cacheable. No `continue-on-error`. Headless is Playwright's default — no xvfb
needed on `ubuntu-latest`.

#### 2. Test-plan updates (fill, not strategy change)

**File**: `context/foundation/test-plan.md`

**Intent**: Reflect shipped reality: fill cookbook §6.6's browser-smoke recipe
with the real reference test + run command; mark the §5 `HTMX browser smoke`
gate as enforced; update §3 Phase 3's Status/Change-folder columns to show the
browser-smoke half shipped via this change while the MockMvc-journey half of
Phase 3 remains open.

**Contract**: §6.6 gets `HtmxSmokeE2eTests` + `./gradlew e2eTest` as the
reference recipe; §3 Phase 3 row references
`context/changes/testing-browser-e2e-smoke/`; §8 ledger untouched (this is
fill-in, not a strategy edit). Do not renumber sections.

#### 3. Change-folder close-out prep

**File**: `context/changes/testing-browser-e2e-smoke/change.md`

**Intent**: Set `status: implementing` → progress notes as phases land (per
the standard change-folder convention); no Linear issue to move (test-plan
phase, not a roadmap slice — per frame).

**Contract**: Frontmatter `status`/`updated` fields only.

### Success Criteria:

#### Automated Verification:

- `./gradlew tasks --all` lists `playwrightInstall` and `e2eTest`; `e2eTest` remains outside `check`
- `./gradlew e2eTest --no-daemon` passes locally using the same test command configured in CI

#### Manual Verification:

- CI workflow reviewed: explicit Chromium/dependency installation precedes blocking `e2eTest`, with no browser cache and no `continue-on-error`
- Test-plan §6.6/§3/§5 read consistently against what actually shipped

### PR Acceptance Gates

These checks run after the Phase 2 commit is pushed and do not block the
phase-end commit ritual:

- PR CI is green, including the explicit `playwrightInstall` and new `e2eTest` steps
- Observed added CI wall-clock cost is acceptable within the cold-run ~3–5 min budget
- The existing `./gradlew test` step duration remains within normal noise

---

## Testing Strategy

### Unit Tests:

- None — this change *is* test infrastructure; the existing 42-file suite is
  the regression net for everything it touches (nothing in `src/main` changes).

### Integration Tests:

- The chained journey itself (Phase 1) is the deliverable: login → profile →
  recommend (3 cards, no reload) → visited toggle on/off (swap + row highlight,
  no reload).

### Manual Testing Steps:

1. Run `./gradlew e2eTest` locally twice — second run must be green without a
   browser re-download.
2. Run once in headed mode and watch the journey: three cards appear in place,
   the toggled row highlights, no full-page flash on either interaction.
3. Temporarily break the `htmx:afterSwap` listener locally (rename the class it
   checks) and confirm the suite **fails** — proves the row-highlight assertion
   carries real signal, then revert.

## Performance Considerations

- One app boot + one browser launch per class; the whole suite should stay
  under ~1 min locally after the first browser download.
- CI: ~3–5 min cold-run budget for explicit Chromium/dependency installation
  plus the browser test. Reconsider browser caching only after measured runs
  show a material net saving; if runtime still creeps upward, the journey count
  — not the tier — is the first lever.

## Migration Notes

None — no production code, schema, or config changes. Purely additive test
infrastructure; deleting `src/e2eTest` + the Gradle/CI blocks reverts it
completely.

## References

- Frame brief: `context/changes/testing-browser-e2e-smoke/frame.md`
- Strategy amendment: `context/foundation/test-plan.md` §1/§3/§4/§5/§6.6/§7
  (2026-07-02)
- HTMX surface: `src/main/resources/templates/resorts/list.html:22-34,51-70,88-95,107-142`,
  `src/main/resources/templates/fragments/layout.html:41-65`,
  `src/main/java/com/nextslope/web/VisitedController.java:30-45`,
  `src/main/java/com/nextslope/web/RecommendController.java:28-37`
- Fixtures/login: `src/test/java/com/nextslope/support/UserFixtures.java:23-48`,
  `src/main/java/com/nextslope/config/SecurityConfig.java:60-63`,
  `src/main/resources/templates/login.html:22-34`
- Seed: `src/main/java/com/nextslope/resort/ResortSeedLoader.java:26-74`
- Phase precedent: `context/archive/2026-06-23-testing-access-control-privacy-net/change.md`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Playwright harness + chained journey (local green)

#### Automated

- [x] 1.1 `./gradlew e2eTest` passes locally (chained journey green, headless)
- [x] 1.2 `./gradlew test` still passes and does not compile or execute anything under `src/e2eTest`
- [x] 1.3 `./gradlew build` succeeds without running `e2eTest` (not wired into `check`)

#### Manual

- [x] 1.4 Journey visually confirmed once in headed mode (or via the `cursor-ide-browser` MCP prototype) — cards and row highlight behave as asserted
- [x] 1.5 Test source reviewed: no sleeps/fixed waits; reload-marker assertions present for both HTMX interactions

### Phase 2: CI gate + test-plan bookkeeping

#### Automated

- [ ] 2.1 `./gradlew tasks --all` lists `playwrightInstall` and `e2eTest`; `e2eTest` remains outside `check`
- [ ] 2.2 `./gradlew e2eTest --no-daemon` passes locally using the same test command configured in CI

#### Manual

- [ ] 2.3 CI workflow reviewed: explicit Chromium/dependency installation precedes blocking `e2eTest`, with no browser cache and no `continue-on-error`
- [ ] 2.4 Test-plan §6.6/§3/§5 read consistently against what actually shipped
