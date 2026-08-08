# Requirements — AI Code Review on Every PR to `main`

> Change: `ci-cd-code-review` · Linear [10X-19](https://linear.app/10xnextslope/issue/10X-19/cicd-ai-code-review-on-every-pr-to-main-gha-workflow-composite-action)
> Design decisions and phasing: `plan.md`, `plan-brief.md` in this folder.

This is the durable spec for the GitHub Actions integration: what it does, what it consumes, what it
produces, and what it deliberately does not do. It is the artifact the shipped YAML
(`.github/actions/ai-reviewer/action.yml`, `.github/workflows/review.yml`) is checked against, and it
is written to stand alone — a reader needs neither the Linear issue nor the plan to implement or audit
from it.

## Concept

Every pull request targeting `main` is reviewed automatically by the `packages/code-reviewer` CLI —
already built and shipped by Linear
[10X-18](https://linear.app/10xnextslope/issue/10X-18/tooling-claude-agent-sdk-code-review-agent-as-an-independent)
— running on a GitHub-hosted `ubuntu-latest` runner. The review scores the PR's diff against five
criteria drawn from this repository's own written conventions and reports back where the author
already looks: a comment on the PR plus a single pass/fail label.

Two components, deliberately split:

| Component | File | Knows about |
|---|---|---|
| Composite action | `.github/actions/ai-reviewer/action.yml` | Node, `npm ci`, the CLI, exit codes |
| Consumer workflow | `.github/workflows/review.yml` | Triggers, git diff, comments, labels, concurrency |

The action is a self-contained wrapper (its own Node setup, its own `npm ci`, its own CLI
invocation), so the workflow never needs to know a Node toolchain is involved. The workflow owns
everything GitHub-specific and reads only the action's two declared outputs.

`review.yml` is a **separate workflow from `ci.yml` and is not a required check.** `ci.yml`'s four
Gradle gates are deterministic; an LLM verdict is probabilistic, and folding the two together would
let a flaky verdict block correct code. This stays true until the promptfoo suite (below) earns the
verdict enough confidence to be promoted.

## Inputs

### To the composite action

| Input | Required | Purpose |
|---|---|---|
| `api-key` | one of | `ANTHROPIC_API_KEY` — a metered Claude Console key |
| `oauth-token` | one of | `CLAUDE_CODE_OAUTH_TOKEN` — a subscription credential from `claude setup-token` |
| `diff-file` | yes | Filesystem **path** to a unified diff on the runner |
| `pr-title` | no | Declared for forward compatibility; not wired into the prompt today |
| `pr-body` | no | Same |

**Exactly one credential**, enforced by a guard step that runs before `npm ci` so a missing secret
costs seconds rather than a full install. Supplying both is rejected rather than resolved by
precedence: Claude Code ranks `ANTHROPIC_API_KEY` above `CLAUDE_CODE_OAUTH_TOKEN`, so it would
silently pick the key and leave no way to tell which account paid for a verdict. The guard tests
only emptiness, never a value.

The credential reaches the model through the environment of the CLI step — never through
`options.env` inside the SDK, which replaces rather than merges the subprocess environment and
would drop `PATH` along with the credential. That the SDK sees it at all depends on `agent.ts`
deliberately never setting `options.env` (trap 4 in `packages/code-reviewer/AGENTS.md`), which is
what lets the spawned native binary inherit it.

### Why this project uses the OAuth token

There is no Anthropic Console org to mint an API key from, and no free alternative: GitHub Models —
the standard zero-cost-inference-in-CI route via `GITHUB_TOKEN` — was fully retired on 2026-07-30,
and pointing `ANTHROPIC_BASE_URL` at a gateway relocates the bill rather than removing it. A
subscription OAuth token reuses the existing Enterprise seat at no additional cost.

Two constraints come with it. The token expires after a year and must be re-minted. And Anthropic's
Agent SDK documentation steers custom SDK-built agents toward API keys while blessing subscription
OAuth for the official CLI and `anthropics/claude-code-action`; the narrow reading is that the
restriction targets developers *offering* claude.ai login in a product, which a private single-author
repository is not. The `api-key` input stays declared so switching back is a one-line workflow edit
if that reading ever needs revisiting.

The diff is passed as a path, never as an inline string. `$GITHUB_OUTPUT` has a 1 MB cap and a
line-oriented parser that a multi-line diff corrupts, so the workflow writes the diff to a file under
`$RUNNER_TEMP` and hands over the path.

### To the workflow

- The PR's merge-base diff, computed after `actions/checkout` with `fetch-depth: 0` (a shallow
  checkout yields an empty diff silently). **Lockfiles are the one exclusion** — `package-lock.json`,
  `pnpm-lock.yaml`, `yarn.lock` — because they are machine-generated, no criterion can apply to
  them, and a single dependency install produces a diff several times the CLI's `MAX_DIFF_BYTES`
  (200 KB) guard, which would exit `1` and leave all the hand-written code in that PR unreviewed.
  Nothing else is filtered; the guard remains the cost ceiling.
- `secrets.ANTHROPIC_API_KEY`, provisioned as a repository secret.
- Three repository labels that must exist before the first run: `ai-cr:passed`, `ai-cr:failed`,
  `ai-cr:review`.

### Configuration the workflow deliberately does not supply

No `--model`, `--fail-on`, `--max-budget-usd`, or `--max-turns` override. The CLI's defaults
(`claude-sonnet-5`, `high`, `$0.50`, 3 turns) are the single source of truth, so a local run and a CI
run score the same diff identically.

## Criteria

The five review criteria are **not duplicated here.** They live in
`packages/code-reviewer/prompts/criteria.md`, whose `##` heading ids are held in lockstep with
`CRITERION_IDS` in `packages/code-reviewer/src/schema.ts` by a unit test. The ids are:
`flyway-forward-only`, `ddl-auto-validate`, `constructor-injection`, `access-control-scoping`,
`e2e-conventions`.

Two properties of that document matter to this workflow and are worth restating:

- Scores are **diagnostic only.** The pass/fail decision is deterministic and computed by
  `src/verdict.ts`, exposed as the `passed` boolean in `review.json`. A consumer reads `passed`; it
  never re-derives the gate from `findings` plus a duplicated threshold.
- Every criterion is answerable from the diff alone, so a review does not depend on repository reads
  competing with the turn budget.

## Contract with the CLI

Consumed, not modified. `review.json`'s schema, the CLI's flags, and the exit codes are a documented
cross-change contract (`packages/code-reviewer/AGENTS.md` → "Cross-Change Contract").

| Exit code | `verdict` output | Meaning |
|---|---|---|
| `0` | `passed` | Run completed, no findings at or above `--fail-on` |
| `1` | `invalid_input` | Invalid invocation/input, or a startup/auth failure before a session ran |
| `2` | `no_result` | A session started but produced no usable result |
| `3` | `blocked` | Run completed, findings at or above `--fail-on` blocked it |

The action exposes two outputs:

- `verdict` — one of the four strings above, mapped from the CLI's numeric exit code. The numeric
  code must be captured explicitly; `continue-on-error: true` alone exposes only
  `success`/`failure`, and `cmd || true` discards `$?` — either would collapse the four-way verdict
  into pass/fail and silently lose the "no misleading label" behavior below.
- `report-dir` — the directory the CLI's `--out` wrote `review.json` and `review.md` into, fixed to
  `${{ runner.temp }}/ai-reviewer` so callers don't invent a path. Needed because
  `packages/code-reviewer/src/render.ts` establishes that `review.md` is posted as a PR comment body
  **verbatim**, which a single status string cannot carry.

A non-zero CLI exit is a meaningful outcome for the workflow to react to, not an action failure.

## Side-effects

Both side-effects are gated on `github.event_name == 'pull_request'` — the comment and label APIs
both need an `issue_number`, which does not exist on a `workflow_dispatch` or `push` run. Non-PR runs
instead write `review.md` to `$GITHUB_STEP_SUMMARY`, which is where a smoke run's rendered report is
read.

### 1. PR comment

One **new** comment per run — never an edit or upsert of a prior comment. The body is `review.md`
verbatim, plus a one-line provenance footer naming the commit SHA that was reviewed
(`github.event.pull_request.head.sha`). With a new comment per push, that footer is the only thing
telling a reader which commit a given comment describes.

The footer deliberately does **not** name the model. `review.json` carries no run provenance — the
resolved model, cost, and turn count reach only `--verbose` stdout — and since the workflow passes no
`--model`, it cannot truthfully name what ran. Revisit when promptfoo starts moving the default model
and an unattributed verdict becomes genuinely ambiguous.

When `verdict` is `invalid_input` or `no_result`, the comment is a short explanatory sentence
referencing the exit-code meaning instead of a rendered report — there is no report to post.

### 2. Pass/fail label

Exactly one of the pair is current at any time; the step runs with `if: always()` after the review so
a label still lands even if the comment step fails.

| `verdict` | Label action |
|---|---|
| `passed` | add `ai-cr:passed`, remove `ai-cr:failed` if present |
| `blocked` | add `ai-cr:failed`, remove `ai-cr:passed` if present |
| `invalid_input` / `no_result` | touch neither label |

The last row is the point: a run that produced no verdict must not leave a misleading pass or fail
label behind. Every removal must tolerate absence — `removeLabel` returns 404 when the label is not
on the PR, which is the normal case on a first `passed` run.

## Retry behavior

Adding the `ai-cr:review` label to a PR re-triggers a review. The workflow listens for
`pull_request: [labeled]` and a job-level `if` skips every label event except this one, so unrelated
labelling doesn't spend an API call.

The label is **consumed**: an early step — before the review runs, gated on
`github.event.action == 'labeled' && github.event.label.name == 'ai-cr:review'` — removes it. Removing
it up front rather than at the end means a maintainer sees their click register immediately instead of
wondering whether it took. It behaves like a one-shot button, not a state flag.

## Triggers

| Trigger | Purpose |
|---|---|
| `pull_request` to `main`, types `[opened, synchronize, reopened, labeled]` | The real gate |
| `workflow_dispatch` (`ref` required, `base` defaulting to `main`) | Maintainer smoke run without opening a PR — usable only once this workflow is on the default branch |
| `push` on `feature/10x-19-*` | **Temporary, removed before merge.** The only way to smoke-test before `review.yml` reaches `main`, since `workflow_dispatch` fires only for workflow files already on the default branch |

Cost and safety guards on the job: `timeout-minutes: 15` (without it the job inherits the 6-hour
default and a hung session would burn this private repo's runner-minute quota),
`concurrency` keyed on the PR number with `cancel-in-progress: true` (a superseded push stops paying
for both the runner and the API), and `permissions: contents: read, pull-requests: write,
issues: write` — declaring any scope sets every unlisted one to `none`, so `contents: read` is
required or `actions/checkout` fails on the first step. Labels go through the issues API, hence
`issues: write`.

Every third-party action is pinned to a full commit SHA with a trailing version comment, matching
`ci.yml`'s existing convention.

## Eval suite

`packages/code-reviewer` gains a promptfoo suite run manually via `npm run promptfoo` — **never wired
into either workflow.** It makes real API calls, matching the existing `npm run verify` cost posture
("costs money… never a watch task"). It measures prompt/model quality over time rather than gating
any specific PR, which is why it has no trigger.

Two provider layers: promptfoo's native `anthropic:` provider for cheap raw-model comparison, and a
custom provider wrapping `runStructuredSession` so the thing being measured is the actual agent that
runs on PRs — same `cwd` scope, same defaults. Assertions run against the three existing fixtures in
`packages/code-reviewer/fixtures/`.

## Non-goals

Full list with rationale: `plan.md` → "What We're NOT Doing". In short, this change does not adopt
`anthropics/claude-code-action@v1`, does not make `review.yml` a required check, does not add a
promptfoo CI workflow, does not filter paths out of the reviewed diff, does not calibrate `--fail-on`
or any score threshold against real PR history, and does not change `review.json`'s schema, the CLI's
flags, or the exit codes. Keeping the producer untouched makes this a pure integration: a failure
during the workflow phases can only be workflow-side.
