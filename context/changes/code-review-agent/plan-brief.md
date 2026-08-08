# Code Review Agent (`packages/code-reviewer`) — Plan Brief

> Full plan: `context/changes/code-review-agent/plan.md`
> Upstream scope: Linear [10X-18](https://linear.app/10xnextslope/issue/10X-18/tooling-claude-agent-sdk-code-review-agent-as-an-independent) (authoritative), downstream [10X-19](https://linear.app/10xnextslope/issue/10X-19/cicd-ai-code-review-on-every-pr-to-main-gha-workflow-composite-action)

## What & Why

Build a standalone ESM TypeScript CLI at `packages/code-reviewer/` that takes a unified diff, reviews
it against five criteria derived from NextSlope's own hard rules via the Claude Agent SDK, and emits a
schema-validated JSON verdict plus a readable markdown summary. It runs locally in this change; the
follow-up change puts it on GitHub Actions for every PR to `main`. The point is to make this repo's
written conventions — forward-only portable migrations, `ddl-auto=validate`, constructor injection,
principal-scoped data access, e2e discipline — actually enforced rather than merely documented.

## Starting Point

Nothing exists yet: no `packages/` directory, no Node tooling in the repo, no `ANTHROPIC_API_KEY` in
the environment. Node v26.3.0 and npm 11.16.0 are installed, and `@anthropic-ai/claude-agent-sdk` is
at `0.3.224`, matching what the Linear issue pinned. `settings.gradle` declares a single root project
with no subprojects, so a new top-level directory is invisible to Gradle by default. The repo's
conventions are uniform enough to score against: migrations are `V1`–`V5` (verified live), both
properties files pin `ddl-auto=validate`, production code has zero `@Autowired`, and every owned route
is principal-scoped through `CurrentUserService.requireUserId` with no id in the path.

## Desired End State

A developer with an API key can run `npm run review -- --diff-file <patch>` and get `review.json` and
`review.md` with a meaningful exit code. `npm run verify` runs three fixtures and prints a pass/fail
table proving the planted defects are found, that removing one makes it disappear from the report, and
that a clean diff produces nothing. `./gradlew build` behaves exactly as it does today, and the repo's
own rules no longer contradict the existence of the package.

## Key Decisions Made

| Decision | Choice | Why | Source |
|---|---|---|---|
| Vendor | Claude Agent SDK 0.3.224 | Native structured outputs with SDK-side validation and re-prompting; Cursor SDK returns a plain string | change.md |
| Verdict schema shape | Per-criterion scores **plus** a findings array | Only shape supporting both gates: the break test needs file/line, 10X-19's promptfoo needs score thresholds | Plan |
| Verdict authority | Code computes it from the model's reported facts | Keeps the CI gate reproducible and tunable by editing a constant, not re-evaluating a prompt | Plan |
| Tool posture | Read-only repo access; `disallowedTools` bare names + `permissionMode: 'dontAsk'` | `allowedTools` only auto-approves, it does not restrict | Plan / change.md |
| Criteria authoring | Curated `criteria.md`, each criterion citing its source | `AGENTS.md` instructs an implementer, not a scorer; feeding it raw wastes tokens on Gradle and deploy config | Plan |
| Fixtures | Defect diff + one-defect-removed variant + clean control | A reviewer that flags everything scores perfectly on planted defects alone; the clean control is what catches that | Plan |
| Verification | `npm run verify` for quality, plain unit tests for deterministic logic | Proves the 10X-19 contract at zero API cost, and makes prompt edits measurable | Plan |
| Governance | Root carve-out + nested `packages/code-reviewer/AGENTS.md` | Scoped context: Java-side sessions don't pay for Node conventions | Plan |
| Cost posture | Sonnet-class, `maxTurns: 3`, overridable | Tight cost guard ahead of CI multiplying by PR count | User |

## Scope

**In scope:** the package skeleton and toolchain; the Zod verdict schema and its draft-07 conversion;
`src/agent.ts` as the sole SDK boundary; five source-cited review criteria and prompt assembly; the CLI
with its verdict policy, dual output, and exit-code contract; three fixtures plus the verification
harness; the `AGENTS.md` carve-out and package-local conventions.

**Out of scope:** all GitHub Actions work (workflow, composite action, PR comments, labels); the
promptfoo eval suite; any change to `ci.yml`; Gradle integration; a multi-provider abstraction; reading
the diff from git rather than a file; threshold calibration against real PRs; any change to Java
sources, migrations, or tests.

## Architecture / Approach

One module — `src/agent.ts` — imports the SDK. It takes a prompt and a JSON Schema and returns a
validated object or a typed failure. Everything else is vendor-agnostic and unit-testable with no
network call: criteria loading, prompt assembly, verdict computation, markdown rendering, exit-code
mapping, the CLI. Swapping providers costs one file, which matters because the course lesson puts five
SDKs on the table and the comparison may be revisited. The JSON verdict and the exit codes are treated
as a cross-change contract, since 10X-19 consumes both.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. Skeleton & connectivity | Package, deps, Gradle isolation proof, live `query()` smoke | Native binary or credential fails in a way that looks like a code bug three phases later |
| 2. Schema & SDK boundary | Zod verdict schema, draft-07 conversion, `src/agent.ts` | The `"draft-07"` literal typechecks even when misspelled |
| 3. Criteria & prompt | Five source-cited criteria, prompt assembly | Criteria drift from `AGENTS.md`; nothing enforces the sync |
| 4. CLI & contract | Verdict policy, rendering, exit codes, unit tests | Exit-code contract is wrong and 10X-19 has to rework |
| 5. Fixtures & harness | Three fixtures, expectations, `npm run verify` | Criteria over-trigger and the clean control fails |
| 6. Governance | Root carve-out, nested `AGENTS.md`, README | Root file bloats and stops being readable in one pass |

**Prerequisites:** `ANTHROPIC_API_KEY` provisioned; Node ≥18 (have v26.3.0); a branch cut from
up-to-date `main` per the repo's git workflow.
**Estimated effort:** ~3–4 sessions across six phases; phases 1 and 6 are short, phases 4 and 5 carry
most of the work.

## Open Risks & Assumptions

- **The turn budget and the tool posture pull against each other.** `maxTurns: 3` leaves almost no
  room for file reads, so repo access is optional enrichment rather than the evidence path. This is
  workable because a unified diff header distinguishes a new file from a modification, which is what
  the migration criterion actually needs — but if `error_max_turns` shows up in Phase 5, the choice is
  between raising the default and tightening the prompt.
- **The draft-07 trap's failure mode has changed since the Linear issue was written.** Claude Code
  2.1.205+ fails loudly at startup on an invalid schema; the silent-ignore behavior in issues #105 and
  #227 was the older behavior. The `structured_output` presence assertion is still worth having, but it
  now guards success-without-`structured_output`, which the docs confirm is real.
- **`settingSources` is more dangerous than documented in the change notes.** Its default flipped to
  loading all three sources, so an omitted value would make the verdict depend on whose machine ran it.
- **The verdict threshold is uncalibrated.** It is a documented starting point, not a tuned number, and
  the first real PRs will move it.
- **Review quality is probabilistic.** The harness checks criterion ids, not prose, because prose
  matching would be brittle. A run can regress in ways the harness does not see.

## Success Criteria (Summary)

- Running the CLI against the fixture produces a report whose findings name the defects actually
  planted in the diff, with truthful justifications.
- Removing one defect removes it from the report, and a clean diff produces no findings — together
  proving the agent reads the diff rather than pattern-matching the repo or flagging indiscriminately.
- `./gradlew build` is untouched, and the repo's own rules no longer contradict the package's
  existence — confirmed by running the agent over this change's own diff.
