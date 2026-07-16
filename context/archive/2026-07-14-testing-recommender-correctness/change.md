---
change_id: testing-recommender-correctness
title: Recommender correctness suite — test-plan rollout Phase 2
status: archived
created: 2026-07-14
updated: 2026-07-16
archived_at: 2026-07-16T21:27:36Z
---

## Notes

Open a change folder for rollout Phase 2 of context/foundation/test-plan.md: "Recommender correctness suite".
Risks covered: #1 rationale lies (truthfulness), #2 incomplete matching (all axes wired), #3 visited/new-only hard filter broken.
Test types planned: unit + integration + mutation (PIT, scoped to com.nextslope.recommendation.* only — never repo-wide).
Risk response intent (from test-plan.md §2 Risk Response Guidance + §6.5):
- #1: prove the "why this matched you" line names a preference axis the user actually set AND corresponds to the matched resort's real scoring reasons. Derive the expected axis from the user's input, never from the rationale generator's own output (oracle-problem tautology). A flow-level browser guardrail already shipped (src/e2eTest/java/com/nextslope/e2e/RationaleTruthfulnessE2eTests.java, PR #32); this phase covers the deeper unit/integration truthfulness-vs-scoring-internals half that test deliberately left out.
- #2: prove that changing exactly one axis (region / novelty / difficulty / experience) in isolation changes the candidate set or ordering — every axis actually wired into scoring. Anti-patterns: asserting exact score numbers copied from the scorer; over-mocking so an axis never executes.
- #3: prove a new-only user with a visited resort never sees it while a revisit-okay user still can; cover the empty/all-visited edge, not just the happy path. A flow-level browser guardrail for the revisit-okay-vs-new-only happy path already shipped (src/e2eTest/java/com/nextslope/e2e/NewOnlyGuardrailE2eTests.java, PR #31); focus on the edge cases and the underlying hard-filter unit coverage.
Pre-verified state (orchestrator analysis, 2026-07-14, clean main @ 74ec228) — research must confirm, not re-derive:
- The PIT mutation gate is ALREADY WIRED, contrary to §4/§6.5 "not wired today": build.gradle has a pitest {} block (plugin 1.19.0, junit5PluginVersion 1.2.3, targetClasses/targetTests com.nextslope.recommendation.*, excludedTestClasses RecommendationOwnershipIntegrationTests, mutationThreshold 90) and .github/workflows/ci.yml runs ./gradlew pitest as a blocking step. Reconcile the stale test-plan.md §4/§6.5 language during research, before finalizing the plan.
- Substantial recommender coverage already exists from the S-05 slice: RecommendationServiceTests (region filter, new-only excludes visited, revisit path skips visited lookup, sparse explanations incl. zero survivors, exactly-three, score-driven ranking, tie-break chain), WeightedDistanceScorerTests, RationaleBuilderTests (axis selection, thresholds, truthful fallback), RecommendationOwnershipIntegrationTests. Per §3 "Sequencing reality", this phase narrows to the gaps that remain — map existing tests to Risks #1–#3 and target only true gaps (single-axis differential proof per §6.5, independent-oracle rationale assertions vs. real scoring reasons, all-visited/empty edges).
- No Linear issue exists yet for this rollout phase (searched team 10xNextSlope) — ask the user whether to create one before cutting the branch, since branch naming (feature/<issue-id>-<slug>) and status sync depend on it.
After creating the folder, follow the downstream continuation rule (suggest /10x-research next).
