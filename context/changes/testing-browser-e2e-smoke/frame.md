# Frame Brief: Small browser-driven e2e suite (deliberate strategy change)

> Framing step before /10x-plan. This document captures what is *actually*
> at issue, separated from what was initially assumed.
>
> **Direction confirmed by developer (2026-07-02): a small *browser-driven* e2e
> suite** — an intentional departure from the frozen test-plan no-browser
> strategy. The earlier MockMvc reframe is preserved below as the rejected path,
> because the frozen strategy is exactly what this change must amend.

## Reported Observation

The developer wants to "develop e2e tests," and — when the frame surfaced that
the frozen strategy defines e2e as browser-less `MockMvc` journeys — confirmed
they specifically want **a small browser-driven suite** (a real browser driving
the running app). So the live problem is not "what does e2e mean here" (settled:
the developer overrides it) but **"what does it cost to add a browser tier to a
project whose frozen strategy deliberately excludes one, and how small can it
stay while still earning its keep."**

## Initial Framing (preserved)

- **User's stated cause or approach**: add a browser-driven end-to-end suite.
- **User's proposed direction**: a *small* browser suite (explicitly scoped
  down, not a full UI-automation matrix).
- **Pre-dispatch narrowing**: the frame first proposed the MockMvc reading (the
  frozen strategy); the developer **overrode it** in favor of a real browser.
  That override is a strategy change, not a plan detail — it reopens §1/§4/§7 of
  `test-plan.md`.

## Dimension Map

With the browser direction confirmed, the framing risk moves from *definition*
to *feasibility and cost*:

1. **Strategy conflict** — the frozen test-plan forbids a browser tier. Can a
   Phase-3 plan legitimately introduce one, or must the strategy be refreshed
   first?
2. **Tooling reality** — *what* drives the browser in a committed, CI-gated
   suite? The available `cursor-ide-browser` MCP is an interactive tool for the
   agent, not a repo dependency that runs in GitHub Actions.  ← the load-bearing
   trap
3. **CI feasibility** — CI is `ubuntu-latest` running `./gradlew test`. A browser
   suite must run **headless there**, or it isn't a gate, just a local ritual.
4. **"Small" = which journeys** — a browser only earns its cost where it sees
   something `MockMvc` cannot: the **client-side HTMX in-place swaps** (mark-
   visited toggle, recommend render + the 2s progress indicator). Everything else
   is already covered server-side.
5. **Where it runs in the build** — folded into the fast `test` task (slows every
   build) or isolated (separate source set / task / job)?

## Hypothesis Investigation

| Hypothesis | Evidence | Verdict |
| --- | --- | --- |
| Dim 1: A Phase-3 plan can just add a browser tier | `test-plan.md` §1 ("do not reach for a browser/e2e tool when a `MockMvc` … assertion already catches the regression"), §4 (`e2e/browser — none`), §7 (visual snapshots + third-party HTMX behavior are *deliberately not tested*) are the **frozen strategy**. A change that contradicts frozen strategy is a strategy edit. | STRONG — needs `/10x-test-plan --refresh` first (amend §1/§4/§7), *then* plan |
| Dim 2: The `cursor-ide-browser` MCP is the test engine | The MCP drives a browser **inside the agent session only**; CI (`.github/workflows/ci.yml`) runs `./gradlew test --no-daemon` on a headless Ubuntu runner with no MCP. A committed suite needs a **JVM browser-automation dependency** in `build.gradle` (none today — only Testcontainers + PIT). | STRONG — MCP ≠ committed suite; a real dep (Playwright-Java / Selenium / HtmlUnit) is required |
| Dim 3: A browser can run in this CI | `ubuntu-latest` supports headless Chromium (Playwright's `playwright install chromium`, or Selenium + Selenium-Manager). Feasible, but adds a provisioning step + minutes to CI. | CONFIRMED feasible, at a cost |
| Dim 4: A browser gives signal MockMvc lacks | The app is HTMX-driven (mark-visited toggle, recommend button → fragment swap, progress indicator NFR). `MockMvc` proves the server *returns* the right fragment; only a browser proves it actually *swaps into the DOM* without a full reload. §7 currently excludes this — the override narrowly re-includes "does OUR page wire HTMX correctly," not "does HTMX-the-library work." | STRONG — this is the one genuine incremental signal; scope the suite to it |
| Dim 5: It belongs in the fast `test` task | 42 existing tests + Testcontainers already run in `test`; a browser boot per journey is far slower and flakier. | LEAN — isolate as a separate source set / Gradle task (own CI step), not inline in `test` |

## Narrowing Signals

- The single highest-value target for a browser is the **HTMX interaction layer**
  — precisely the behavior the current strategy parks in §7 as "don't test."
  Keeping the suite to those 1–2 journeys is what makes it "small" *and*
  defensible on cost×signal (§1 principle #1).
- No browser tooling exists in `build.gradle`; this change adds a new test tier
  and a CI provisioning step — real, one-time cost the plan must own.
- Precedent for the *phase* holds (Phase 1 = `testing-access-control-privacy-net`,
  a test-plan rollout phase, not a roadmap slice, no separate Linear issue) — but
  Phase 3 as originally written said "no browser," so this specific change
  **redefines** Phase 3's method, which is why the strategy refresh comes first.

## Cross-System Convention

For a Spring Boot server-rendered app, a committed browser e2e test is a
`@SpringBootTest(webEnvironment = RANDOM_PORT)` booting the real app (seeded H2)
plus a browser-automation library hitting `http://localhost:{port}`. The modern
low-friction choice on a headless Ubuntu CI runner is **Playwright for Java**
(bundled browser download, first-class headless, auto-waiting that reduces HTMX-
swap flakiness); Selenium/Selenide is the heavier, more conventional alternative;
HtmlUnit is cheapest but its JS engine is unreliable for modern HTMX and would
undercut the very signal we're buying. The `cursor-ide-browser` MCP is useful for
*me* to prototype/verify a journey interactively, but it is **not** the committed
runner.

## Reframed (or Confirmed) Problem Statement

> **The actual problem to plan around is**: add a **small, isolated, CI-gated
> browser-driven e2e smoke suite** that boots the real app and drives a headless
> browser through the 1–2 journeys where client-side HTMX behavior is the point
> (mark-visited in-place toggle; "Recommend resorts" → three-result render +
> progress indicator) — buying the one signal `MockMvc` cannot. This requires
> **first amending the frozen test-plan strategy** (`/10x-test-plan --refresh`:
> §1 no-browser principle, §4 stack row, §7 HTMX exclusion), then a plan that
> picks the JVM browser tool (Playwright-Java recommended), wires a separate
> source set / Gradle task, and adds the headless-browser CI step.

The initial "e2e = MockMvc journeys" reframe was **overridden by the developer**.
The direction is now a real browser suite — deliberately small, targeted at the
HTMX layer, and explicitly a strategy change rather than a straight Phase-3
build.

## Resolved Unknowns

- **Browser vs. MockMvc** — resolved by the developer: **browser**. The
  no-browser strategy (§1/§4/§7) is therefore treated as the thing this change
  amends, not a constraint that blocks it.
- **Is the `cursor-ide-browser` MCP the suite?** — **No.** It runs only in-
  session; the committed, CI-gated suite needs a real JVM browser dependency.
  The MCP's role is prototyping/verification during implementation.
- **Is this a roadmap slice?** — No. Still a test-plan rollout phase (Phase 3),
  tracked under the slices it exercises; no new roadmap entry, no separate Linear
  issue.

## Open Unknowns for /10x-plan (and the strategy refresh)

1. **Browser tool** — Playwright-Java (recommended) vs. Selenium/Selenide vs.
   HtmlUnit. Trade-off: CI simplicity + HTMX-swap reliability vs. familiarity.
2. **Journey set** — confirm the "small" scope: which 1–2 flows. Leading
   candidates: mark-visited toggle (HTMX partial swap) and recommend render +
   progress indicator. Owner: user.
3. **Build placement** — separate `e2e` source set + Gradle task + its own CI
   step (recommended, keeps `./gradlew test` fast) vs. inline.
4. **CI cost tolerance** — headless-browser install adds minutes; is a per-PR
   gate wanted, or a lighter cadence?

## Confidence

**HIGH on the framing, decisions still open.** The direction is confirmed by the
developer; the load-bearing constraints are all direct-read (frozen strategy in
`test-plan.md`, CI shape in `ci.yml`, no browser dep in `build.gradle`, HTMX as
the sole browser-only signal). What remains is genuinely a *planning* choice
(tool, journey set, build placement), not a framing ambiguity — plus the
prerequisite that the frozen strategy be refreshed before the plan lands.

## What Changes for /10x-plan

**Prerequisite:** run `/10x-test-plan --refresh` to amend the frozen strategy —
§1 (permit a narrow browser smoke tier), §4 (add the chosen browser tool as a
stack row), §7 (relax the "don't test HTMX" exclusion to "smoke-test that our
pages wire HTMX correctly"). Without this, a browser suite contradicts a frozen
doc.

**Then plan (in scope):** a small browser-driven e2e smoke suite —
`@SpringBootTest(RANDOM_PORT)` + a JVM browser lib (Playwright-Java recommended),
seeded H2, isolated as its own source set / Gradle task with a dedicated headless
CI step; cover the 1–2 HTMX journeys (mark-visited toggle; recommend render +
progress indicator); fill cookbook §6.6 with the browser-e2e recipe.

**Explicitly out of scope (keep it small):** pixel/visual snapshot assertions
(still excluded — §7), cross-browser matrices, exhaustive page coverage, and
re-testing flows already fully proven by the existing `MockMvc`/integration
tier. The browser suite exists only for the client-side behavior the server-side
tests structurally cannot see.

Open the change folder via `/10x-new` (suggested change_id
`testing-browser-e2e-smoke`, a Phase-3 sibling of the archived
`testing-access-control-privacy-net`).

## References

- Frozen strategy this change amends: `context/foundation/test-plan.md` §1
  (no-browser principle), §3 Phase 3, §4 (`e2e/browser — none`), §7 (visual /
  HTMX exclusions), §8 (refresh trigger: "tech stack changes / negative-space no
  longer matches")
- CI shape: `.github/workflows/ci.yml` (`ubuntu-latest`, `./gradlew test
  --no-daemon` + `./gradlew pitest`; headless browser must run here)
- Build: `build.gradle` (no browser/e2e dep today; Testcontainers + PIT present;
  `test` task uses `useJUnitPlatform()`)
- HTMX surface (the browser-only signal): the mark-visited and recommend
  controllers/fragments (`src/main/java/com/nextslope/web/VisitedController.java`,
  `.../RecommendController.java` and their Thymeleaf partials)
- Precedent: `context/archive/2026-06-23-testing-access-control-privacy-net/change.md`
  (test-plan rollout phase = a phase, not a roadmap slice; no separate Linear issue)
- Tooling note: `cursor-ide-browser` MCP is an in-session interactive driver, not
  a committed CI test dependency
