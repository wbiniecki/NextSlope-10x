# CI/CD AI Code Review — Plan Brief

> Full plan: `context/changes/ci-cd-code-review/plan.md`
> Upstream scope: Linear [10X-19](https://linear.app/10xnextslope/issue/10X-19/cicd-ai-code-review-on-every-pr-to-main-gha-workflow-composite-action) (authoritative), prerequisite [10X-18](https://linear.app/10xnextslope/issue/10X-18/tooling-claude-agent-sdk-code-review-agent-as-an-independent) (done)

## What & Why

Put the already-built `packages/code-reviewer` CLI on GitHub Actions so every PR to `main` gets an
AI-generated review comment plus a pass/fail label, backed by a promptfoo eval suite that pins
review quality so future prompt/model changes are measured rather than guessed at. This makes the
repo's own written conventions (Flyway forward-only, `ddl-auto=validate`, constructor injection,
principal-scoped access, e2e discipline) actually enforced on every PR, not just documented.

## Starting Point

The reviewer CLI, exit codes (0/1/2/3), and `review.json` schema are fully built and treated as a
cross-change contract by `packages/code-reviewer/AGENTS.md` — this change consumes them without
touching them. `.github/workflows/ci.yml` is the only workflow today: four deterministic Gradle
gates, every action pinned to a commit SHA. No `.github/actions/` directory exists yet, and
`packages/code-reviewer` has no promptfoo dependency.

## Desired End State

A PR gets exactly one AI-review comment per push (containing the rendered `review.md`) and exactly
one current label (`ai-cr:passed` or `ai-cr:failed`). Adding `ai-cr:review` re-triggers a review and
consumes itself. Once this change is on `main`, a maintainer can smoke-test the plumbing via
`workflow_dispatch` without opening a PR. `npm run promptfoo` inside `packages/code-reviewer` produces a comparison table proving the
fixtures score as expected — run manually, never wired into CI.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
|---|---|---|---|
| Action approach | Hand-rolled composite action | Preserves the schema-validated `review.json`/exit-code contract already built; the escape hatch (`claude-code-action@v1`) would abandon it | User |
| Promptfoo scope | Kept in this change | Matches Linear 10X-19's scope exactly rather than splitting into a follow-up | User |
| Comment behavior | New comment every run | Simplest implementation; no comment-search/upsert logic needed | User |
| Label lifecycle | Swap pass/fail each run; auto-remove `ai-cr:review` after it triggers | Label state always reflects only the latest run; retry label behaves like a one-shot button | User |
| CI cost overrides | Keep all CLI defaults (model, budget, fail-on) | One source of truth in `src/agent.ts`/`src/cli.ts`; local and CI runs score identically | User |
| Oversized/inconclusive diffs (exit 1 or 2) | Comment explaining why, no label | Reuses the exit-code contract; a missing verdict must not produce a misleading pass/fail label | User (extended to exit 2 during planning for the same rationale) |
| Diff scope | Full merge-base diff, no path filters | Matches what a human reviewer sees on the PR's Files-changed tab; the CLI's own 200KB cap already guards cost | User |
| Requirements doc | Written as Phase 1, in the change folder | Keeps 10X-19 item 1's deliverable traceable alongside the design decisions that produced it | User |
| Verification strategy | Cheap smoke run first, then one real PR | Minimizes wasted API spend across debugging iterations before the real end-to-end proof | User |
| Smoke-run trigger | Temporary branch-scoped `push:`, not `workflow_dispatch` | `workflow_dispatch` only fires for workflow files already on the default branch, so it cannot run from the change branch; Phase 7 removes the temporary trigger | Plan review |
| Composite action output shape | `verdict` (status string) **plus** `report-dir` | `render.ts`'s own doc comment requires posting `review.md` verbatim, which a single status output can't carry | Plan |
| Promptfoo CI wiring | None — manual `npm run promptfoo` only | Matches the existing `npm run verify` cost posture ("costs money... never a watch task"); Linear's scope lists only the config file, not a workflow | Plan |
| `review.json` schema | Left frozen; provenance added workflow-side only (commit SHA, not model) | Every CI need is already served by today's shape, so keeping the producer untouched means a Phase 3-6 failure can only be workflow-side | User |
| CI credential | Subscription OAuth token (`CLAUDE_CODE_OAUTH_TOKEN`), not an API key | No Console org exists to mint a key from, and no free path remains (GitHub Models retired 2026-07-30); the token reuses the existing Enterprise seat at no extra cost | User (during Phase 2) |
| Action credential surface | Accepts `api-key` **or** `oauth-token`, exactly one | Keeps the API-key path a one-line edit away if the SDK-docs steer toward keys ever needs honoring; rejecting both avoids a silent precedence win | User (during Phase 2) |
| Reviewed diff scope | Lockfiles excluded (`package-lock.json`, `pnpm-lock.yaml`, `yarn.lock`); nothing else | Reverses the original "no path filters" non-goal. Adding promptfoo grew `package-lock.json` by 474 KB, so this change's own PR diff (~581 KB) would trip the 200 KB `MAX_DIFF_BYTES` guard and review nothing — a lockfile no criterion applies to would have blocked review of all the hand-written code | User (during Phase 5) |

## Scope

**In scope:** `context/changes/ci-cd-code-review/requirements.md`; `.github/actions/ai-reviewer`
composite action; `.github/workflows/review.yml` (triggers, diff computation, concurrency, comment,
label lifecycle); `packages/code-reviewer`'s promptfoo suite (config, custom provider, native
provider comparison, assertions); the `ANTHROPIC_API_KEY` repo secret; end-to-end verification via
`workflow_dispatch` and one real PR; documentation updates to both `AGENTS.md` files.

**Out of scope:** `anthropics/claude-code-action@v1`; any promptfoo CI workflow; making `review.yml`
a required check; any change to `review.json`'s schema, the CLI's flags, or exit codes (see the
accepted provenance limitation under Open Risks); diff path filtering; threshold/score calibration
against real PR history; any Java/Gradle change.

## Architecture / Approach

The composite action is self-contained (its own `setup-node`, its own `npm ci`, its own CLI
invocation) and exposes two outputs — `verdict` (status string) and `report-dir` (where
`review.json`/`review.md` landed) — so `review.yml` never needs to know about Node or npm. The
workflow owns everything GitHub-specific: trigger shape, diff computation via `fetch-depth: 0` +
`git merge-base`, concurrency, and the comment/label side-effects branching on the action's outputs.
Promptfoo is added as a sibling concern inside `packages/code-reviewer`, evaluating the actual agent
(via a custom provider wrapping `runStructuredSession`) alongside a native `anthropic:` provider for
raw-model comparison — with no automatic trigger, matching the package's existing cost-conscious
convention.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. Requirements doc | `requirements.md` capturing concept/inputs/criteria/side-effects/retry | Written before design is fully settled and drifts from the shipped YAML |
| 2. Secret & composite action | `ANTHROPIC_API_KEY` secret, `.github/actions/ai-reviewer/action.yml` | `npm ci` path resolution inside a composite action is easy to get wrong |
| 3. Consumer workflow | `.github/workflows/review.yml` triggers, diff, concurrency | Shallow checkout silently produces an empty diff if `fetch-depth: 0` is missed |
| 4. PR side-effects | Comment + label swap/consume logic | Label swap race if the label step isn't `if: always()` after a partial failure |
| 5. Promptfoo suite | Config, custom provider, assertions | ESM/`tsx` loading of the custom provider inside promptfoo's Node runtime is unproven |
| 6. E2E verification | `workflow_dispatch` smoke + real PR proof | Real API cost if debugging happens directly against PR-triggered runs |
| 7. Documentation | `AGENTS.md`/`README.md` updates | Docs update is skipped and the contract looks unconsumed to the next reader |

**Prerequisites:** `ANTHROPIC_API_KEY` provisioned as a repo secret; a branch cut from up-to-date
`main` per the repo's git workflow; [10X-18](https://linear.app/10xnextslope/issue/10X-18/tooling-claude-agent-sdk-code-review-agent-as-an-independent) merged (it is).
**Estimated effort:** ~3-4 sessions across seven phases; Phase 5 (promptfoo) and Phase 6 (E2E
verification against a real PR) carry the most uncertainty.

## Open Risks & Assumptions

- **The promptfoo custom provider's module-loading path is unverified.** `packages/code-reviewer`
  is ESM-only with no build step, running TypeScript directly via `tsx`; whether promptfoo's Node
  runtime can load a provider file that imports `src/agent.ts` under those conditions needs to be
  proven during Phase 5, with a fallback (a thin `.mjs` wrapper) already anticipated in the plan.
- **`npm ci`'s working directory inside a composite action** depends on the action's file location
  relative to repo root; the plan names the intent but the exact relative path must be resolved
  against where `.github/actions/ai-reviewer` actually lands during implementation.
- **Exit code 2 (`no_result`) receiving no label is a plan-time extension**, not something Linear
  10X-19 states explicitly — it follows the same logic as exit 1 but is worth flagging as an
  inference rather than a literal requirement.
- **Verdicts are unattributed, by accepted decision.** `review.json` carries no run provenance —
  resolved model, cost, and turns reach only `--verbose` stdout, and since the workflow passes no
  `--model` it cannot name what ran. PR comments carry the reviewed commit SHA (workflow-side, no
  schema change) but not the model. This becomes genuinely ambiguous once promptfoo starts moving
  the default model, which is the trigger to revisit it.
- **Capturing the CLI's numeric exit code inside the composite action needs care, and is an accepted
  implementation-time risk.** `continue-on-error: true` exposes only `steps.<id>.outcome`
  (`success`/`failure`) and `cmd || true` discards `$?`, so neither can distinguish exit 1 from 2 from
  3. Getting this wrong collapses the four-way `verdict` into pass/fail and silently drops the
  `invalid_input`/`no_result` "no misleading label" behavior — the failure mode to watch for when
  Phase 2 lands (raised in plan review as F6, risk accepted).
- **Review quality remains probabilistic** — inherited from `code-review-agent`; the promptfoo
  suite measures it going forward but doesn't eliminate it, which is exactly why `review.yml` stays
  non-required.

## Success Criteria (Summary)

- A real PR to `main` gets exactly one AI-review comment per push and exactly one current
  pass/fail label, with `ai-cr:review` behaving as a working one-shot retry trigger.
- `./gradlew build` and `ci.yml` behave identically to before this change.
- `npm run promptfoo` runs locally, scoring both the raw model and the actual PR-facing agent
  against the existing fixtures.
