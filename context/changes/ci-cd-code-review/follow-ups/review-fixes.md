# Follow-ups from the implementation review

Queued from `reviews/impl-review.md` (2026-08-08). Each item was triaged as real but deliberately
not fixed inside the `ci-cd-code-review` branch.

## F4 — Gate `packages/code-reviewer`'s free tests in CI

**Why it was deferred:** the fix belongs in `.github/workflows/ci.yml`, which this change's plan
deliberately left untouched (Phase 3 criterion 3.2, verified by an empty
`git diff --stat -- .github/workflows/ci.yml`). Adding a job here would break that guarantee for no
gain — the work is self-contained and reviews better on its own.

**The problem:** `packages/code-reviewer` is now on every pull request's critical path, but nothing
runs its tests. `ci.yml` has no Node step, and `review.yml` only invokes the CLI. `npm test` is free
and offline, and it guards precisely the invariants the CI integration depends on:

- the per-run nonce that forms the prompt-injection boundary (`src/prompt.ts:13-21`),
- the `draft-07` literal whose typo typechecks cleanly and silently emits no `$schema`
  (Trap 1 in `packages/code-reviewer/AGENTS.md`, guarded by `test/schema.test.ts`),
- the criterion-id lockstep between `prompts/criteria.md` and `CRITERION_IDS`.

Any of those can break and ship today. The failure surfaces as `invalid_input`/`no_result` on live
PRs — or, worse, as a quietly weakened injection boundary — rather than as a red check.

**Proposed shape:** a small job in `ci.yml` running `npm ci && npm test && npm run typecheck` with
`working-directory: packages/code-reviewer`, path-filtered to `packages/code-reviewer/**` so it only
fires when the package changes. Reuse the Node pin and `actions/setup-node` SHA already established
in `.github/actions/ai-reviewer/action.yml`. The paid `verify` and `promptfoo` suites stay manual,
per the package's existing cost posture.

**Consider bundling with F3** (also skipped in triage): the same job is the natural place to prove
that `npm ci --omit=dev --ignore-scripts` still resolves the SDK's per-platform native binary, which
is the verification F3's fix is blocked on.
