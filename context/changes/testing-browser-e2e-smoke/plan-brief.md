# Browser-driven E2E Smoke Suite — Plan Brief

> Full plan: `context/changes/testing-browser-e2e-smoke/plan.md`
> Frame brief: `context/changes/testing-browser-e2e-smoke/frame.md`

## What & Why

> Add a **small, isolated, CI-gated browser-driven e2e smoke suite** that boots
> the real app and drives a headless browser through the 1–2 journeys where
> client-side HTMX behavior is the point (mark-visited in-place toggle;
> "Recommend resorts" → three-result render + progress indicator) — buying the
> one signal `MockMvc` cannot.

(Reframed problem statement, lifted from the frame. The frozen test-plan
strategy was already amended on 2026-07-02 to permit this tier.)

## Starting Point

42 test files cover every slice server-side (unit, web-slice, integration,
ownership, PIT mutation gate), but nothing executes the client-side HTMX
wiring: the visited toggle's `outerHTML` swap + custom `htmx:afterSwap`
row-highlight JS, and the recommend button's `innerHTML` swap into
`#recommend-results`. No browser tooling exists in the build; no test boots the
app on a real port.

## Desired End State

`./gradlew e2eTest` boots the app (in-memory H2, Flyway, 150-resort seed),
launches headless Chromium, and runs one chained journey green: form login →
save profile → recommend (exactly 3 cards swapped in, no page reload) →
visited toggle on/off (button swap + row highlight, no reload). The suite is a
blocking per-PR CI step; `./gradlew test` is untouched.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| e2e definition | Real browser (not MockMvc journeys) | Developer override of the frozen strategy; amendment landed 2026-07-02 | Frame |
| Browser tool | Playwright for Java 1.61.0 | Bundled headless Chromium + auto-waiting assertions absorb HTMX swap latency (main flake killer) | Plan |
| Journey shape | Both HTMX flows, one chained journey | One app boot + one browser session covers both swaps and the login/CSRF plumbing at lowest runtime | Plan |
| Auth setup | Pre-seed user via `UserFixtures`, log in through the real form | Reuses the fixture pattern while still proving real form-login + CSRF in a browser; signup already proven by MockMvc | Plan |
| Build placement | Separate `e2eTest` source set + task, not in `check` | `./gradlew test` stays fast; Playwright never leaks onto the unit-test classpath | Frame |
| CI cadence | Every PR, blocking step, cached browser | Matches the amended §5 gate; ~2–3 min warm-cache cost | Plan |
| Spinner assertion | Structural wiring only, not transient visibility | H2-fast responses make visible-spinner assertions the #1 flake risk | Plan |
| No-reload proof | `window` JS marker survives the click | Only reliable way to prove an in-place swap vs a full reload | Plan |

## Scope

**In scope:**
- `e2eTest` source set + Gradle task + Playwright-Java 1.61.0 dependency
- One chained journey test (`HtmxSmokeE2eTests`) covering recommend + visited toggle
- Blocking CI step with Playwright browser caching
- Test-plan fill-in (§6.6 recipe, §5 gate enforced, §3 Phase 3 status)

**Out of scope:**
- The MockMvc-journey half of test-plan Phase 3 (stays open)
- Pixel/visual snapshots, cross-browser matrices, signup/admin UI flows
- Re-testing anything the server-side tier already proves

## Architecture / Approach

`@SpringBootTest(webEnvironment = RANDOM_PORT)` boots the real app once per
class on the default profile (H2 + Flyway + seed). The test persists a user
with the existing `UserFixtures`/`PasswordEncoder` pattern, then drives
everything through the UI with Playwright auto-waiting assertions — no sleeps.
The source set reuses `src/test` output for fixtures; the CI step caches
`~/.cache/ms-playwright` keyed on the Playwright version.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Playwright harness + chained journey | `./gradlew e2eTest` green locally; `test` untouched | First-ever real-port boot + browser CSRF/login path (never exercised outside MockMvc) |
| 2. CI gate + test-plan bookkeeping | Blocking per-PR step with cached browser; docs match reality | CI flake/cost on the merge path; missing Chromium system deps on the runner |

**Prerequisites:** strategy amendment (done 2026-07-02); S-02/03/04/05 shipped (done).
**Estimated effort:** ~2 sessions across 2 phases.

## Open Risks & Assumptions

- Thymeleaf's auto-injected CSRF hidden input on the login/profile forms has
  never been proven outside MockMvc — Phase 1 is the first real-browser proof;
  if it surprises, the fix is in the test approach, not production code.
- Spinner timing on fast H2 responses is inherently unassertable — mitigated by
  asserting structural wiring instead (locked in the plan).
- Playwright's Gradle-9/Java-21 fit is current-generation and low-risk, but the
  first CI run may need `install --with-deps chromium` for system libraries.

## Success Criteria (Summary)

- A broken HTMX swap (either flow) fails a PR before merge — signal that never
  existed before.
- `./gradlew test` speed and scope are provably unchanged.
- The suite stays small: one journey, one browser, ~2–3 min warm-cache CI cost.
