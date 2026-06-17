<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Persistence & Migration Baseline Implementation Plan

- **Plan**: `context/changes/persistence-migration-baseline/plan.md`
- **Mode**: Deep
- **Date**: 2026-06-17
- **Verdict**: SOUND
- **Findings**: 0 critical 3 warnings 1 observation

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | PASS |
| Plan Completeness | PASS |

## Grounding
6/6 paths check passed, 4/4 symbol checks passed, plan-brief consistency check passed.

## Findings

### F1 — Required prod Flyway secret lacks an explicit operational guardrail

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 1 + Phase 3 docs scope
- **Detail**: The plan required `SPRING_FLYWAY_URL` with fail-fast behavior but did not explicitly require a pre-deploy confirmation that the Render dashboard secret is set to Neon's DIRECT endpoint.
- **Fix A ⭐ Recommended**: Add explicit runbook/checklist verification in the Phase 1 manual contract and mirror it in Progress item 1.3.
  - Strength: Prevents CI-green but startup-failing deploys due to missing/misconfigured secret.
  - Tradeoff: Adds one manual checklist requirement.
  - Confidence: HIGH — directly grounded in current config and deployment behavior.
  - Blind spot: Repository cannot validate dashboard values directly.
- **Fix B**: Allow Flyway fallback to datasource URL.
  - Strength: Reduces hard-fail risk from missing secret.
  - Tradeoff: Reintroduces migration-through-pooler risk.
  - Confidence: MEDIUM — shifts risk rather than removing it.
  - Blind spot: Pooler migration behavior remains environment-dependent.
- **Decision**: FIXED via Fix A

### F2 — Canonical repository test pattern is heavier than current testing convention

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Lean Execution
- **Location**: Phase 2 test contract + Phase 3 AGENTS.md conventions
- **Detail**: The original wording could be read as making full-context Testcontainers tests the default for repository testing in future slices.
- **Fix A ⭐ Recommended**: Split guidance by intent: keep full-context Testcontainers as canonical for prod-engine migration verification, and keep slice-first testing for routine repository/domain cases.
  - Strength: Preserves migration confidence while avoiding unnecessary test heaviness.
  - Tradeoff: Slightly more nuance in documented conventions.
  - Confidence: HIGH — aligns with existing testing guidance and slice goals.
  - Blind spot: Future authors may still overuse full-context tests.
- **Fix B**: Make the canonical pattern `@DataJpaTest` + Testcontainers.
  - Strength: Lighter default test profile.
  - Tradeoff: More setup complexity for the foundational migration proof case.
  - Confidence: MEDIUM — feasible but less direct for full-wiring proof.
  - Blind spot: Needs additional behavior validation in this codebase.
- **Decision**: FIXED via Fix A

### F3 — Phase 3 docs task did not explicitly clean known stale AGENTS.md statements

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 3 (`AGENTS.md` contract)
- **Detail**: The docs contract added persistence guidance but did not explicitly require reconciling known contradictory baseline statements in the same pass.
- **Fix**: Extend the Phase 3 contract to require reconciling stale/contradictory `AGENTS.md` bullets in the same edit.
- **Decision**: FIXED

### F4 — Current-state wording could better distinguish explicit vs implicit local ddl behavior

- **Severity**: 👀 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Current State Analysis
- **Detail**: “No ddl-auto in local profile” could be misread without clarifying explicit-vs-implicit behavior.
- **Fix**: Clarify that local profile has no explicit `ddl-auto` property and embedded-database defaults apply implicitly.
- **Decision**: FIXED
