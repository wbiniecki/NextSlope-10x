<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Browser-driven E2E Smoke Suite — Phase 2

- **Plan**: context/changes/testing-browser-e2e-smoke/plan.md
- **Scope**: Phase 2 of 2 (CI gate + test-plan bookkeeping; commit e0dfa49)
- **Date**: 2026-07-12
- **Verdict**: APPROVED
- **Findings**: 0 critical, 1 warning, 4 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS (1 observation) |
| Scope Discipline | PASS |
| Safety & Quality | WARNING (1 warning, 2 observations) |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS (1 observation) |

## Plan-drift summary (all planned changes)

- `.github/workflows/ci.yml` — **MATCH**. `playwrightInstall --no-daemon` then `e2eTest --no-daemon` appended to the existing `Test` job after the PIT step (ci.yml:35-47); no `~/.cache/ms-playwright` cache, no `continue-on-error`, no xvfb; step naming/comment/`--no-daemon` style matches the existing steps.
- `context/foundation/test-plan.md` — **MATCH**. §6.6 filled with `HtmxSmokeE2eTests` + `./gradlew e2eTest` reference recipe (factual claims verified against build.gradle and the test source); §5 HTMX-browser-smoke gate marked enforced 2026-07-12 with the real CI step names; §3 Phase 3 row set to `implementing` (valid parser literal) and references `context/changes/testing-browser-e2e-smoke/` with the browser-smoke/MockMvc split; §8 ledger untouched; no renumbering.
- `context/changes/testing-browser-e2e-smoke/change.md` — **MATCH**. Frontmatter-only status bookkeeping as contracted.
- Cross-phase check — Phase 2 did not break Phase 1 assumptions: CI invokes exactly the tasks Phase 1 registered (`playwrightInstall` JavaExec of `com.microsoft.playwright.CLI install --with-deps chromium`, `e2eTest` outside `check`), and §6.6's description of the journey matches the shipped test.
- No EXTRA changes, no "What We're NOT Doing" guardrail violations (`e2eTest` remains outside `check`/`build` — confirmed via `./gradlew check --dry-run`).

## Success Criteria Verification (run 2026-07-12)

Automated (Phase 2):

- 2.1 `./gradlew tasks --all` — **PASS** (lists `e2eTest` — "Runs the browser-driven e2e smoke suite (headless Chromium via Playwright)" — and `playwrightInstall`). `./gradlew check --dry-run` — **PASS** (graph is `compileJava…test→check` only; no `e2eTest`/`compileE2eTestJava` — outside `check`).
- 2.2 `./gradlew e2eTest --no-daemon` — **PASS**. First invocation was `UP-TO-DATE`; forced with `--rerun-tasks`: `BUILD SUCCESSFUL in 20s`, JUnit XML `tests="1" failures="0" errors="0"` (HtmxSmokeE2eTests), app booted and shut down gracefully.
- Note: first attempt failed inside the sandbox (`Could not determine a usable wildcard IP` — Gradle FileLockContentionHandler); re-run with elevated permissions per standing instructions.

Manual (Phase 2, per Progress):

- 2.3 (CI workflow reviewed) — checked `[x]`, "confirmed by user 2026-07-12"; independently corroborated against ci.yml: install precedes blocking `e2eTest`, no browser cache, no `continue-on-error`. Legitimate.
- 2.4 (test-plan §6.6/§3/§5 consistency) — checked `[x]`, "confirmed by user 2026-07-12"; corroborated except one adjective (see F2). Legitimate.

## Findings

### F1 — CI job has no `timeout-minutes`; new browser steps raise hang exposure to GitHub's 6-hour default

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: .github/workflows/ci.yml:10
- **Detail**: The `Test` job has never set `timeout-minutes` (pre-existing), but Phase 2 adds the two most hang-prone operations in the pipeline: a network download of Chromium + apt system dependencies (`playwrightInstall`) and real browser processes (`e2eTest`). A wedged download or zombie browser now burns up to GitHub's 360-minute default — significant against the free-tier 2,000 min/month budget. The whole job normally completes in well under 15 minutes.
- **Fix**: Add `timeout-minutes: 20` to the `Test` job (one line under `runs-on`).
- **Decision**: FIXED (2026-07-12) — `timeout-minutes: 10` chosen (recent CI runs complete in ~2 minutes; new Playwright steps add a few more, leaving ample headroom)

### F2 — Test-plan §6.6 restates "auto-waiting assertions exclusively" without the htmx-readiness-guard caveat

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: context/foundation/test-plan.md:252
- **Detail**: The §6.6 recipe says the reference test "Uses Playwright auto-waiting assertions exclusively (no sleeps)". Phase 1's review (F3 there) already established the test also uses one deterministic condition wait — `page.waitForFunction("() => window.htmx !== undefined")` — and the plan gained an explicit addendum documenting it. The freshly written cookbook entry omits that caveat, so the doc slightly overstates "exclusively" on the very point the previous review clarified. "No sleeps" remains true.
- **Fix**: Append a parenthetical to test-plan.md:252, e.g. "(no sleeps; plus one condition-based htmx-readiness guard — see the plan's Critical Implementation Details addendum)".
- **Decision**: FIXED (2026-07-12)

### F3 — Workflow lacks a least-privilege `permissions:` block (pre-existing)

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: .github/workflows/ci.yml:1-11
- **Detail**: The workflow declares no `permissions:`, so the GITHUB_TOKEN gets the repository default (potentially read/write) while the job only needs to read contents. Not introduced by Phase 2 — noted because the file was touched and hardening it is a two-line change. No secret exposure or script injection exists in the new steps (plain `run:` lines, no github-context interpolation).
- **Fix**: Add `permissions:\n  contents: read` at the workflow top level.
- **Decision**: FIXED (2026-07-12)

### F4 — An e2e compile error would fail CI at the "Install Playwright Chromium" step, not the smoke step

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: build.gradle:97 (surfaces in .github/workflows/ci.yml:39-40)
- **Detail**: `playwrightInstall` uses `classpath = sourceSets.e2eTest.runtimeClasspath`, which wires a task dependency on `e2eTestClasses`. A compile error in `src/e2eTest` therefore fails the CI step named "Install Playwright Chromium (+ OS dependencies)" rather than "Browser e2e smoke" — mildly confusing failure attribution, no functional harm (the run still fails, blocking as intended).
- **Fix**: Accept as-is; if the attribution ever confuses a CI triage, point `playwrightInstall`'s classpath at `configurations.e2eTestRuntimeClasspath` (dependencies only, no compiled classes) instead.
- **Decision**: ACCEPTED (2026-07-12) — accepted as-is per user triage

### F5 — Documented Playwright cache path `~/.cache/ms-playwright` is Linux-only

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: context/foundation/test-plan.md:256 (also context/changes/testing-browser-e2e-smoke/change.md:23)
- **Detail**: The §6.6 "Run locally" note (and the change-folder note it was lifted from) says the first run downloads Chromium to `~/.cache/ms-playwright`. That is the Linux default; on macOS — the actual dev machine — Playwright uses `~/Library/Caches/ms-playwright`. The behavior described (download once, reuse afterward) is correct; only the path is platform-specific.
- **Fix**: Qualify the path, e.g. "`~/.cache/ms-playwright` (Linux; `~/Library/Caches/ms-playwright` on macOS)".
- **Decision**: FIXED (2026-07-12)
