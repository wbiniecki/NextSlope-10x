<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Code Review Agent (`packages/code-reviewer`)

- **Plan**: `context/changes/code-review-agent/plan.md`
- **Scope**: Phase 1 of 6
- **Date**: 2026-08-07
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 3 warnings, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Grounding

Commit under review: `68da6c1` (9 files, +3212). All four planned files verified against their
contracts by an independent drift audit; every contract item returned MATCH, with no MISSING and no
scope creep. Automated criteria 1.1–1.4 re-run at this commit and green; 1.5 (smoke) verified live
earlier in the same session at this same commit, reporting `claude_code_version 2.1.224` on
`claude-sonnet-5` for $0.024. Manual criteria 1.6–1.7 confirmed by the user with observable evidence
in the smoke output, so no rubber-stamping risk.

Two `.gitignore` claims were re-verified directly with `git check-ignore -v` rather than taken on
trust, and the `--import` version floor was confirmed against Node's own CLI documentation.

## Findings

### F1 — A repo-root `.env` holding the API key is not ignored

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: `.gitignore:52-57`
- **Detail**: The new section ignores `packages/**/.env*`, and `git check-ignore -v` confirms
  `packages/code-reviewer/.env` is caught by line 55. But there is no repo-wide rule: both `.env`
  and `.env.local` at the repository root return NOT IGNORED. A root dotenv file is the most common
  way a developer gets `ANTHROPIC_API_KEY` into the environment, so this is a realistic path for the
  credential to land in a commit. This is a gap in the plan's contract, not drift from it — the
  contract specified exactly the five patterns that were implemented.
- **Fix**: Add a repo-wide `.env` / `.env.*` rule with a `!.env.example` carve-out to the Node
  tooling section.
- **Decision**: FIXED — replaced the `packages/`-scoped `packages/**/.env*` with an unanchored
  repo-wide `.env` + `.env.*` pair plus `!.env.example`, in a new `### Secrets ###` section.
  Re-verified with `git check-ignore -v`: root `.env`/`.env.local` and their `packages/` equivalents
  are all ignored, `.env.example` is negated, and the lockfile remains untracked by any rule.

### F2 — The smoke script exits 0 when a session ends without a terminal result

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: `packages/code-reviewer/scripts/smoke.ts:66-71`
- **Detail**: The only post-loop guard is `if (!sawInit)`. There is no matching `sawResult` flag, so
  if the subprocess is killed or the stream closes after emitting `system`/`init` but before a
  terminal `result`, `main()` falls through to `return 0` and the connectivity proof reports success
  for a session that never answered. The script's entire value is a trustworthy pass/fail, and this
  is the one path where it can produce a false green.
- **Fix**: Track a `sawResult` boolean alongside `sawInit` and return 1 when the stream ends without
  a terminal `result` message.
- **Decision**: FIXED — added a `sawResult` flag set on the success arm only (the non-success arm
  already returns 1, so the flag means "a session answered successfully") plus a matching post-loop
  guard returning 1 with a distinct diagnostic. `npm run typecheck` green.

### F3 — `engines.node: ">=18"` understates the floor the scripts actually need

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: `packages/code-reviewer/package.json:8`
- **Detail**: All five scripts invoke `node --import tsx/esm`. Node's CLI documentation records
  `--import` as "Added in: v18.18.0", and the `node:module` `register()` API that `tsx/esm` relies on
  landed in v18.19.0. Node 18.0–18.17 would therefore fail with an unknown-flag error rather than
  npm's clear engine-mismatch message. The `>=18` value came from the plan's contract, written when
  the runner was assumed to be the bare `tsx` binary; the switch to the ESM loader moved the real
  floor without the declared floor following. This matters beyond cosmetics because 10X-19 will pick
  its `setup-node` version from this field.
- **Fix**: Tighten to `">=18.19.0"` (or a current LTS floor such as `">=20.6.0"`).
- **Decision**: FIXED — set `engines.node` to `">=20.6.0"`, the release where both `--import` and
  `node:module` `register()` are non-experimental on a still-supported line. `package-lock.json`
  mirrors the root `engines` field, so it was stale and would have failed `npm ci`'s sync check;
  regenerated with `npm install --package-lock-only`, and the resulting diff is exactly one line with
  no dependency versions moved.

### F4 — `scripts/smoke.ts` is a second SDK importer that Phase 6's wording must account for

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Architecture
- **Location**: `packages/code-reviewer/scripts/smoke.ts:16`
- **Detail**: The plan's architectural spine is "only `src/agent.ts` imports the SDK", and Phase 6
  is contracted to write that rule into `packages/code-reviewer/AGENTS.md`. `smoke.ts` imports
  `@anthropic-ai/claude-agent-sdk` directly, which is correct — Phase 1's contract requires it to
  call `query()`, and Phase 2's own check (2.6) scopes the grep to the `src/` tree, which `scripts/`
  is outside of. No violation exists today. The risk is purely that an unqualified "only
  `src/agent.ts` may import the SDK" sentence in Phase 6 would be false on its face, giving a future
  reviewer (including this agent reviewing itself) a standing spurious finding.
- **Fix**: When Phase 6 writes the rule, scope it to `src/` and name `scripts/smoke.ts` as the
  deliberate exception.
- **Decision**: FIXED — queued into `follow-ups/review-fixes.md` as a Phase 6 entry. Recorded there
  rather than edited into the plan's Phase 6 block because phase blocks are read-only during
  implementation; only the `## Progress` section is mutable.

### F5 — The pre-existing unanchored `bin/` rule silently ignores `packages/code-reviewer/bin/`

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: `.gitignore:16`
- **Detail**: Line 16's `bin/` (from the STS/Eclipse section) is unanchored, so it matches at any
  depth. `git check-ignore -v packages/code-reviewer/bin/run.js` confirms it resolves to
  `.gitignore:16:bin/`. The existing negations only rescue Java paths (`!**/src/main/**/bin/`,
  `!**/src/test/**/bin/`), which never match under `packages/`. No `bin/` directory exists and the
  plan does not call for one — the CLI is reached through npm scripts — so this is latent. It is
  worth recording because the symptom, if it ever bites, is a file silently missing from
  `git status` with no error.
- **Fix**: If a `bin/` wrapper is ever added under `packages/`, add `!/packages/**/bin/` to the Node
  tooling section.
- **Decision**: SKIPPED — latent and speculative; no `bin/` directory exists and the CLI is reached
  through npm scripts. Revisit only if a `bin/` wrapper is introduced under `packages/`.

## Notes

Dismissed during triage of the sub-agent reports, recorded so they are not re-raised:

- **`@anthropic-ai/sdk` and `@modelcontextprotocol/sdk` appear unused.** They are declared peer
  dependencies of the agent SDK and must be installed by the consumer; the plan names all three
  deliberately. Not a finding.
- **Caret ranges on the non-SDK dependencies.** The committed `package-lock.json` (lockfileVersion 3,
  every entry carrying `resolved` + `integrity`) is the reproducibility mechanism, and 10X-19 will
  use `npm ci`. Working as designed.
- **`esbuild` / `fsevents` carry install scripts.** Both are transitive via `tsx`. Notably this
  machine's npm `allow-scripts` policy blocked `esbuild`'s postinstall and the toolchain still worked,
  because the per-platform `@esbuild/darwin-arm64` package supplies the binary directly.
- **`review`, `verify`, and `test` point at files that do not exist yet.** Phase 1's contract only
  required the scripts be declared. Expected — but a green Phase 1 is not evidence those three entry
  points work.
- **`AGENTS.md`'s single-tier hard rule currently contradicts the package's existence.** Phase 6 is
  contracted to add the `packages/` tooling-zone carve-out. Deliberately deferred, tracked, not a
  defect.

Worth carrying into Phase 2: the live smoke printed the default tool surface as `Task, Bash, Edit,
Write, WebFetch, WebSearch, NotebookEdit, Skill, Workflow` and roughly twenty more. That is direct
confirmation of plan-review finding F4 — `disallowedTools` alone would leave most of that reachable,
so the restrictive `tools: ["Read", "Glob", "Grep"]` allowlist is load-bearing rather than
belt-and-braces. Separately, the sandboxed first attempt produced a `subtype: "success"` result whose
text was an authentication error, which is the success-without-usable-output shape the plan predicts;
Phase 2's `structured_output`-presence guard has now been motivated by live evidence rather than docs.
