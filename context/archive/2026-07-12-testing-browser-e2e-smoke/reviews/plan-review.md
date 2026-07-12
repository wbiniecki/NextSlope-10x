<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Browser-driven E2E Smoke Suite

- **Plan**: `context/changes/testing-browser-e2e-smoke/plan.md`
- **Mode**: Deep
- **Date**: 2026-07-12
- **Verdict**: SOUND
- **Findings**: 3 critical, 2 warnings, 0 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | PASS |
| Plan Completeness | PASS |

## Grounding

4/4 existing modification targets ✓; planned new E2E path absent as expected; 8/8 referenced contracts ✓; brief↔plan ✓

## Findings

### F1 — Progress titles do not match six success criteria

- **Severity**: ❌ CRITICAL
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 1/2 Success Criteria ↔ Progress
- **Detail**: Progress paraphrases criteria 1.4 and 2.1–2.5 instead of copying their immutable titles. The Progress contract requires a matching `N.M` row for every criterion; `/10x-implement` mutates rows by exact title.
- **Fix**: Copy the six Success Criteria titles verbatim into Progress.
- **Decision**: FIXED — Progress titles synchronized verbatim after the final criteria edits.

### F2 — Phase 2 requires CI before its commit can exist

- **Severity**: ❌ CRITICAL
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Plan Completeness
- **Location**: Phase 2 — Automated Verification
- **Detail**: Criteria 2.1–2.3 require a PR CI run, a second run/cache hit, and timing evidence. `/10x-implement` must pass phase checks before making the phase commit; the CI workflow change cannot run until that commit is pushed. This creates a circular completion gate.
- **Fix ⭐ Recommended**: Make Phase 2 criteria locally/static verifiable, and move real CI-green/cache/timing confirmation to the PR test plan and final merge gate.
  - Strength: Matches the phase-commit workflow while retaining a real CI acceptance gate before merge.
  - Tradeoff: Progress will not record the first live CI proof.
  - Confidence: HIGH — the implementation workflow explicitly verifies before its phase-end commit.
  - Blind spot: None significant.
- **Decision**: FIXED — phase checks are local/static; live CI evidence moved to post-implementation PR acceptance gates.

### F3 — Custom source-set runtime wiring omits H2 and JUnit launcher

- **Severity**: ❌ CRITICAL
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 1 — Gradle contract
- **Detail**: `e2eTestImplementation.extendsFrom(testImplementation)` does not inherit `runtimeOnly` H2/Flyway dependencies or `testRuntimeOnly` JUnit Platform launcher. Main/test compiled output is not a runtime dependency classpath. Gradle 9.4.1's documented custom-test pattern separately inherits `runtimeOnly` and explicitly sets the Test task's `testClassesDirs` and `classpath`.
- **Fix**: Require `e2eTestRuntimeOnly.extendsFrom(testRuntimeOnly)` plus explicit `testClassesDirs = sourceSets.e2eTest.output.classesDirs` and `classpath = sourceSets.e2eTest.runtimeClasspath`.
- **Decision**: FIXED — runtime inheritance and explicit Test/JavaExec task contracts added.

### F4 — Teardown omits the persisted preference profile

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 1 — E2E journey cleanup
- **Detail**: The journey creates a profile, but the plan only names visited rows and the user for cleanup. `V3__create_preference_profiles.sql` has a non-cascading FK to `users`; deleting the user first can fail teardown. Existing integration tests delete profile data explicitly.
- **Fix**: Specify teardown order: visited rows → preference profile → user.
- **Decision**: FIXED — teardown order now includes preference-profile deletion before user deletion.

### F5 — Cache-first CI does not deterministically provision Chromium

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Lean Execution
- **Location**: Phase 2 — CI step
- **Detail**: The plan caches `~/.cache/ms-playwright` and conditionally suggests `install --with-deps` only if launch fails. Browser-cache restoration does not install Linux system libraries. Playwright 1.61's CI guidance provisions browsers/dependencies explicitly and generally discourages browser caching because restore time is often comparable to download.
- **Fix A ⭐ Recommended**: Always run Playwright CLI `install --with-deps chromium`; remove the cache and cache-hit criterion.
  - Strength: Deterministic and follows Playwright's documented CI path.
  - Tradeoff: Downloads Chromium on each fresh runner.
  - Confidence: HIGH — verified against Playwright 1.61 documentation.
  - Blind spot: Exact download time on this repository's CI is unmeasured.
- **Fix B**: Keep the cache, but still run `install --with-deps chromium` before `e2eTest`.
  - Strength: Retains possible binary-download savings.
  - Tradeoff: More CI configuration for a cache Playwright does not generally recommend; OS dependencies remain uncached.
  - Confidence: HIGH — cache and system dependencies are separate.
  - Blind spot: Net time saved is unmeasured.
- **Decision**: FIXED via Fix A — explicit `install --with-deps chromium` on every CI run; browser cache removed.

## Triage Summary

- **Fixed**: F1, F2, F3, F4, F5 (5)
- **Skipped**: None
- **Accepted**: None
- **Dismissed**: None
- **Verdict after fixes**: SOUND
