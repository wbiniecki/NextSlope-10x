<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Admin Resort Management (S-06) Implementation Plan

- **Plan**: `context/changes/admin-resort-management/plan.md`
- **Mode**: Deep (re-review)
- **Date**: 2026-07-02
- **Verdict**: SOUND
- **Findings**: 0 critical, 0 warnings, 0 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | PASS |
| Plan Completeness | PASS |

## Grounding

10/10 paths ✓, 3/3 symbols ✓ (`findByActiveTrueOrderByCountryAscNameAsc`, `findByIdAndActiveTrue`, `getDifficultyMix`), brief↔plan ✓, Progress↔Phase mechanical contract ✓ (3 phases, all success-criteria bullets map 1:1 to Progress rows).

## Prior review fixes — verified

- **F1 (resync overwrite)**: Documented in `Critical Implementation Details` (plan.md:111–117); matches `ResortSeedLoader.copyFacts` behavior (`active` preserved, facts overwritten) and existing `ResortSeedLoaderTests.resyncPreservesAdminDeactivatedActiveFlagWhileUpdatingFacts`.
- **F2 (not-found exception)**: `com.nextslope.resort.ResortNotFoundException` listed as Phase 2 item #2; `loadForm`/`toggleActive` throw it; GET edit and toggle handlers map to `ResponseStatusException(NOT_FOUND)` — correct package boundary vs `visited.ResortNotFoundException`.

## Deep verification summary

All five riskiest claims confirmed against live code:

1. **Security matcher** — insertion point after `permitAll`, before `anyRequest().authenticated()` is correct; `hasRole("ADMIN")` aligns with `AppUserDetailsService` `ROLE_ADMIN` mapping; URL-level gate will produce real USER→403 in `@WebMvcTest` without method security.
2. **Resync interaction** — opt-in, default off; overwrites CSV facts except `active` (and id/externalId/audit).
3. **Edit preservation** — ~25 entity columns; form covers six PRD facts + optional `externalId`; seed data populates unmanaged columns.
4. **HTMX toggle** — `VisitedController` + `visitedToggle` fragment + `layout.html` `htmx:afterSwap` branch is a direct template for Phase 3 `active-toggle`.
5. **Deactivation wired** — all user-facing reads filter via `findByActiveTrue*`; unmark skips active check (FR-013 proven by `VisitedResortOwnershipIntegrationTests`).

Blast radius: introducing `ResortService` does not require refactoring existing read controllers; they keep using `ResortRepository` directly. No contract-surface file in repo.

## Findings

None. Plan is actionable, internally consistent, and grounded in the codebase. Safe to implement.
