<!-- PLAN-REVIEW-REPORT -->
# Plan Review: CI/CD AI Code Review Implementation Plan

- **Plan**: `context/changes/ci-cd-code-review/plan.md`
- **Mode**: Deep
- **Date**: 2026-08-08
- **Verdict**: REVISE (after triage: SOUND — 9 fixed, 1 accepted)
- **Findings**: 5 critical, 5 warnings, 0 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | FAIL |
| Lean Execution | PASS |
| Architectural Fitness | WARNING |
| Blind Spots | FAIL |
| Plan Completeness | WARNING |

Overall is REVISE rather than RETHINK despite two FAILs: the architecture and
decisions are sound, and every finding has a concrete bounded fix. The failures
are localized to verification sequencing (F1, F4) and workflow-hygiene omissions
(F2, F3, F7, F8), not to the design.

## Grounding

11/11 paths ✓ (`.github/actions/` correctly absent — new), 7/7 symbols ✓
(`verdictJsonSchema`, `runStructuredSession`, `buildReviewPrompt`,
`DEFAULT_FAIL_ON`, `MAX_DIFF_BYTES`, `REVIEW_ROOT`, committed
`package-lock.json`), brief↔plan ✓.

Blast-radius sweep found no existing repo artifact referencing `review.yml`,
`.github/actions`, `promptfoo`, `ai-cr:*`, or `workflow_dispatch` — the change is
purely additive. Docs carrying forward-looking 10X-19 claims that Phase 7 should
reconcile: `packages/code-reviewer/AGENTS.md:25-26` ("`ci.yml` has no Node step
yet") and `:90-91`, `packages/code-reviewer/README.md:15-17,97-98`, root
`AGENTS.md:48,72`.

## Findings

### F1 — workflow_dispatch cannot be triggered from the change branch

- **Severity**: ❌ CRITICAL
- **Impact**: 🔬 HIGH — architectural stakes; think carefully before deciding
- **Dimension**: End-State Alignment
- **Location**: Phase 6, and Phase 2/3 Manual Verification
- **Detail**: GitHub only dispatches a workflow whose file exists on the default branch. GitHub's docs team escalated this to their SME team and confirmed it: if the file is absent from `main`, the versions on other branches cannot be triggered at all — not via the UI button, not via `gh workflow run --ref`. Since `review.yml` will live only on the change branch until the PR merges, and `.cursor/rules/git-workflow.mdc` forbids pushing to `main`, Phase 6's "cheap smoke run first, then one real PR" ordering is unexecutable. Phase 2's and Phase 3's only Manual Verification criteria both defer to "Phase 6's `workflow_dispatch` run", so three phases lose their verification story and the brief's "Verification strategy" decision loses its mechanism.
- **Fix A ⭐ Recommended**: Use a temporary branch-scoped `push:` trigger as the smoke path; keep `workflow_dispatch` in the shipped file so it starts working once it lands on `main`.
  - Strength: `push` runs the workflow file from the branch itself with no default-branch requirement, so it works today, needs no `main` push, and preserves the cheap-before-real cost ordering. A push run has no PR context, pairing naturally with F4's event guard.
  - Tradeoff: The temporary trigger must be removed before the PR merges — add it as an explicit Phase 7 step with its own Progress row.
  - Confidence: HIGH — the default-branch restriction is documented for `workflow_dispatch` only, never for `push`.
  - Blind spot: A push run still can't exercise the comment/label path, so those remain unproven until the real PR.
- **Fix B**: Land a skeleton `review.yml` on `main` via a small `chore/` PR first, then build the rest on the change branch.
  - Strength: Makes `workflow_dispatch` genuinely available (UI button included) for every later debugging iteration, which is what the cost decision wanted.
  - Tradeoff: Two PRs to `main` for one change, against the repo's one-PR-per-change rule; the skeleton is dead code on `main` until the real PR merges.
  - Confidence: MEDIUM — mechanically sound but conflicts with `git-workflow.mdc`.
  - Blind spot: Whether the partial workflow fires on unrelated PRs and posts noise in the interim.
- **Decision**: FIXED via Fix A (temporary branch-scoped `push:` trigger; Phase 7 removes it)

### F2 — `permissions` block omits `contents: read`, so checkout fails

- **Severity**: ❌ CRITICAL
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 3 — Contract, `permissions: pull-requests: write, issues: write`
- **Detail**: Declaring any scope in a `permissions` block sets every unlisted scope to `none`. With only `pull-requests` and `issues` listed, `contents` becomes `none` and `actions/checkout` fails on the first step of every run — the workflow never reaches diff computation, let alone the reviewer. `ci.yml:9-10` already declares `contents: read` for exactly this reason.
- **Fix**: Add `contents: read` to Phase 3's `permissions` contract alongside `pull-requests: write` and `issues: write`.
- **Decision**: FIXED

### F3 — `git merge-base` is called with one argument

- **Severity**: ❌ CRITICAL
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 3 — Contract, diff computation step
- **Detail**: The plan specifies `BASE_SHA=$(git merge-base origin/<base>) && git diff "$BASE_SHA" HEAD > "$RUNNER_TEMP/pr.diff"`. `git merge-base` requires at least two commits; with one it exits non-zero and, because of the `&&` chain, the diff file is never created — so the composite action fails on a missing `--diff-file` and reports `invalid_input`, making a shell bug look like a reviewer problem.
- **Fix**: `BASE_SHA=$(git merge-base "origin/$BASE" HEAD)`, and open the step with `set -euo pipefail` instead of `&&`-chaining so a merge-base failure fails the step loudly rather than producing an empty diff.
- **Decision**: FIXED

### F4 — Comment and label steps are unguarded, so the smoke run dies on them

- **Severity**: ❌ CRITICAL
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: End-State Alignment
- **Location**: Phase 4 — Contract (`if: always()` steps)
- **Detail**: Phase 3 deliberately adds a `workflow_dispatch` trigger for a PR-free smoke run, but Phase 4 attaches the comment and label steps with only `if: always()` and no event-type guard. On a non-PR run there is no `github.event.pull_request.number`, so `github.rest.issues.createComment` and `addLabels` are called with an empty `issue_number` and throw. The two phases contradict each other: the run designed to be the cheap first proof is the one that cannot finish.
- **Fix**: Gate both side-effect steps on a PR context (`if: always() && github.event_name == 'pull_request'`), and state in Phases 3 and 6 that a non-PR run proves only diff computation plus the action's `verdict`/`report-dir` outputs. To keep the smoke run useful, have the non-PR path write `review.md` to `$GITHUB_STEP_SUMMARY`.
- **Decision**: FIXED

### F5 — Progress row 4.4 has no matching Phase 4 success criterion

- **Severity**: ❌ CRITICAL
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Progress → Phase 4 (line 557) vs Phase 4 Manual Verification
- **Detail**: Phase 4's Manual Verification lists two bullets but Progress carries three rows (4.2, 4.3, 4.4). Row 4.4 ("Each comment's provenance footer names the commit SHA it reviewed") has no counterpart in the Phase block, breaking the one-to-one Progress↔Phase contract `/10x-implement` parses. The footer is a real Phase 4 requirement, so the Phase block is the side that's missing it. Every other phase maps cleanly.
- **Fix**: Add "Each comment's provenance footer names the commit SHA it reviewed" to Phase 4's Manual Verification list.
- **Decision**: FIXED

### F6 — Neither suggested technique can capture the CLI's numeric exit code

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Plan Completeness
- **Location**: Phase 2 — Contract, final paragraph
- **Detail**: The plan offers `continue-on-error: true` or "`|| true` plus `$?`". Neither yields the number: `continue-on-error` exposes only `steps.<id>.outcome` (`success`/`failure`), and `cmd || true` replaces the child's status with 0 before `$?` can be read. Both collapse 1, 2 and 3 into one failure bucket — precisely the distinction the "a missing verdict must not produce a misleading pass/fail label" decision depends on.
- **Fix**: Capture explicitly in one `shell: bash` step — `set +e; npm run review -- --diff-file "$DIFF" --out "$OUT"; code=$?; set -e; echo "exit-code=$code" >> "$GITHUB_OUTPUT"` — then map to `verdict` in a following step. Also specify the unknown-code branch: anything outside 0–3 maps to `no_result`.
- **Decision**: ACCEPTED — risk accepted, to be handled during implementation; recorded in `plan-brief.md` Open Risks

### F7 — No `timeout-minutes` on the review job

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 3 — Contract; Performance Considerations
- **Detail**: Performance Considerations reasons only about API spend ($0.50/run, 3 turns). That covers Anthropic billing but not GitHub billing: with no `timeout-minutes` the job inherits the 6-hour default, and a session that hangs before the SDK's budget guard trips burns runner minutes against this private repo's monthly quota. `ci.yml:15` sets `timeout-minutes: 10` for the far more predictable Gradle job.
- **Fix**: Add `timeout-minutes: 15` to the review job in Phase 3's contract and note the runner-minute exposure in Performance Considerations.
- **Decision**: FIXED

### F8 — Nothing provisions the three `ai-cr:*` labels, and removal will 404

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 2 (secret provisioning), Phase 4 (label lifecycle)
- **Detail**: Phase 2 provisions `ANTHROPIC_API_KEY` as a manual human step but no phase creates the labels. `ai-cr:review` must already exist for a maintainer to attach it, so the Desired End State's one-shot retry button has nothing to click on day one; and `github.rest.issues.removeLabel` throws 404 when the label isn't currently on the PR, which will fail the first `passed` run unless the call tolerates it. The plan says "remove `ai-cr:failed` if present" but never says how "if present" is determined.
- **Fix**: Add a Phase 2 manual step creating all three labels (`gh label create ai-cr:passed|ai-cr:failed|ai-cr:review`) next to the secret step, and specify in Phase 4 that removals either check `github.event.pull_request.labels` first or wrap `removeLabel` in try/catch swallowing 404.
- **Decision**: FIXED

### F9 — Phase 5's `npm run typecheck` criterion cannot fail

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 5 Automated Verification (5.1)
- **Detail**: `packages/code-reviewer/tsconfig.json` sets `"include": ["src/**/*.ts", "scripts/**/*.ts", "test/**/*.ts"]` with no `allowJs` or `checkJs`, so `tsc --noEmit` never sees `promptfoo/provider.js`. 5.1 passes whether the provider is correct or completely broken, and 5.2 (`npm test`) is equally blind since no new tests are added. Phase 5 therefore has no automated gate on its riskiest artifact — the one the brief already flags as unproven.
- **Fix**: Replace 5.1 with a criterion that can actually fail — `node --check promptfoo/provider.js` plus an import smoke (`node --import tsx/esm -e "await import('./promptfoo/provider.js')"`), which is also the cheapest way to settle the ESM/`tsx` loading question the brief lists as an open risk.
- **Decision**: FIXED

### F10 — Phase 5's provider is under-specified in the two ways that decide whether the eval means anything

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Architectural Fitness
- **Location**: Phase 5 — Changes 2 and 3
- **Detail**: Phase 5's rationale is that the custom provider evaluates "the actual agent that runs on PRs", but the contract says only that it calls `runStructuredSession` "with the same `jsonSchema`/`validate` pairing `cli.ts` uses" — it never mentions `cwd`. `cli.ts:287` passes `cwd: REVIEW_ROOT` (the repo-root `src/` tree, deliberately not the repo root, per `cli.ts:61-73`); `agent.ts:146` omits `cwd` entirely when undefined, so a provider that forgets it gives the eval agent a different read scope than the PR agent and the stated rationale becomes false. Separately, the `is-json` schema file is left as an unresolved either/or ("a small build step or a checked-in snapshot").
- **Fix**: Pin the provider's session options to the CLI's — pass the same repo-root `src/` `cwd`, and leave `model`/`maxTurns`/`maxBudgetUsd` unset so `agent.ts`'s defaults apply, matching `cli.ts:275-288` and the "keep CLI defaults" decision. Resolve the schema question in favor of generating the JSON file from `verdictJsonSchema` inside the `promptfoo` npm script rather than checking in a snapshot: `src/schema.ts` is the single source of truth and its `draft-07` emission is guarded by a dedicated regression test (Trap 1 in the package's `AGENTS.md`) that a second copy would silently escape.
- **Decision**: FIXED
