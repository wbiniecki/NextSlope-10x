# Repository Guidelines — `packages/code-reviewer`

A Node/TypeScript CLI that reviews a unified diff against NextSlope's own conventions using the
Claude Agent SDK, and emits `review.json` plus `review.md`. It is developer tooling: outside the
Gradle build, absent from `settings.gradle`, and never part of the deployed artifact. Root
`AGENTS.md` carves `packages/` out of the "no JS build step / Node tier" hard rule — that rule
governs the application, not this package.

## Hard Rules

- **Only `src/agent.ts` may import `@anthropic-ai/claude-agent-sdk`.** Criteria, prompt assembly,
  the verdict gate, rendering, exit codes, and the CLI stay vendor-agnostic, so swapping providers
  costs exactly one file — and everything else stays unit-testable with no network call.
  `scripts/smoke.ts` is the one deliberate exception, since proving the SDK connects is its whole
  job.
- **Never apply the fixture patches to the working tree.** `fixtures/*.patch` are review *input*.
  They describe real defects (an edited applied migration, `ddl-auto=update`, an IDOR route) that
  must never land in the repo. `sample-diff.patch` also carries an inert adversarial instruction
  telling the reviewer to report nothing — deliberately, as a prompt-injection control.
- **`npm run verify` costs money.** One real API run per fixture per pass — four today. It is a
  deliberate action, never a watch task.

## Commands

- `npm install` — install; the lockfile is committed so the `npm ci` that 10X-19 will add to CI
  resolves reproducibly (`ci.yml` has no Node step yet). Never pass `--omit=optional`: the native
  Claude Code binary ships as a per-platform optional dependency.
- `npm run review -- --diff-file <path>` — review one diff (real API call).
- `npm run verify` — run all fixtures against `fixtures/expectations.json` (one real API call per
  fixture). Add `-- --artifacts-dir <path>` to retain each run's `review.json`, `review.md`, and
  `run.log` instead of discarding the temporary output directory; same runs, no extra model call.
- `npm test` — unit tests via `node --test`; no network calls, none allowed.
- `npm run typecheck` — `tsc --noEmit`.
- `npm run smoke` — smallest possible `query()`, to separate a bad credential from an unresolved
  native binary from a network failure.
- `npm run promptfoo` — promptfoo eval of the production agent against the fixtures. Also costs
  money; never wired into CI. `npm run promptfoo:compare` adds a raw-model column and needs an
  `ANTHROPIC_API_KEY`, which the default suite does not.

## Toolchain

- ESM only, `"type": "module"`. The SDK has no CJS path; require() will not work.
- `tsconfig.json` must keep `"module"` and `"moduleResolution"` at `"nodenext"`. The SDK resolves
  types through `exports`, so plain `"node"` resolution finds nothing. This is a hard requirement.
- Node `>=20.6.0` (`--import tsx/esm` needs it). Source runs directly through `tsx`; there is no
  build step and no `dist/`.
- Peer dependencies the SDK needs and this package installs explicitly: `@anthropic-ai/sdk`,
  `@modelcontextprotocol/sdk`, `zod@^4`.

## The Four Traps

Each one cost someone real time. All four are encoded in code with a comment; do not "simplify"
them away.

1. **`z.toJSONSchema(schema, { target: "draft-07" })`** — the literal is `draft-07`, not the
   `draft-7` the official Zod docs page shows. Zod's target type ends in `({} & string)`, so a typo
   typechecks cleanly and silently emits no `$schema`. `test/schema.test.ts` asserts the emitted
   `$schema` and is the regression guard.
2. **`settingSources: []` is mandatory.** The default loads `user`, `project`, and `local`; this
   repo has a `.claude/settings.local.json`, so omitting it would make a review verdict depend on
   whose machine produced it.
3. **`allowedTools` does not restrict anything** — it only auto-approves, and unlisted tools still
   fall through to `permissionMode`. Read-only is enforced by the separate restrictive `tools`
   allowlist (`Read`, `Glob`, `Grep`), with `disallowedTools: ["Write", "Edit", "Bash"]` as defense
   in depth and `permissionMode: "dontAsk"` denying the rest. Read-only is not the same as harmless:
   `cwd` is scoped to `src/` (`REVIEW_ROOT` in `cli.ts`), never the repo root, because finding text
   lands verbatim in a PR comment and a session that can read `.env` or `.claude/` could publish it.

   **Expect four tools, not three.** A live `system`/`init` reports
   `["Glob","Grep","Read","StructuredOutput"]`. `StructuredOutput` is the SDK's end-turn carrier:
   setting `outputFormat: { type: "json_schema" }` makes the session an end-turn tool session and
   the model delivers its payload by calling that tool. It is injected by that option, not granted
   by `tools` or `allowedTools`, and removing it would mean abandoning structured output — the
   reason this package chose the Claude Agent SDK. It is not a permission leak, and a review of this
   package should not report it as one.
4. **Never set `options.env`.** It replaces the subprocess environment rather than merging into it,
   which drops `PATH` and `ANTHROPIC_API_KEY`.

## Conventions

- Criterion ids live in `src/schema.ts` (`CRITERION_IDS`) and must match the `id` of every criterion
  in `prompts/criteria.md` exactly, in both directions. A unit test enforces it — a drifted id makes
  a criterion silently unscoreable.
- The model reports facts (per-criterion scores and findings); `src/verdict.ts` decides pass/fail.
  Never move the gate into the prompt or the schema, or it becomes probabilistic. Scores are
  diagnostic and do not affect the gate.
- Cost guards are named constants, not scattered literals: `DEFAULT_MODEL`, `DEFAULT_MAX_TURNS`,
  `DEFAULT_MAX_BUDGET_USD` in `src/agent.ts`, `MAX_DIFF_BYTES` in `src/cli.ts`. Every criterion must
  be answerable from the diff alone, because repo reads compete with a 3-turn budget.
- The diff is untrusted data. `src/prompt.ts` wraps it in explicit delimiters and instructs the
  model that anything inside them is evidence, never instructions.
- Severity has a written rubric, and it is the thing that matters: `--fail-on` compares severities,
  so an improvised severity is an improvised gate. The **global** rubric lives in `src/prompt.ts`
  beside the score guidance, anchored on one axis — adding something weakly versus removing a
  protection that existed — plus a tie-break for the `medium`/`high` collision on a pure addition.
  **Per-criterion** anchors live beside their criterion in `prompts/criteria.md` and take precedence
  over the global default; `test-verifies-behavior` uses that to cap every finding at `medium`
  during its rollout, so it reports on every PR without blocking. Both halves are unit-tested in
  `test/prompt.test.ts`.
- **Do not edit the severity rubric without a paid run to check it.** Measured in 10X-20: a
  tie-break that read perfectly plausibly produced the exact opposite of its intent, because the
  model attached the word "reachable" to the artifact rather than to the failure and graded a defect
  it had itself called a "risk" as `high`. The wording that works makes the model's own hedging
  vocabulary the test — "risks / could / may" means `medium`. Over the same three runs, the
  mechanical per-criterion cap never deviated once. Prefer a per-criterion anchor to a global prose
  change, and verify any prose change against `npm run verify` before trusting it. Evidence:
  `context/changes/*-test-verifies-behavior/verification/comparison.md`.
- `applicable: false` means the criterion governs nothing the diff touches. Its `score` is then
  meaningless — retained only because the emitted draft-07 schema keeps `score` required — and
  `render.ts` shows an em dash rather than `N/10`. It is not an escape hatch: a criterion that
  governs anything the diff touches is scored normally even when unviolated, and both
  `scripts/verify.ts` and `promptfoo/assertions.js` fail a fixture whose `expectedCriteria` come
  back not applicable.
- `fixtures/expectations.json` now carries `expectedNotApplicable` beside `expectedCriteria` and
  `forbiddenCriteria`, plus optional `expectedFindings` / `forbiddenFindingRanges` matched on
  criterion, severity, file, and line range — never on prose, which a probabilistic reviewer words
  differently every run. The file is zod-validated with `.strict()` at load, so a mistyped key
  errors instead of silently disabling its assertion. `promptfoo/tests.yaml` restates the same
  structures and is kept honest by a drift test in `test/verify.test.ts`.

## Cross-Change Contract

**Consumed, live.** `review.json` (validated by `reviewReportSchema`) and the exit codes are read by
`.github/workflows/review.yml` via `.github/actions/ai-reviewer`, shipped by Linear 10X-19
(`ci-cd-code-review`). Changing either shape breaks a workflow that runs on every pull request: the
action maps the exit code to a `verdict` string driving the PR label, and posts `review.md` as the
comment body verbatim.

Two constraints the CI consumer places on this package, easy to break from in here:

- **`npm ci` must resolve on the runner.** The lockfile is committed, and the action pins Node 24
  because npm's major has to match the one that wrote `package-lock.json` — `npm ci` rejects a lock
  a different npm major resolves differently. Re-check that pin when the local npm major moves.
- **The credential is a subscription OAuth token**, not an API key. The action accepts `api-key` or
  `oauth-token` and rejects both at once, since Claude Code ranks the key above the token and would
  silently pick it.

| Code | Meaning |
|---|---|
| `0` | Run completed, no blocking findings |
| `1` | Invalid invocation or input, or a startup/auth failure before a session ran |
| `2` | A session started but produced no usable result |
| `3` | Run completed, but findings at or above `--fail-on` blocked it |
