<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Code Review Agent

- **Plan**: `context/changes/code-review-agent/plan.md`
- **Mode**: Deep
- **Date**: 2026-08-07
- **Verdict**: SOUND (after triage)
- **Findings**: 1 critical, 7 warnings, 0 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | PASS |
| Plan Completeness | PASS |

## Grounding

8/8 referenced existing paths verified; 5 risky codebase claims checked; brief↔plan consistent; Progress↔Phases consistent. SDK and Zod claims checked against the published `@anthropic-ai/claude-agent-sdk@0.3.224` and `zod@4.4.3` packages.

## Findings

### F1 — Fixture coverage cannot satisfy its five-criterion contract

- **Severity**: ❌ CRITICAL
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: End-State Alignment
- **Location**: Phase 5 — Planted-defect fixture and expectations
- **Detail**: The fixture promises one defect per criterion, but its four mandatory defects cover only three distinct IDs: Flyway, ddl-auto, and access control. E2E is optional and constructor injection is absent. The broken fixture then “expects the rest,” which cannot be derived consistently.
- **Fix**: Make both constructor-injection and E2E defects mandatory, retain both useful Flyway sub-defects, and list exact expected/forbidden IDs for every fixture.
- **Decision**: FIXED — complete fixture coverage and explicit expectations

### F2 — The schema layers do not compose

- **Severity**: ⚠️ WARNING
- **Impact**: 🔬 HIGH — architectural stakes; think carefully before deciding
- **Dimension**: Architectural Fitness
- **Location**: Phase 2 SDK boundary; Phase 4 `review.json` contract
- **Detail**: `agent.ts` receives only a JSON Schema but must call Zod `safeParse`; a JSON Schema has no parser. Separately, `review.json` is said to match `verdictSchema` while also adding `passed`/`reasons`, so it no longer matches that schema. The downstream 10X-19 contract is therefore ambiguous.
- **Fix ⭐ Recommended**: Define separate agent-verdict and review-report schemas; give `runAgent<T>` a validator callback (or return `unknown`), then validate the enriched report before writing it.
  - Strength: Preserves the generic SDK boundary and gives 10X-19 one explicit, validated artifact contract.
  - Tradeoff: Adds a second schema and one orchestration step.
  - Confidence: HIGH — `structured_output` is explicitly `unknown` in the published SDK type.
  - Blind spot: Decide whether cost/model metadata belongs in `review.json`.
- **Decision**: FIXED — separate validated model-output and review-report schemas

### F3 — The authoritative verdict policy is undefined

- **Severity**: ⚠️ WARNING
- **Impact**: 🔬 HIGH — architectural stakes; think carefully before deciding
- **Dimension**: Plan Completeness
- **Location**: Phase 4 — Verdict policy
- **Detail**: The plan never defines the default `failOn` severity, severity ordering, minimum score, interaction between scores and findings, or score anchors. Implementers must invent the behavior that determines exit code 0 versus 3.
- **Fix A ⭐ Recommended**: Gate only on finding severity in this change; specify the default and ordering, and keep scores diagnostic until 10X-19 calibrates them.
  - Strength: Matches the existing `--fail-on` API and avoids making an uncalibrated probabilistic score authoritative.
  - Tradeoff: A low score without a finding will not block.
  - Confidence: HIGH — the plan already defers calibration to 10X-19.
  - Blind spot: 10X-19 must not assume scores drive this CLI’s exit code.
- **Fix B**: Define an exact combined score-and-severity policy now.
  - Strength: Uses both model outputs immediately.
  - Tradeoff: Makes an admitted uncalibrated score a blocking gate.
  - Confidence: MEDIUM — no real-PR evidence supports a cutoff yet.
  - Blind spot: Score stability across model versions remains unmeasured.
- **Decision**: FIXED — Fix A: severity-only gate; scores remain diagnostic

### F4 — Read-only enforcement ignores the SDK’s restrictive `tools` option

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Architectural Fitness
- **Location**: Phase 2 — SDK session options
- **Detail**: SDK 0.3.224 documents `tools` as the built-in-tool allowlist. The plan blocks Write/Edit/Bash but leaves the remaining default tool surface present. `permissionMode: "dontAsk"` reduces risk, but the capability boundary is less explicit than the plan claims.
- **Fix**: Set `tools` to the exact read-only set needed, retain `disallowedTools` as defense in depth, and assert the initialized tool list.
  - Strength: Capability posture becomes allowlist-based and testable.
  - Tradeoff: Native builds may lack dedicated search tools, reducing optional repo exploration.
  - Confidence: HIGH — confirmed in the published 0.3.224 declarations.
  - Blind spot: The minimal cross-platform read-only tool set needs a smoke run.
- **Decision**: FIXED — explicit read-only `tools` allowlist with defense in depth

### F5 — Two criteria overstate the repository’s actual baseline

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Architectural Fitness
- **Location**: Current State; Phase 3 — Review criteria
- **Detail**: Constructor injection is not uniformly Lombok: `AdminBootstrap`, `DevAdminBootstrap`, and `ResortSeedLoader` use valid explicit constructors. The canonical `HtmxSmokeE2eTests` also uses CSS fill/click interactions that conflict with the strict locator rule. Literal criteria can therefore false-positive against accepted code.
- **Fix**: Accept explicit constructor injection while rejecting field injection; document the E2E baseline deviations and score only newly added violating interaction lines, using the newer role-based locator pattern as the positive example.
  - Strength: Criteria enforce intended behavior without treating historical exceptions as the standard.
  - Tradeoff: Prompt wording becomes slightly more nuanced.
  - Confidence: HIGH — verified across production and all three E2E classes.
  - Blind spot: Whether to repair baseline E2E deviations belongs to a separate change.
- **Decision**: DISMISSED

### F6 — A diff can inject instructions into the reviewer prompt

- **Severity**: ⚠️ WARNING
- **Impact**: 🔬 HIGH — architectural stakes; think carefully before deciding
- **Dimension**: Blind Spots
- **Location**: Phase 3 — Prompt assembly; Phase 5 — Fixtures
- **Detail**: PR diff text is untrusted input, but the prompt contract only says to concatenate criteria and diff. A planted comment can instruct the model to ignore criteria and emit a clean verdict—the exact future CI bypass this package is meant to prevent.
- **Fix**: Delimit the diff as untrusted evidence, explicitly forbid following instructions inside it, and add an adversarial instruction to a defect fixture whose expected findings must still be returned.
  - Strength: Tests the main integrity boundary without adding another framework.
  - Tradeoff: Prompt-level defenses reduce rather than eliminate injection.
  - Confidence: HIGH — the attack surface follows directly from raw diff input.
  - Blind spot: Stronger isolation may require future model/eval work.
- **Decision**: FIXED — untrusted-data prompt boundary plus adversarial fixture payload

### F7 — `maxTurns` does not bound cost or run reproducibility

- **Severity**: ⚠️ WARNING
- **Impact**: 🔬 HIGH — architectural stakes; think carefully before deciding
- **Dimension**: Blind Spots
- **Location**: Phase 2 options; Performance Considerations
- **Detail**: Three turns can still contain an arbitrarily large diff and expensive first request. The plan also says “Sonnet-class” without pinning a model, so quality and cost can change with CLI defaults.
- **Fix**: Specify a model ID/alias policy, maximum diff size, and `maxBudgetUsd`; record model and budget outcome in verbose output or report metadata.
  - Strength: Creates enforceable cost bounds and reproducible fixture runs.
  - Tradeoff: Large PRs need an explicit “too large” result or chunking later.
  - Confidence: HIGH — SDK 0.3.224 exposes `model` and `maxBudgetUsd`.
  - Blind spot: The initial byte/token and dollar limits need empirical tuning.
- **Decision**: FIXED — pinned model, 200,000-byte input limit, and `$0.50` budget

### F8 — Harness and artifact lifecycle leave easy implementation traps

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 4 exit codes; Phase 5 verification harness
- **Detail**: Defect fixtures should make the CLI exit 3, but the harness does not say that 3 is an expected completed run rather than an execution failure. Generated `review.json`/`review.md` are also not covered by the proposed ignore rules when `--out` defaults to the package cwd.
- **Fix**: Treat 0/3 as completed runs and 1/2 as harness failures, parse the artifact for assertions, and ignore or redirect default generated outputs.
- **Decision**: FIXED — explicit exit semantics and isolated harness artifacts

## Triage Summary

- **Fixed**: F1, F2, F3 (Fix A), F4, F6, F7, F8
- **Dismissed**: F5
- **Post-triage verdict**: SOUND
