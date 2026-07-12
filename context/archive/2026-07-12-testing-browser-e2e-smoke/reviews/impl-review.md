<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Browser-driven E2E Smoke Suite

- **Plan**: context/changes/testing-browser-e2e-smoke/plan.md
- **Scope**: Phase 1 of 2
- **Date**: 2026-07-12
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 2 warnings, 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | WARNING |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Success Criteria Verification (run 2026-07-12)

Automated (Phase 1):

- `./gradlew e2eTest` — **PASS** (`BUILD SUCCESSFUL in 2m 30s`; includes first-run Chromium download; chained journey green, app booted on random port and shut down cleanly).
- `./gradlew test` — **PASS** (`BUILD SUCCESSFUL`; task graph contains no `compileE2eTestJava` / `e2eTest` — nothing under `src/e2eTest` compiled or executed).
- `./gradlew build` — **PASS** (`BUILD SUCCESSFUL`; graph runs `check` but not `e2eTest` — confirmed not wired into `check`).

Manual (Phase 1, per Progress):

- 1.4 (headed-mode visual confirmation) — checked `[x]`; inherently out-of-band, no in-diff evidence possible; accepted as claimed by the implement session (same day as commit 40bf93b).
- 1.5 (no sleeps / reload-marker assertions) — checked `[x]`; **verified against source**: no `Thread.sleep`/`waitForTimeout`; reload-marker set+assert present for recommend (lines 141/150) and both visited toggles (lines 162/171/178). One condition-based wait exists — see F3.

## Findings

### F1 — tearDown lacks try/finally: a Playwright close failure skips DB cleanup

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/e2eTest/java/com/nextslope/e2e/HtmxSmokeE2eTests.java:76-91
- **Detail**: `tearDown()` calls `playwright.close()` first and only then deletes visited rows → profile → user. If the Playwright close throws, the DB cleanup never runs, leaving the seeded user in the JVM-cached H2 context for any later class sharing that context. The FK-safe order and never-delete-resorts rules themselves are correctly implemented.
- **Fix**: Wrap the close in `try { playwright.close(); } finally { /* DB cleanup */ }` (or run the DB cleanup before closing Playwright).
- **Decision**: FIXED (2026-07-12)

### F2 — Test-plan strategy amendment exists only as an uncommitted working-tree edit

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: context/foundation/test-plan.md (uncommitted diff, §1/§3/§4/§5/§6.6/§7/§8)
- **Detail**: The plan and frame treat the 2026-07-02 frozen-strategy amendment (permitting the browser smoke tier) as an already-done prerequisite, but it is not in git history — it lives only in the working tree, alongside the uncommitted plan.md Progress SHA stamps. If lost, the plan's stated prerequisite and its §-references silently break.
- **Fix**: Commit the test-plan.md amendment (plus the plan.md Progress stamps) as a docs commit on this branch — Phase 2 will edit test-plan.md again anyway, so landing the baseline first keeps history coherent.
- **Decision**: FIXED (2026-07-12, commit 0c3cad5)

### F3 — `awaitHtmxReady()` deviates from the "auto-waiting assertions exclusively" contract

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/e2eTest/java/com/nextslope/e2e/HtmxSmokeE2eTests.java:186-188
- **Detail**: The plan mandates "Playwright auto-waiting assertions exclusively — no sleeps, no manual waits", but the test adds `page.waitForFunction("() => window.htmx !== undefined")` before the recommend click. It is a deterministic condition wait (not a sleep) that prevents a real race — a click landing before the CDN-loaded HTMX runtime initializes would silently no-op — so it serves the contract's anti-flake intent while deviating from its letter. Progress item 1.5 ("no sleeps/fixed waits") was checked with this present.
- **Fix**: Keep the guard and record it as a one-line plan addendum under Critical Implementation Details (condition-based htmx-readiness wait, explicitly not a sleep).
- **Decision**: FIXED (2026-07-12)

### F4 — Unplanned `shouldRunAfter test` ordering hint on the e2eTest task

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: build.gradle:89
- **Detail**: The `e2eTest` task adds `shouldRunAfter tasks.named('test')`, which the plan's contract did not specify. Benign — it only orders the two tasks when both are requested in one invocation and does not couple `e2eTest` into `check`/`build` (verified via task graphs).
- **Fix**: Keep it and note it in the plan's build.gradle contract as an addendum (or delete the line if strict contract fidelity is preferred).
- **Decision**: FIXED (2026-07-12)

### F5 — One non-auto-waiting assertion (`assertEquals` on `locator.count()`)

- **Severity**: 💬 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/e2eTest/java/com/nextslope/e2e/HtmxSmokeE2eTests.java:159-160
- **Detail**: The initial "row un-highlighted" check uses `assertEquals(0, page.locator(...).count(), ...)` — an immediate snapshot — while every other DOM assertion in the file uses auto-waiting `assertThat(...)`. Race risk is negligible here (the preceding `assertThat` already synchronized the page), but it is the file's one inconsistency and a latent flake pattern if copied.
- **Fix**: Replace with `assertThat(page.locator("#" + rowId + ".table-active")).hasCount(0)` to match the rest of the file.
- **Decision**: FIXED (2026-07-12)
