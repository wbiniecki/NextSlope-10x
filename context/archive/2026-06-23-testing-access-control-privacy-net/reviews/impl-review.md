<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Access-control & Privacy Regression Net

- **Plan**: `context/changes/testing-access-control-privacy-net/plan.md`
- **Scope**: All phases (1–3 of 3)
- **Date**: 2026-06-24
- **Verdict**: APPROVED (with 2 minor warnings)
- **Findings**: 0 critical, 2 warnings, 0 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

## Grounding

`git diff --name-only a2d5c0c^..HEAD -- src/main/` is empty — the test-only guardrail held (no production code changed). All 11 planned deliverables MATCH their contracts (3 harness classes, Phase-2 web-slice tests + `RouteGatingTests` deletion folded into `PermitListLockTests`, Phase-3 seed tests + cookbook/AGENTS docs). No EXTRA/scope-creep source files; only expected `plan.md`/`change.md` workflow metadata changed alongside the tests. `./gradlew test` (full suite, incl. Testcontainers Postgres) is green. All Phase 1–3 manual Progress items confirmed complete by the user.

## Findings

### F1 — Stale RouteGatingTests reference left in cookbook §6.2

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: context/foundation/test-plan.md:150-151
- **Detail**: This change deleted `RouteGatingTests.java` (folded into `PermitListLockTests`) and updated §6.4, but §6.2 still cites `RouteGatingTests.java` as its reference test (line 150) and `./gradlew test --tests com.nextslope.RouteGatingTests` as the run command (line 151). Anyone following §6.2 hits a deleted class. `SignupWebMvcTests.java` (also referenced) still exists.
- **Fix**: Repoint §6.2 to a live web-slice test — replace `RouteGatingTests` with `PermitListLockTests` in both the reference list and the run command (keep `SignupWebMvcTests`).
- **Decision**: FIXED

### F2 — assertReachedPastSecurity passes on a 403 (latent, future reuse)

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/test/java/com/nextslope/support/AccessControlAssertions.java:34-41
- **Detail**: `assertReachedPastSecurity` only asserts `redirectedUrl != "/login"`. A 403 response has a null redirect, so it would pass on a forbidden response — semantically the opposite of "reached past security." Current callers are safe (`RoleGatingPatternTests.adminReachesTheRoute` pairs it with `status().isOk()`; `PermitListLockTests` uses an inline check), but S-02/S-04 reusing the named helper standalone could get a false green.
- **Fix**: Harden the helper to also reject denial statuses (assert HTTP status is not 401/403) so the vocabulary is safe to reuse standalone.
- **Decision**: FIXED
