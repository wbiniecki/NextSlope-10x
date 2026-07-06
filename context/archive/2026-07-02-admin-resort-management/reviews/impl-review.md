<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Admin Resort Management (S-06) Implementation Plan

- **Plan**: context/changes/admin-resort-management/plan.md
- **Scope**: Phase 1 of 3
- **Date**: 2026-07-03
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical 2 warnings 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | WARNING |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

## Verification Evidence

- `./gradlew test` — **PASS** (`BUILD SUCCESSFUL in 49s`)
- `./gradlew test --tests com.nextslope.web.AdminResortControllerTests` — **PASS** (`BUILD SUCCESSFUL in 5s`)
- `./gradlew test --tests com.nextslope.PermitListLockTests` — **PASS** (`BUILD SUCCESSFUL in 5s`)
- `./gradlew test --tests com.nextslope.resort.ResortRepositoryTests` — **PASS** (`BUILD SUCCESSFUL in 5s`)
- Manual phase-1 checklist items `1.5`, `1.6`, `1.7` are marked complete in `plan.md`; diff evidence supports each claim (admin entry points, USER 403 gate tests, and non-prod-only bootstrap profile guard).

## Findings

### F1 — Dev admin credentials are predictable outside prod

- **Severity**: ⚠️ WARNING
- **Impact**: 🔬 HIGH — architectural stakes; think carefully before deciding
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/nextslope/config/DevAdminBootstrap.java:17
- **Detail**: The bootstrap uses fixed credentials and is enabled with `@Profile("!prod")`, which covers all non-prod environments, not only local dev. If a non-prod instance is exposed, takeover risk is high.
- **Fix A ⭐ Recommended**: Restrict bootstrap to explicit local/dev profile(s) and require an opt-in property for activation.
  - Strength: Keeps local convenience while removing accidental exposure from shared environments.
  - Tradeoff: Slightly more setup when onboarding local environments.
  - Confidence: HIGH — profile-scoped bootstraps are already the project pattern for environment safety.
  - Blind spot: Did not verify all deployment profile names currently used outside `prod`.
- **Fix B**: Keep `!prod` but generate one-time random password and print only once on startup.
  - Strength: Reduces default-credential exposure without changing profile wiring.
  - Tradeoff: Still allows bootstrap in non-local environments and relies on log hygiene.
  - Confidence: MEDIUM — safer than fixed credentials but weaker than strict profile scoping.
  - Blind spot: No guarantee logs are private in every environment.
- **Decision**: FIXED (Fix A)

### F2 — Active toggle has lost-update race window

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/nextslope/resort/ResortService.java:49
- **Detail**: `toggleActive` uses read-modify-write without concurrency control. Two near-simultaneous requests can collapse into one effective state change.
- **Fix**: Add optimistic locking (`@Version`) on `Resort` and handle lock conflicts in the toggle path (retry or explicit conflict response).
  - Strength: Provides deterministic behavior under concurrent toggles and protects other write paths too.
  - Tradeoff: Requires entity/migration/test adjustments and conflict handling decisions.
  - Confidence: MEDIUM — robust pattern, but conflict UX/handling needs a project-level choice.
  - Blind spot: No production contention data yet to quantify likelihood.
- **Decision**: FIXED

### F3 — Phase-1 review scope contains later-phase extras

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: src/main/java/com/nextslope/resort/ResortService.java:23
- **Detail**: Files in the phase-1 footprint now contain phase-2/3 behavior (form/edit/toggle flows), making phase-specific validation noisier and harder to reason about.
- **Fix**: Add a phase addendum in the plan (or progress notes) explicitly documenting cross-phase consolidation in shared files.
- **Decision**: FIXED

### F4 — Two phase-1 contract drifts are present

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/main/resources/templates/admin/resorts/list.html:2
- **Detail**: Phase 1 contract called for `xmlns:sec` in the admin list template, but the namespace is absent. Also, the contract said to log seeded credentials, while `DevAdminBootstrap` logs only account creation/skip events.
- **Fix**: Either align code to contract (`xmlns:sec` + explicit credential logging policy) or document intentional deviations in plan addendum.
- **Decision**: FIXED (namespace aligned + no-plaintext-credential logging policy documented)

### F5 — Landing page still duplicates shared layout includes

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/resources/templates/index.html:4
- **Detail**: `index.html` still inlines Bootstrap/HTMX includes instead of using `fragments/layout` head/scripts fragments used across layout-based pages; this increases drift risk when shared includes evolve.
- **Fix**: Refactor `index.html` to reuse shared layout fragments for head/scripts consistency.
- **Decision**: FIXED

