<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: CI/CD AI Code Review

- **Plan**: `context/changes/ci-cd-code-review/plan.md`
- **Scope**: Phases 1–7 (all)
- **Date**: 2026-08-08
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 6 warnings, 4 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | WARNING |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | WARNING |

## Automated verification re-run

| Criterion | Result |
|---|---|
| 1.1 `requirements.md` exists | PASS |
| 2.1 / 3.1 / 4.1 workflow + action are valid YAML | PASS (via `js-yaml`; the plan's `python3 -c "import yaml"` command cannot run — PyYAML is not installed on this machine) |
| 2.2 third-party actions pinned to full SHA + version comment | PASS (4 pins, all 40-char SHAs) |
| 3.2 `ci.yml` untouched | PASS (empty diff vs `main`) |
| 5.1 provider loads under ESM/`tsx` (`node --check` + `import()`) | PASS |
| 5.2 `npm test` | PASS (76 tests, 0 failures) |
| 7.1 `push:` trigger gone | PASS (`on:` keys are exactly `pull_request`, `workflow_dispatch`) |
| 7.2 `./gradlew build` | PASS (BUILD SUCCESSFUL, all tasks up-to-date) |

Everything the plan asked for passes in substance. The only wrinkle is that four of the criteria are
written against PyYAML, which is absent here, so they were re-run with an equivalent parser.

## Findings

### F1 — `npm run promptfoo` cannot produce the two-provider table criterion 5.3 claims

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Success Criteria
- **Location**: `packages/code-reviewer/promptfoo/promptfooconfig.yaml:21-23`
- **Detail**: The plan's Phase 5 contract requires the config to list "at least one
  `anthropic:messages:<model-id>` entry **and** `file://provider.js`" (plan:388-389), and its manual
  criterion reads "`npm run promptfoo` produces a comparison table **with both providers**"
  (plan:436) — marked `[x] 5.3 … — 569472b` (plan:647). The shipped default config lists only
  `file://provider.js`. The native provider was moved to `promptfooconfig.compare.yaml:31` behind a
  separate `npm run promptfoo:compare`, because promptfoo preflights every configured provider and
  one API-key provider would abort a run that otherwise authenticates via the subscription OAuth
  token (`promptfooconfig.yaml:10-13`). That split is well-reasoned, but `promptfoo:compare` needs
  an `ANTHROPIC_API_KEY` this project decided in Phase 2 it cannot mint — so the two-provider
  comparison is not something anyone can currently run, and the checked box overclaims.
- **Fix A ⭐ Recommended**: Rescope 5.3 in the plan's Progress to what shipped and is runnable — the
  single-provider agent suite passing all three fixtures — and record the compare config as an
  opt-in extra that is blocked on an API key.
  - Strength: Makes the Progress row a true statement without discarding the split, which is the
    right design given the preflight constraint.
  - Tradeoff: The plan's two-layer comparison goal stays unmet and needs tracking somewhere.
  - Confidence: HIGH — the preflight behaviour is documented in the config and the credential
    constraint is stated in `packages/code-reviewer/AGENTS.md`.
  - Blind spot: Not verified whether promptfoo's preflight can be disabled, which would allow one
    config after all.
- **Fix B**: Keep 5.3 as written and actually run `promptfoo:compare` once an API key exists,
  leaving the row unchecked until then.
  - Strength: Preserves the original intent of measuring the agent against the raw model.
  - Tradeoff: Blocks closing the change on a credential the project has said it will not obtain.
  - Confidence: MEDIUM — depends entirely on whether an API key is ever available.
  - Blind spot: No estimate of what the compare run would cost.
- **Decision**: FIXED via Fix A — amended the Phase 5 contract and manual criterion in `plan.md`,
  and annotated Progress row 5.3 to record that only the production-agent provider was verified.

### F2 — Four in-flight reversals never made it back into `plan.md`

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Scope Discipline
- **Location**: `context/changes/ci-cd-code-review/plan.md:75-101`
- **Detail**: `git log -p -- plan.md` shows every commit after `d021daa` touching only `## Progress`
  checkboxes. Four decisions reversed the plan body and were recorded in `plan-brief.md` and
  `requirements.md` instead, leaving `plan.md` asserting the opposite of what shipped:
  1. **Lockfile path filter.** plan:98-99 says "Not adding path filters to exclude
     lockfiles/build artifacts"; `review.yml:92-95` excludes `package-lock.json`, `pnpm-lock.yaml`,
     and `yarn.lock`. The justification is strong (promptfoo's install grew the lock enough that the
     full diff would trip `MAX_DIFF_BYTES` and review nothing), and commit `fffe342` says so
     explicitly — but the plan still reads as a prohibition, and `plan-brief.md:61` still lists
     "diff path filtering" under Out of scope, four rows below the decision reversing it.
  2. **Credential swap** (`9a616c4`). plan:209-210 and plan:218-220 prescribe a required `api-key`
     input and `env: ANTHROPIC_API_KEY`; `action.yml:10-22` makes `api-key` optional and adds
     `oauth-token`, which `review.yml:107` is what actually passes.
  3. **Node 24 pin** (`894ac24`). `action.yml:40-48` adds a `node-version` input defaulting to `24`
     because npm's major must match the one that wrote the lockfile. The plan never mentions a Node
     version at all.
  4. **`cli.ts` was touched.** plan:85-91 states the producer stays untouched so "a failure during
     Phases 3-6 can only be workflow-side"; `cli.ts:74` widened `const REVIEW_ROOT` to
     `export const REVIEW_ROOT`. Zero behaviour change and the right call versus duplicating the
     path in the provider — but the stated invariant is now literally false.
- **Fix**: Append an "Amendments" section to `plan.md` recording all four reversals with their
  rationale, and delete the stale "diff path filtering" row from `plan-brief.md:61`.
- **Decision**: FIXED — added an `## Amendments` section to `plan.md` covering all four reversals
  plus the `!cancelled()` divergence; in `plan-brief.md`, struck the superseded "Diff scope" row,
  moved diff path filtering out of the Out-of-scope list, and corrected the in-scope credential from
  `ANTHROPIC_API_KEY` to the shipped OAuth token.

### F3 — `npm ci` installs the full dev tree with lifecycle scripts, in a job holding a write-scoped token

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: `.github/actions/ai-reviewer/action.yml:94-104`
- **Detail**: `actions/checkout` runs without `persist-credentials: false`, so the job's
  `GITHUB_TOKEN` (scoped `pull-requests: write` + `issues: write`) sits in `.git/config` as an
  `http.extraheader`. The next thing to touch the workspace is a bare `npm ci`
  (`action.yml:104`) over a tree that grew from 139 to 821 packages when `promptfoo` was added as a
  devDependency (`package.json:27`). Any transitive `preinstall`/`postinstall` runs as the job user
  with read access to that token, and could patch `node_modules` before the next step exports
  `CLAUDE_CODE_OAUTH_TOKEN` (`action.yml:130-134`). Two real mitigations exist: the credential guard
  at `action.yml:65-81` runs before the install, so a fork PR (no secrets, read-only token under
  `pull_request`) fails first; and the lockfile is committed. Residual exposure is same-repo PRs and
  compromised transitive deps. Separately, none of promptfoo/typescript/`@types/node` is used on the
  runner, and `actions/setup-node` sets no `cache: npm`, so this is paid for on every review.
- **Fix**: Move `tsx` into `dependencies` (the `review` script needs it at runtime) and install with
  `npm ci --omit=dev --ignore-scripts`, verifying the SDK's per-platform native binary still
  resolves — the package's `AGENTS.md` warns only against `--omit=optional`. Add `cache: npm` with
  `cache-dependency-path: packages/code-reviewer/package-lock.json`. Do **not** use
  `persist-credentials: false`: `review.yml:86` fetches the base ref from a private repo and needs
  that credential.
- **Decision**: SKIPPED

### F4 — The reviewer package is on every PR's critical path but its free tests gate nothing

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: `.github/workflows/ci.yml` (no Node job), `.github/workflows/review.yml`
- **Detail**: `ci.yml` has no Node step, and `review.yml` only invokes the CLI. Yet `npm test` costs
  nothing, makes no network calls, and guards exactly the invariants this change now depends on:
  the per-run nonce that forms the prompt-injection boundary (`src/prompt.ts:13-21`), the `draft-07`
  literal whose typo silently emits no `$schema` (Trap 1 in the package's `AGENTS.md`), and the
  criterion-id lockstep between `prompts/criteria.md` and `CRITERION_IDS`. Any of those can now
  break and ship; the failure surfaces as `invalid_input`/`no_result` on live PRs, or as a silently
  weakened injection boundary, rather than a red check.
- **Fix**: Add a small Node job running `npm ci && npm test && npm run typecheck` in
  `packages/code-reviewer`, path-filtered to that package. The paid `verify`/`promptfoo` suites stay
  manual as designed.
- **Decision**: DEFERRED — queued in `follow-ups/review-fixes.md`. Not fixed here because the change
  lives in `ci.yml`, which this plan deliberately left untouched (criterion 3.2).

### F5 — The reviewed diff is already at 62% of the CLI's cap, two-thirds of it unscoreable prose

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: `.github/workflows/review.yml:92-95`
- **Detail**: Measured on this branch's own merge base, the diff the workflow sends is 123,681 bytes
  against `MAX_DIFF_BYTES = 200_000` (`src/cli.ts:50`), and 83,558 of those bytes (68%) are markdown
  under `context/` and `*.md`. All five review criteria (`src/schema.ts:24-30`) are Java, Flyway,
  Spring, and Playwright concerns — not one can score a line of that prose. This is the same failure
  mode the lockfile exclusion was added to prevent, one step removed: a plan-heavy change crosses the
  ceiling, the CLI exits `1`, and the PR's hand-written code goes entirely unreviewed while the
  comment reports `invalid_input`. `requirements.md:86` states this filtering choice deliberately
  ("Nothing else is filtered; the guard remains the cost ceiling"), but the measurement shows it is
  close to biting.
- **Fix**: Add `':(exclude)context/**'` to the pathspec at `review.yml:92-95`, extending the
  reasoning already written for lockfiles.
- **Decision**: FIXED — added `':(exclude)context/**'` to the pathspec and rewrote the surrounding
  comment to give both exclusions one shared rationale; updated `requirements.md:82-86` to match.
  Measured effect on this branch: reviewed diff drops from 123,681 to 46,334 bytes (62% → 23% of
  `MAX_DIFF_BYTES`). Workflow re-validated as YAML.

### F6 — `requirements.md`, the artifact the YAML is audited against, contradicts the shipped YAML

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: `context/changes/ci-cd-code-review/requirements.md:87`, `:162`
- **Detail**: Line 87 lists "`secrets.ANTHROPIC_API_KEY`, provisioned as a repository secret" as a
  workflow input, while `review.yml:107` passes `secrets.CLAUDE_CODE_OAUTH_TOKEN` — and the same
  document argues for the OAuth token at length at `:61-73`, so line 87 contradicts its own §Inputs
  table at `:43-44`. Line 162 says the label step "runs with `if: always()`" while `review.yml:137`
  uses `!cancelled()`. The document declares itself the spec the shipped YAML is checked against,
  and root `AGENTS.md` points future readers at it, so both entries will mislead the next auditor.
- **Fix**: Correct `:87` to name the OAuth token and `:162` to `!cancelled()`, with the one-line
  rationale already present in `review.yml:131-134`.
- **Decision**: FIXED — the inputs bullet now names `CLAUDE_CODE_OAUTH_TOKEN` and cross-references
  the document's own OAuth rationale; the label section now says `!cancelled()` and explains why.

### F7 — Workflow surface hygiene: redundant `issues: write`, dead untrusted PR inputs

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: `.github/workflows/review.yml:22-27`, `:112-113`
- **Detail**: Two independent minor items. (a) All three API calls (`addLabels`, `removeLabel`,
  `createComment`) target pull requests and are guarded by `github.event_name == 'pull_request'`;
  GitHub grants label and comment writes on a PR under `pull-requests: write`. The `/issues/` path
  segment in the REST route is not the same as the `issues` permission scope, so `issues: write`
  (and the comment at `:23` justifying it) is likely unnecessary and lets the token create and close
  real issues. (b) `pr-title`/`pr-body` are passed at `:112-113` and declared at
  `action.yml:28-39`, but nothing consumes them. They are safe today — verified that no `run:` block
  or `github-script` body references them — but they are the two classic script-injection carriers,
  and the plumbing now looks blessed for whoever wires them in next.
- **Fix**: Drop `issues: write` and confirm the label swap still works on one PR; remove the two
  unused inputs until something consumes them, re-adding with `env:` indirection at that point.
- **Decision**: FIXED (both parts) — `permissions` is now `contents: read` + `pull-requests: write`;
  `pr-title`/`pr-body` removed from both `review.yml` and `action.yml`, each replaced by a comment
  explaining why they are deliberately absent. `requirements.md` and the plan's Amendments section
  updated to match. **Unproven locally**: the permission removal needs one live PR run to confirm
  the label swap still applies; restore `issues: write` if it fails.

### F8 — `set +e` covers the setup commands; `exit-code` output is written but undeclared

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: `.github/actions/ai-reviewer/action.yml:120-123`, `:156`
- **Detail**: The `set +e` is correct and well-argued — it is what lets exit codes 1–3 be captured as
  verdicts rather than aborting the step. But it takes effect before `mkdir -p "$REPORT_DIR"` and
  the `report-dir` write, so a failing `mkdir` lets the script continue, the reviewer fails to write
  artifacts, and the step reports `invalid_input` — blaming the input for a runner problem, exactly
  the misattribution the diff step's comment at `:83-85` was written to avoid. Separately, `:156`
  writes `exit-code=$code` to `$GITHUB_OUTPUT`, but the action's `outputs:` block (`:50-58`) never
  declares it, so a future caller referencing `steps.review.outputs.exit-code` silently gets an
  empty string.
- **Fix**: Move `mkdir -p` and the `report-dir` write above `set +e`; either declare `exit-code` as
  an output or drop the line.
- **Decision**: FIXED — the step now opens `set -euo pipefail`, does its setup under errexit, and
  only then drops to `set +e` for the CLI invocation, with a comment naming the misattribution it
  prevents. `exit-code` is now declared in the action's `outputs:` block. Verified: YAML parses,
  `bash -n` clean on the extracted script.

### F9 — Unguarded report read and comment post, with the label landing first

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: `.github/workflows/review.yml:189`, `:198-202`
- **Detail**: `fs.readFileSync` at `:189` has no `try`/`catch`. In practice the file exists — exit
  `0` and exit `3` both write both artifacts (`cli.ts:317-321`) — so this is low-probability rather
  than latently broken. But a miss kills the step with a raw Node stack after the label step has
  already applied a label, so the PR shows `ai-cr:failed` with no findings anywhere the author can
  read them. `createComment` has the same exposure to a transient 5xx or secondary rate limit. The
  only defensive handling anywhere is the deliberate 404 tolerance on `removeLabel`.
- **Fix**: Wrap the read and `createComment` and fall back to a short "the report could not be
  posted, see the job summary" comment — the summary at `:117-129` already holds the content.
- **Decision**: FIXED — the report read is now try/caught and falls back to a comment pointing at
  the job summary, so a label never lands with nothing behind it. `createComment` retries once after
  5s (the usual failure is a transient 5xx or secondary rate limit) and then calls `core.setFailed`
  rather than swallowing: a silent PR would be worse than a red step, given the label is already
  applied. Verified: YAML parses and all three `github-script` bodies pass `node --check`.

### F10 — Promptfoo suite record accuracy: a second source of truth and an overstated comment

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: `packages/code-reviewer/promptfoo/tests.yaml:12-51`,
  `packages/code-reviewer/promptfoo/grader.js:15-16`
- **Detail**: Two small accuracy issues in otherwise good work. (a) The plan said `vars.expectedCriteria`
  should be sourced from `fixtures/expectations.json` (plan:390-391); `tests.yaml:12-51` hand-restates
  the three fixtures' criterion lists instead, explicitly opting out at `:1-4`. They agree today, but
  this is the drifting-second-copy risk the plan spent a paragraph avoiding for the JSON schema. The
  same file's header claims it exists so the two suites "can never drift", yet `defaultTest` and the
  seven-line rubric are copy-pasted between `promptfooconfig.yaml:25-49` and
  `promptfooconfig.compare.yaml:38-57` — so the suites can still drift on how they judge. (b)
  `grader.js:15-16` says "Deliberately no `cwd`: … the grader … has no business reading the
  repository." Omitting `cwd` does not deny reads — `agent.ts:146` just drops the option while
  `Read`/`Glob`/`Grep` stay granted (`agent.ts:138-139`), so the grader gets `packages/code-reviewer/`
  as its read root. Given that `cli.ts:61-73` treats read-scope selection as a security decision, the
  comment misdescribes the code.
- **Fix**: Have `tests.yaml`'s criterion lists read from `fixtures/expectations.json`, extract the
  shared `defaultTest` block to a file both configs reference, and reword the `grader.js` comment to
  say what omitting `cwd` actually does.
- **Decision**: PARTIALLY FIXED — reworded the `grader.js` comment to state that omitting `cwd`
  falls back to the process cwd with `Read`/`Glob`/`Grep` still granted, narrowing the read root
  rather than denying reads, and that real containment would be a `tools` change. The two
  duplication items (`tests.yaml` vs `fixtures/expectations.json`, and the copy-pasted `defaultTest`
  across the two configs) were deliberately left as-is: both copies currently agree, and the comment
  was the part that actively misleads.

## Triage outcome (2026-08-08)

| Decision | Findings |
|---|---|
| Fixed | F1 (Fix A), F2, F5, F6, F7, F8, F9 |
| Partially fixed | F10 — comment corrected, duplication left as-is |
| Deferred to follow-up | F4 → `follow-ups/review-fixes.md` |
| Skipped | F3 |

Automated criteria re-run after the fixes: both YAML files parse, all three `github-script` bodies
pass `node --check`, the extracted action script passes `bash -n`, 5 of 5 third-party actions remain
fully SHA-pinned with version comments, `ci.yml` is still untouched, the `push:` trigger is still
gone, and `npm test` is still 76/76.

**One thing needs a live PR run to confirm:** dropping `issues: write` (F7). If the label swap fails
on the next run, restore that scope in `review.yml`.

## Verified clean

Worth recording, because these were the dominant risks for a change of this shape and all were
handled correctly:

- **No script injection.** Every `${{ }}` in both files was enumerated. `github.base_ref` and
  `inputs.base` reach the shell only through `env: BASE_REF` and are referenced as `"$BASE_REF"`;
  all three `github-script` blocks read exclusively from `process.env` with no interpolation inside
  `script:`. The only expression inlined into a `run:` is `${{ github.action_path }}`, which is
  runner-generated.
- **Trigger safety.** `pull_request`, not `pull_request_target`. Fork PRs get no secrets and a
  read-only token, and the credential guard fails the job before `npm ci`.
- **Secret handling.** Neither credential is echoed, written to a file, added to the step summary,
  or passed to the model. Exactly one variable is exported, so precedence is unambiguous. The diff
  is passed as a path, never interpolated or `eval`'d.
- **Action pinning.** All four third-party `uses:` are full 40-char SHAs with version comments,
  matching `ci.yml`'s convention.
- **Merge-base correctness.** `fetch-depth: 0` + explicit base fetch + `git merge-base` with two
  commits yields the same set as `main...HEAD` and cannot silently produce an empty diff.
- **No self-retrigger loop.** The job's `if` filters `labeled` down to `ai-cr:review`, so the
  workflow's own label writes are skipped, and `removeLabel` fires an `unlabeled` event that is not
  in `types`.
- **Prompt-injection defense in depth.** Nonce-delimited diff, read root scoped to `src/`, pipe
  escaping in `render.ts`, and the workflow deliberately kept off the required-check list.
