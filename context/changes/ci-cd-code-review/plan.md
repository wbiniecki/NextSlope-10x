# CI/CD AI Code Review Implementation Plan

## Overview

Wire the already-built `packages/code-reviewer` CLI into GitHub Actions so every pull request to
`main` gets reviewed automatically: a composite action runs the CLI against the PR's diff, and a
consumer workflow turns the verdict into a PR comment plus a pass/fail label. A promptfoo eval
suite is added alongside so future prompt/model changes are measured rather than guessed at. This
is Linear [10X-19](https://linear.app/10xnextslope/issue/10X-19/cicd-ai-code-review-on-every-pr-to-main-gha-workflow-composite-action),
blocked-by (and now unblocked by) [10X-18](https://linear.app/10xnextslope/issue/10X-18/tooling-claude-agent-sdk-code-review-agent-as-an-independent).

## Current State Analysis

`packages/code-reviewer` exists and is fully built (archived change `code-review-agent`,
[10X-18](https://linear.app/10xnextslope/issue/10X-18/tooling-claude-agent-sdk-code-review-agent-as-an-independent)):
a CLI (`npm run review -- --diff-file <path>`) that reads a unified diff, runs one Claude Agent SDK
session scoped to `src/` read-only access, and writes `review.json` (schema-validated verdict +
deterministic gate) and `review.md` (GitHub-flavored markdown, meant to be pasted into a PR comment
verbatim — see `packages/code-reviewer/src/render.ts:1-9`). Exit codes are a documented
cross-change contract (`packages/code-reviewer/src/cli.ts:1-19`):

| Code | Meaning |
|---|---|
| `0` | Passed — no blocking findings |
| `1` | Invalid invocation/input, or a startup/auth failure before a session ran |
| `2` | A session started but produced no usable result |
| `3` | Blocked — findings at or above `--fail-on` (default `high`) |

`.github/workflows/ci.yml` is the only workflow today: four deterministic Gradle gates (test, PIT,
Playwright install, e2e), every third-party action pinned to a full commit SHA with a version
comment (e.g. `actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5 # v4.3.1`). There is no
`.github/actions/` directory and no Node step in CI yet. `packages/code-reviewer/package.json` has
no `promptfoo` dependency and no `npm run promptfoo` script.

## Desired End State

A PR opened against `main` triggers `.github/workflows/review.yml`, which computes the PR's diff,
runs `.github/actions/ai-reviewer` (the composite action wrapping the CLI), posts a new PR comment
with the rendered `review.md`, and sets `ai-cr:passed` or `ai-cr:failed` (removing whichever is
stale). Adding the `ai-cr:review` label re-triggers a review and the label is consumed
(removed) once that run starts. A maintainer can also fire a cheap `workflow_dispatch` smoke run to
prove the plumbing without opening a PR. `packages/code-reviewer` gains a promptfoo suite
(`npm run promptfoo`) that scores the fixtures against `is-json`, `llm-rubric`, and `javascript`
assertions — run manually, never wired into CI, matching the existing `npm run verify` cost
posture.

Verified by: a real draft PR against this branch showing the comment and label appear, a deliberate
`ai-cr:review` retrant that re-triggers and swaps the label, and `npm run promptfoo` producing a
pass/fail table locally. The cheap pre-PR smoke run uses a temporary branch-scoped `push:` trigger
rather than `workflow_dispatch`, because the latter only fires for workflow files already present on
the default branch (Phase 6); the `workflow_dispatch` entry point ships anyway and becomes usable to
maintainers on merge.

### Key Discoveries:

- `packages/code-reviewer/src/render.ts:1-9` states outright that Linear 10X-19 posts `review.md`
  as a PR comment body verbatim — the composite action must expose the rendered file, not just a
  status code, so its contract needs a `report-dir` output alongside `verdict`.
- `packages/code-reviewer/AGENTS.md` documents `npm ci` as already CI-ready ("the lockfile is
  committed so the `npm ci` that 10X-19 will add to CI resolves reproducibly") and warns never to
  pass `--omit=optional`, since the native Claude Code binary ships as a per-platform optional
  dependency.
- `packages/code-reviewer/src/cli.ts:56-58` and `verdict.ts:14-15` fix `DEFAULT_FAIL_ON = "high"`
  and the exit-code mapping; per the "keep CI defaults" decision, the composite action passes no
  override flags, so CI and local runs score identically.
- `.github/workflows/ci.yml` pins every action to a commit SHA with a version comment — `review.yml`
  and any actions it uses (`actions/checkout`, `actions/setup-node`, `actions/github-script`) must
  follow the same convention.
- Diffs must never go through `$GITHUB_OUTPUT` (1 MB cap, multi-line risk) — write to a file under
  `$RUNNER_TEMP` instead, per [GitHub's workflow-commands docs](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-commands#setting-an-output-parameter).
- `is-json`'s `value` accepts a JSON Schema (inline or `file://`), so promptfoo can validate the
  custom provider's output directly against `packages/code-reviewer/src/schema.ts`'s
  `verdictJsonSchema` by writing it to a file the config references.

## What We're NOT Doing

- Not building the promptfoo suite as a GitHub Actions workflow — it stays a manual, local
  `npm run promptfoo` script, matching the `npm run verify` cost posture (`packages/code-reviewer/AGENTS.md`:
  "costs money... never a watch task"). Linear 10X-19's scope lists the config file, not a workflow.
- Not adopting `anthropics/claude-code-action@v1` (the documented escape hatch) — the hand-rolled
  composite action is being built to preserve the schema-validated `review.json`/exit-code contract.
- Not making `review.yml` a required check — it stays a separate, non-blocking workflow from
  `ci.yml` until the promptfoo suite calibrates confidence in verdict quality (Linear 10X-19,
  "Deliberately a separate workflow from `ci.yml`").
- Not changing `review.json`'s schema, the CLI's flags, or the exit-code contract. Every consumer
  need this change has is already served by today's shape: the label decision reads `passed` (added
  by `code-review-agent` precisely so a consumer never re-derives the gate from `findings` plus a
  duplicated threshold), the comment body is `review.md` verbatim, and promptfoo's assertions read
  `criteria[].score` while taking cost/latency straight off `runStructuredSession`'s return value.
  Keeping the producer untouched makes this change a pure integration, so a failure during Phases
  3-6 can only be workflow-side. **Known limitation accepted by this decision:** `review.json`
  carries no run provenance — the resolved model, cost, and turn count exist on the session result
  but only reach `--verbose` stdout (`packages/code-reviewer/src/cli.ts:296-297`), never either
  artifact. Since the "keep CLI defaults" decision means the workflow passes no `--model`, the
  workflow cannot name the model that produced a verdict; PR comments carry the reviewed commit SHA
  (Phase 4, workflow-side, no schema change) but not the model. Revisit when promptfoo starts moving
  the default model and an unattributed verdict becomes genuinely ambiguous.
- Not adding path filters to exclude lockfiles/build artifacts from the reviewed diff — the full
  merge-base diff is used, relying on the CLI's existing `MAX_DIFF_BYTES` guard.
- Not calibrating `--fail-on` or any score threshold against real PR history — the shipped default
  stays in place, as already documented as "uncalibrated" in the `code-review-agent` plan brief.

## Implementation Approach

The composite action (`.github/actions/ai-reviewer`) is a thin, self-contained wrapper: it does its
own `actions/setup-node`, its own `npm ci --prefix packages/code-reviewer`, runs the CLI against an
input diff-file, and exposes two outputs (`verdict`, `report-dir`) derived from the CLI's exit code
and `--out` directory. This keeps `review.yml` from having to know anything about Node versions or
npm — it only knows about GitHub's PR/label/comment surface. `review.yml` owns everything
GitHub-specific: trigger shape, diff computation, concurrency, and the comment/label side-effects,
reading the action's two outputs to decide what to post. The promptfoo suite is added as a sibling
concern inside `packages/code-reviewer` with no wiring back into either workflow, since it evaluates
prompt/model quality rather than gating any specific PR.

## Critical Implementation Details

**Composite action output contract.** Linear 10X-19 names one output (`verdict`), but `render.ts`'s
own doc comment establishes that the PR comment body is `review.md` verbatim, which the action
alone cannot expose through a single status string. The action must additionally output
`report-dir` (the directory `--out` wrote `review.json`/`review.md` into, resolved to
`${{ runner.temp }}/ai-reviewer` inside the action so callers don't have to invent a path). Map the
CLI's exit code to `verdict` as one of `passed` (0), `invalid_input` (1), `no_result` (2), or
`blocked` (3) — a string the workflow branches on for both the comment tone and the label decision.
Exit codes `1` and `2` both mean "no real verdict was produced," so neither should receive a
pass/fail label (per the earlier decision extending the exit-1 handling to exit-2 for the same
reason): post an explanatory comment instead and leave existing labels alone.

**Label lifecycle ordering.** The label-swap step must run with `if: always()` after the review
step so a label update still happens even if a later step (e.g. the comment post) fails, and the
`ai-cr:review` removal must happen as its own early step gated on
`github.event.action == 'labeled'` — removing it before the review runs (not after) means a
maintainer immediately sees the one-shot label consumed rather than wondering if their click
registered.

## Phase 1: Requirements Document

### Overview

Write the `requirements.md` Linear 10X-19 item 1 asks for, capturing the settled design before any
YAML exists, so it can be checked against later.

### Changes Required:

#### 1. Requirements document

**File**: `context/changes/ci-cd-code-review/requirements.md`

**Intent**: Document the concept (AI review on every PR), inputs (diff, PR title/body, API key),
the five review criteria (by reference to `packages/code-reviewer/prompts/criteria.md`), the two
side-effects (comment, label), and the retry behavior (`ai-cr:review` label), as the durable spec
for this workflow — the artifact a future reviewer or AI agent checks the shipped YAML against.

**Contract**: A markdown document with sections: Concept, Inputs, Criteria (reference, not
duplicate), Side-effects, Retry behavior, Non-goals (link to this plan's "What We're NOT Doing").

### Success Criteria:

#### Automated Verification:

- File exists and is valid markdown: `test -f context/changes/ci-cd-code-review/requirements.md`

#### Manual Verification:

- Requirements doc reads as a standalone spec someone could implement from without the Linear issue open

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Secret Provisioning & Composite Action

### Overview

Provision the `ANTHROPIC_API_KEY` repo secret and build the self-contained composite action that
wraps the CLI.

### Changes Required:

#### 1. Repository secret

**File**: N/A (GitHub repo settings, done manually by the human — this plan cannot script it)

**Intent**: `ANTHROPIC_API_KEY` must exist as a repo secret before any workflow run can authenticate
the SDK's bundled native binary.

**Contract**: Repo Settings → Secrets and variables → Actions → New repository secret, named
`ANTHROPIC_API_KEY`.

#### 2. Repository labels

**File**: N/A (GitHub repo settings, done manually by the human alongside the secret)

**Intent**: All three labels must exist before the workflow's first run. `ai-cr:review` in particular
is a button a maintainer has to be able to attach, so it cannot be left to on-demand creation by
`addLabels`; and pre-creating the pair means they get deliberate colors rather than the arbitrary one
the API assigns.

**Contract**: `gh label create ai-cr:passed`, `gh label create ai-cr:failed`,
`gh label create ai-cr:review` (with sensible colors/descriptions).

#### 3. Composite action definition

**File**: `.github/actions/ai-reviewer/action.yml`

**Intent**: Self-contained wrapper: sets up Node, installs `packages/code-reviewer`'s dependencies
via `npm ci` (never `--omit=optional`, per the package's own `AGENTS.md`), runs the CLI against the
supplied diff file, and maps its exit code to the action's declared outputs.

**Contract**: `runs.using: "composite"`. Inputs: `api-key` (required), `diff-file` (required, a
path), `pr-title` (optional, unused by the CLI today but declared per Linear 10X-19 item 3 for
forward compatibility — not yet wired into the prompt), `pr-body` (optional, same). Outputs:
`verdict` (one of `passed`/`invalid_input`/`no_result`/`blocked`, mapped from the CLI's exit code —
0/1/2/3 respectively) and `report-dir` (the directory containing `review.json`/`review.md`, fixed
to `${{ runner.temp }}/ai-reviewer` so callers don't need to pass one in). Every `run:` step needs
`shell: bash`. The `npm ci` and CLI invocation steps use
`working-directory: ${{ github.action_path }}/../../../packages/code-reviewer` (the action lives
three directories below repo root) or an equivalent `--prefix` flag — resolve the exact relative
path against where `.github/actions/ai-reviewer` actually sits. Pass `ANTHROPIC_API_KEY` as an
environment variable to the CLI step (`env: ANTHROPIC_API_KEY: ${{ inputs.api-key }}`), never via
`options.env` inside the SDK (already handled correctly by `agent.ts` — nothing to change there).
The exit-code-to-`verdict` mapping step must not fail the action step itself on a non-zero exit (use
`continue-on-error: true` on the CLI-invocation step, or capture the exit code explicitly with
`|| true` plus `$?`), since exit codes 1–3 are meaningful outcomes for the calling workflow to react
to, not action failures.

### Success Criteria:

#### Automated Verification:

- `action.yml` is valid YAML: `python3 -c "import yaml,sys; yaml.safe_load(open('.github/actions/ai-reviewer/action.yml'))"`
- Third-party actions used inside it are pinned to a commit SHA with a version comment, matching `ci.yml`'s convention (manual grep check)

#### Manual Verification:

- A local `act`-style or manual dry run (or Phase 6's push-triggered smoke run) confirms `npm ci` resolves inside `packages/code-reviewer` and the CLI runs to completion against a real diff file

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: Consumer Workflow

### Overview

Build `.github/workflows/review.yml`: the trigger shape, diff computation, concurrency, and the
call into the composite action from Phase 2.

### Changes Required:

#### 1. Consumer workflow

**File**: `.github/workflows/review.yml`

**Intent**: A separate, non-required workflow (deliberately not folded into `ci.yml`, since an LLM
verdict is probabilistic) that computes the PR's diff and invokes the composite action.

**Contract**: `on.pull_request` with `branches: [main]` and
`types: [opened, synchronize, reopened, labeled]`; `on.workflow_dispatch` with a required `ref`
input and an optional `base` input defaulting to `main`; and — **temporarily, for this change's own
smoke runs only** — `on.push` with `branches: ['feature/10x-19-*']`. The `push` trigger exists
because `workflow_dispatch` only fires for workflow files that already exist on the default branch,
so it cannot be used before this change merges (see Phase 6); Phase 7 removes it. A job-level `if`
skips non-`ai-cr:review` label events (`github.event.action != 'labeled' || github.event.label.name == 'ai-cr:review'`).
`permissions: contents: read, pull-requests: write, issues: write` (workflow- or job-level) —
declaring any scope sets every unlisted scope to `none`, so `contents: read` is required or
`actions/checkout` fails on the first step of every run; this matches `ci.yml`'s existing block.
`timeout-minutes: 15` on the job: without it the job inherits the 6-hour default, and a session that
hangs before the SDK's own budget guard trips would burn this private repo's monthly runner-minute
quota.
`concurrency: group: ${{ github.workflow }}-${{ github.event.pull_request.number || github.ref }}, cancel-in-progress: true`.
Steps: `actions/checkout` with `fetch-depth: 0` and a `ref` that branches on `github.event_name`
(PR head SHA, the `workflow_dispatch` input, or the pushed SHA); a `shell: bash` step opening with
`set -euo pipefail` and computing `BASE_SHA=$(git merge-base "origin/$BASE" HEAD)` followed by
`git diff "$BASE_SHA" HEAD > "$RUNNER_TEMP/pr.diff"` — `git merge-base` takes **two** commits, and
`set -euo pipefail` rather than `&&`-chaining so a merge-base failure fails the step loudly instead
of leaving an absent diff the action would then report as `invalid_input` (a shell bug masquerading
as a reviewer problem). The base ref likewise branches on `github.event_name`. Then
`uses: ./.github/actions/ai-reviewer` with
`diff-file: ${{ runner.temp }}/pr.diff`, `api-key: ${{ secrets.ANTHROPIC_API_KEY }}`,
`pr-title`/`pr-body` from `github.event.pull_request.title`/`.body` (empty string on
`workflow_dispatch` and `push`). All third-party actions pinned to a commit SHA with a version
comment.

### Success Criteria:

#### Automated Verification:

- Workflow file is valid YAML: `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/review.yml'))"`
- `ci.yml` is untouched: `git diff --stat -- .github/workflows/ci.yml` produces no output

#### Manual Verification:

- Phase 6's push-triggered smoke run confirms the diff file is non-empty and matches the expected ref's changes

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 4: PR Comment & Label Side-Effects

### Overview

Turn the composite action's `verdict`/`report-dir` outputs into the two side-effects Linear 10X-19
asks for.

### Changes Required:

#### 1. Comment and label steps

**File**: `.github/workflows/review.yml` (extends Phase 3's job)

**Intent**: Post a new PR comment containing `review.md`'s contents on every run (no upsert, per
the confirmed decision), and swap the `ai-cr:passed`/`ai-cr:failed` label pair based on `verdict`,
consuming `ai-cr:review` as a one-shot retry trigger.

**Contract**: Every step in this phase is additionally gated on `github.event_name == 'pull_request'`
(i.e. `if: always() && github.event_name == 'pull_request'` on the late steps). Both the comment and
label APIs need an `issue_number`, which does not exist on a `workflow_dispatch` or `push` run, so
without this guard the very run Phase 6 uses as its cheap first proof would throw in its side-effect
steps. So that a non-PR run is still informative, the review step writes `review.md` to
`$GITHUB_STEP_SUMMARY` — that is where the smoke run's rendered report is read.

An early step (before the review runs), gated on
`github.event.action == 'labeled' && github.event.label.name == 'ai-cr:review'`, removes that label
via `actions/github-script` (`github.rest.issues.removeLabel`) or `gh pr edit --remove-label`. A
late step, `if: always()` after the composite action call, branches on `verdict`: for `passed`, add
`ai-cr:passed` and remove `ai-cr:failed` if present; for `blocked`, the reverse; for
`invalid_input`/`no_result`, touch neither label. Every removal must tolerate absence —
`github.rest.issues.removeLabel` throws 404 when the label is not currently on the PR, which is the
normal case on a first `passed` run — so either check `github.event.pull_request.labels` before
calling or wrap the call in try/catch swallowing 404. A separate `if: always()` comment step reads
`${{ steps.<review-id>.outputs.report-dir }}/review.md` when `verdict` is `passed` or `blocked`, or
composes a short explanatory sentence referencing the CLI's own exit-code meaning when it's
`invalid_input`/`no_result`, and posts it via `actions/github-script`'s
`github.rest.issues.createComment` (always create, never look up prior comments, per the confirmed
decision). Append a one-line provenance footer naming the reviewed commit SHA
(`github.event.pull_request.head.sha`, or the resolved `ref` on `workflow_dispatch`) below the
markdown body — with a new comment per push, the footer is what tells a reader which commit a given
comment describes. The footer is composed workflow-side and deliberately does not name the model,
since `review.json` carries no provenance and this change leaves the producer untouched (see "What
We're NOT Doing").

### Success Criteria:

#### Automated Verification:

- Workflow file remains valid YAML after this phase's additions (same check as Phase 3)

#### Manual Verification:

- Phase 6's real draft PR shows exactly one label (`ai-cr:passed` or `ai-cr:failed`, never both) and exactly one new comment per push
- Adding `ai-cr:review` to that PR re-triggers a run and the label disappears immediately
- Each comment's provenance footer names the commit SHA it reviewed

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 5: Promptfoo Eval Suite

### Overview

Add a manually-run promptfoo suite inside `packages/code-reviewer` that measures review quality
against the existing fixtures, without wiring it into either GitHub Actions workflow.

### Changes Required:

#### 1. Promptfoo dependency and script

**File**: `packages/code-reviewer/package.json`

**Intent**: Add `promptfoo` as a devDependency and an `npm run promptfoo` script, mirroring the
existing `npm run verify` cost posture (real API calls, deliberate action only).

**Contract**: `"promptfoo": "eval -c promptfoo/promptfooconfig.yaml"` (or equivalent), devDependency
pinned to the latest stable major.

#### 2. Promptfoo config

**File**: `packages/code-reviewer/promptfoo/promptfooconfig.yaml`

**Intent**: Two-layer comparison per Linear 10X-19 item 6: promptfoo's native `anthropic:` provider
for cheap side-by-side model comparison (raw model, no agent scaffolding), and the custom provider
below for the actual regression gate against the agent that runs on PRs.

**Contract**: `providers:` lists at least one `anthropic:messages:<model-id>` entry and
`file://provider.js` (the custom provider). `prompts:` derived from
`packages/code-reviewer/src/prompt.ts`'s `buildReviewPrompt` output for each fixture. `tests:` one
entry per fixture in `packages/code-reviewer/fixtures/`, with `vars.expectedCriteria` sourced from
`fixtures/expectations.json`. `defaultTest.assert` includes: `is-json` with `value` pointing at a
JSON file holding `verdictJsonSchema` (the custom provider's raw-string output must be valid JSON
against that schema). **Generate that file** from `src/schema.ts` inside the `promptfoo` npm script
(a tiny `scripts/emit-schema.ts` run before `promptfoo eval`, writing to a gitignored path) rather
than checking in a snapshot: `src/schema.ts` is the single source of truth and its `draft-07`
emission is guarded by a dedicated regression test (Trap 1 in the package's `AGENTS.md`), which a
second checked-in copy would silently escape as the schema evolves. Also
`llm-rubric` checking that findings' justifications name the actual planted defect; `javascript`
parsing the provider's JSON output and asserting the planted-vulnerability fixture's relevant
criterion score falls below a documented threshold constant (mirroring `DEFAULT_FAIL_ON`'s
role — diagnostic, not the CLI's own gate).

#### 3. Custom provider

**File**: `packages/code-reviewer/promptfoo/provider.js`

**Intent**: Wrap `runStructuredSession` from `src/agent.ts` so promptfoo evaluates the actual agent
that runs on PRs, not a bare model call — per Linear 10X-19's explicit rationale ("the native
provider tests the raw model, not the agent that actually runs on PRs").

**Contract**: Exports a class/object with `id()` and `async callApi(prompt)` returning
`{ output: JSON.stringify(result.value) }` on success or `{ error: result.diagnostic }` on failure,
calling `runStructuredSession` with the same `jsonSchema`/`validate` pairing `cli.ts` uses. **The
session options must match `cli.ts:275-288` exactly**, or this phase's stated rationale is false:
pass the same `cwd` (the repo-root `src/` tree that `REVIEW_ROOT` resolves to — deliberately not the
repo root, per the security comment at `cli.ts:61-73`) and leave `model`/`maxTurns`/`maxBudgetUsd`
unset so `agent.ts`'s defaults apply, per the "keep CLI defaults" decision. `agent.ts:146` omits
`cwd` entirely when it is `undefined`, so a provider that simply forgets it hands the eval agent a
different read scope than the agent that runs on PRs — measuring something other than what ships.
Since
this package is ESM-only and has no build step, the provider file must be loadable by promptfoo's
Node runtime directly — verify promptfoo can `import()` a `.js` file that itself imports a `.ts`
module via the project's existing `tsx` toolchain (may require a thin `.mjs` wrapper using
`--import tsx/esm` semantics, or invoking through `node --import tsx/esm`; resolve during
implementation and document the actual working form here as a comment in the file, since this
package's own `AGENTS.md` requires ESM throughout).

### Success Criteria:

#### Automated Verification:

- The provider loads under the package's own ESM/`tsx` toolchain: `node --check promptfoo/provider.js`, then `node --import tsx/esm -e "await import('./promptfoo/provider.js')"` resolves without error. This replaces a `npm run typecheck` criterion, which would be vacuous here — `tsconfig.json`'s `include` is `["src/**/*.ts", "scripts/**/*.ts", "test/**/*.ts"]` with no `allowJs`, so `tsc --noEmit` never sees `promptfoo/provider.js` and would pass whether the provider works or not. The import smoke is also the cheapest way to settle the module-loading risk this phase carries.
- `npm test` still passes (no new network-calling tests added)

#### Manual Verification:

- `npm run promptfoo` produces a comparison table with both providers, and the custom-provider row's assertions pass against all three existing fixtures

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 6: End-to-End Verification

### Overview

Prove the workflow works cheaply first, then with one real PR, capturing the evidence Linear
10X-19 asks for.

### Changes Required:

#### 1. Smoke and real-PR proof

**File**: N/A (operational verification, no new source files)

**Intent**: Prove secret resolution, `npm ci`, and the native binary all work on `ubuntu-latest`
before spending a run against a real PR event; then open (or reuse) the draft PR for this change
itself as the real end-to-end proof.

**The smoke run cannot use `workflow_dispatch`.** That event only fires for workflow files that
already exist on the **default branch**, and `review.yml` will not be on `main` until this change's
PR merges — while `.cursor/rules/git-workflow.mdc` forbids pushing to `main` directly. Neither the
UI's "Run workflow" button nor `gh workflow run --ref` can reach a dispatch-triggered workflow that
lives only on a feature branch. The temporary `push:` trigger added in Phase 3 is therefore the smoke
path: a `push` event runs the workflow file from the pushed branch itself, with no default-branch
requirement. `workflow_dispatch` stays in the shipped file and starts working for maintainers the
moment this change merges.

**Contract**: One push to this change's branch, whose run is inspected for a successful `verdict`
output, a written `report-dir`, and the rendered `review.md` in the job's step summary. Because
Phase 4's comment and label steps are PR-gated, this run proves diff computation plus the action's
outputs only — never the side-effects. Then the change's own PR to `main` is observed for: exactly
one AI-review comment, exactly one of `ai-cr:passed`/`ai-cr:failed`, and (as a deliberate break test)
one `ai-cr:review` re-trigger that removes the label and produces a second comment.

### Success Criteria:

#### Automated Verification:

- N/A — this phase is operational verification, not new automated tests

#### Manual Verification:

- Screenshot or log capture of the Actions pipeline view showing the push-triggered smoke run
- Job logs from the real-PR run
- The AI-review comment visible on the PR
- The label swap and `ai-cr:review` consumption observed live

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 7: Documentation & Governance

### Overview

Remove the temporary smoke trigger, close the loop on the cross-change contract, and make the new
workflow discoverable in the repo's own documentation.

### Changes Required:

#### 1. Remove the temporary `push:` trigger

**File**: `.github/workflows/review.yml`

**Intent**: The branch-scoped `push:` trigger added in Phase 3 exists only to make Phase 6's smoke
run possible before `review.yml` reaches `main`. It must not ship — left in place it would fire a
paid review on every push to any future `feature/10x-19-*` branch, outside the PR flow the labels and
comments assume.

**Contract**: Delete the `push:` key from `on:`, leaving `pull_request` and `workflow_dispatch`.
This must happen before the PR to `main` is merged.

#### 2. Package docs

**File**: `packages/code-reviewer/AGENTS.md`, `packages/code-reviewer/README.md`

**Intent**: Mark the "Cross-Change Contract" section as consumed by this change (link the merged
PR), and document `npm run promptfoo` alongside the existing `npm run verify` command table.

**Contract**: Update the existing "Cross-Change Contract" heading in `AGENTS.md` to note 10X-19 is
implemented, with a pointer to `.github/workflows/review.yml`. Add a `promptfoo` row to the
`README.md` command table.

#### 3. Root documentation

**File**: `AGENTS.md`

**Intent**: Note in the "Deployment & Configuration" section that `review.yml` exists as a separate,
non-required PR gate, so a future reader of CI docs isn't surprised by a second workflow.

**Contract**: One or two sentences appended to the existing CI paragraph, not a new section.

### Success Criteria:

#### Automated Verification:

- The temporary `push:` trigger is gone: `python3 -c "import yaml; assert 'push' not in yaml.safe_load(open('.github/workflows/review.yml'))['on']"`
- `./gradlew build` behaves identically to before this change (no Java/Gradle files touched)

#### Manual Verification:

- Reading `AGENTS.md` (root and package-level) end-to-end, a new contributor understands that two independent CI workflows exist and why

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Testing Strategy

### Unit Tests:

- No new unit-testable logic is introduced (this change is YAML + a promptfoo config); existing `packages/code-reviewer` unit tests must continue to pass untouched.

### Integration Tests:

- The `workflow_dispatch` smoke run (Phase 6) is this change's integration test — it is the only way to prove GitHub Actions-specific behavior (secrets, `github.action_path` resolution, label/comment API calls) short of a real PR.

### Manual Testing Steps:

1. Trigger `workflow_dispatch` on the change branch; confirm the job succeeds and `verdict`/`report-dir` outputs are set.
2. Open the change's own PR to `main`; confirm a comment and a label appear within the workflow's run time.
3. Add `ai-cr:review`; confirm the label disappears immediately and a second review run completes with a fresh comment.
4. Push a second commit to the same PR; confirm a second new comment appears (not an edited one) and the label reflects the latest verdict only.

## Performance Considerations

`maxTurns`/`maxBudgetUsd` stay at the CLI's existing defaults (3 turns, $0.50/run) per the "keep
defaults" decision — API cost scales with PR count but is bounded per-run by code already shipped in
`code-review-agent`.

That bound covers Anthropic billing, not GitHub billing. A session that hangs before the SDK's budget
guard trips would hold the runner for the 6-hour default job timeout, against this private repo's
monthly runner-minute quota — hence the explicit `timeout-minutes: 15` in Phase 3 (`ci.yml` sets 10
for its far more predictable Gradle job). `cancel-in-progress: true` also means a superseded push
stops paying for both the runner and the API.

## Migration Notes

Not applicable — this change only adds new workflow/action files and a new devDependency; nothing
existing is migrated.

## References

- Cross-change contract: `packages/code-reviewer/AGENTS.md` ("Cross-Change Contract" section)
- Exit codes and CLI: `packages/code-reviewer/src/cli.ts:1-19`
- PR-comment-verbatim contract: `packages/code-reviewer/src/render.ts:1-9`
- Prior change: `context/archive/2026-08-07-code-review-agent/plan-brief.md`
- Linear: [10X-19](https://linear.app/10xnextslope/issue/10X-19/cicd-ai-code-review-on-every-pr-to-main-gha-workflow-composite-action) (this change), [10X-18](https://linear.app/10xnextslope/issue/10X-18/tooling-claude-agent-sdk-code-review-agent-as-an-independent) (prerequisite, done)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Requirements Document

#### Automated

- [x] 1.1 File exists and is valid markdown — d021daa

#### Manual

- [x] 1.2 Requirements doc reads as a standalone spec someone could implement from without the Linear issue open — d021daa

### Phase 2: Secret Provisioning & Composite Action

#### Automated

- [x] 2.1 `action.yml` is valid YAML — 924d349
- [x] 2.2 Third-party actions pinned to a commit SHA with a version comment — 924d349

#### Manual

- [ ] 2.3 Dry/manual run confirms `npm ci` resolves inside `packages/code-reviewer` and the CLI runs to completion

### Phase 3: Consumer Workflow

#### Automated

- [x] 3.1 Workflow file is valid YAML
- [x] 3.2 `ci.yml` is untouched

#### Manual

- [ ] 3.3 `workflow_dispatch` run confirms the diff file is non-empty and matches expected changes

### Phase 4: PR Comment & Label Side-Effects

#### Automated

- [ ] 4.1 Workflow file remains valid YAML

#### Manual

- [ ] 4.2 Real draft PR shows exactly one label and exactly one new comment per push
- [ ] 4.3 Adding `ai-cr:review` re-triggers a run and removes the label immediately
- [ ] 4.4 Each comment's provenance footer names the commit SHA it reviewed

### Phase 5: Promptfoo Eval Suite

#### Automated

- [ ] 5.1 The provider loads under ESM/`tsx`: `node --check` plus an `import()` smoke
- [ ] 5.2 `npm test` still passes

#### Manual

- [ ] 5.3 `npm run promptfoo` produces a comparison table and all fixture assertions pass

### Phase 6: End-to-End Verification

#### Manual

- [ ] 6.1 Screenshot/log capture of the Actions pipeline view showing the push-triggered smoke run
- [ ] 6.2 Job logs from the real-PR run
- [ ] 6.3 The AI-review comment visible on the PR
- [ ] 6.4 The label swap and `ai-cr:review` consumption observed live

### Phase 7: Documentation & Governance

#### Automated

- [ ] 7.1 The temporary `push:` trigger is gone from `review.yml`
- [ ] 7.2 `./gradlew build` behaves identically to before this change

#### Manual

- [ ] 7.3 Root and package-level `AGENTS.md` read end-to-end make the two-workflow setup clear
