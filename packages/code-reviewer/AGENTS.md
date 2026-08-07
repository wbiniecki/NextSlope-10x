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
- **`npm run verify` costs money.** Three real API runs per pass. It is a deliberate action, never
  a watch task.

## Commands

- `npm install` — install; the lockfile is committed so CI's `npm ci` is reproducible. Never pass
  `--omit=optional`: the native Claude Code binary ships as a per-platform optional dependency.
- `npm run review -- --diff-file <path>` — review one diff (real API call).
- `npm run verify` — run all fixtures against `fixtures/expectations.json` (three real API calls).
- `npm test` — unit tests via `node --test`; no network calls, none allowed.
- `npm run typecheck` — `tsc --noEmit`.
- `npm run smoke` — smallest possible `query()`, to separate a bad credential from an unresolved
  native binary from a network failure.

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
   in depth and `permissionMode: "dontAsk"` denying the rest.
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

## Cross-Change Contract

`review.json` (validated by `reviewReportSchema`) and the exit codes are consumed by Linear 10X-19
(`ci-cd-code-review`), which puts this package on GitHub Actions. Changing either shape breaks that
consumer.

| Code | Meaning |
|---|---|
| `0` | Run completed, no blocking findings |
| `1` | Invalid invocation or input, or a startup/auth failure before a session ran |
| `2` | A session started but produced no usable result |
| `3` | Run completed, but findings at or above `--fail-on` blocked it |
