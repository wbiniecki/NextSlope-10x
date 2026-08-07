# Code Review Agent (`packages/code-reviewer`) Implementation Plan

## Overview

Build a standalone ESM TypeScript CLI at `packages/code-reviewer/` that takes a unified diff, runs a
Claude Agent SDK session against five review criteria derived from NextSlope's own hard rules, and
emits a schema-validated JSON verdict plus a human-readable markdown summary. It runs locally in this
change; the follow-up change `ci-cd-code-review` (Linear 10X-19) puts it on GitHub Actions for every
PR to `main`.

The package is deliberately outside the Gradle build and outside `src/`. `AGENTS.md`'s "no JS build
step / Node tier" hard rule governs the *application*; this change adds an explicit carve-out naming
`packages/` a tooling zone, so the rule stops colliding with this package on every future review —
including the reviews this agent itself performs.

## Current State Analysis

**Nothing exists yet.** There is no `packages/` directory, no Node tooling in the repo, and
`ANTHROPIC_API_KEY` is not set in the shell. `settings.gradle` declares a single root project
(`rootProject.name = 'nextslope'`) with no subprojects, so a new top-level directory is invisible to
Gradle by default — the isolation requirement is satisfied by *not* acting, but it still needs proving.

**Toolchain is present.** Node v26.3.0 and npm 11.16.0 are installed. `npm view
@anthropic-ai/claude-agent-sdk version` returns `0.3.224`, matching the version the Linear issue
pinned, so the vendor research is current rather than historical.

**The repo has strong, uniform conventions the criteria can key off:**

- Flyway migrations are `V1`–`V5` (verified live with `ls` **and** `git ls-files`, per
  `context/foundation/lessons.md` → "Verify file listings live before correctness-critical decisions").
  `V5__add_resort_version_lock.sql` is the most recent. All five are committed, so all five count as
  "already applied" for the forward-only rule.
- `spring.jpa.hibernate.ddl-auto=validate` in both `application.properties:15` and
  `application-prod.properties:10`, with an inline comment in the prod file explicitly warning against
  `update`.
- Production code is uniformly Lombok `@RequiredArgsConstructor` + `private final` fields. There is
  **no `@Autowired` or `@Inject` anywhere in `src/main/java/`**. The single deviation is
  `AccountController.java:29`, which instantiates `SecurityContextLogoutHandler` inline.
- **There is currently no IDOR surface at all.** Every owned route is principal-scoped through
  `CurrentUserService.requireUserId(principal)`
  (`src/main/java/com/nextslope/user/CurrentUserService.java:22`), and both `ProfileController` and
  `VisitedController` carry class Javadoc stating there is no id in the path and therefore no
  cross-user surface to defend. This matters for the fixture: a planted IDOR defect must *introduce*
  a path param, which is precisely the regression the criterion needs to catch.
- E2E conventions are established by the seed exemplar
  `src/e2eTest/java/com/nextslope/e2e/HtmxSmokeE2eTests.java` — role-based locators for interaction,
  `waitForFunction` never `waitForTimeout`, FK-safe teardown, resorts never deleted.

**Two upstream facts have moved since `change.md` and Linear 10X-18 were written.** Both are recorded
in "Key Discoveries" below because they change what the verification actually proves.

## Desired End State

A developer with `ANTHROPIC_API_KEY` exported can run, from `packages/code-reviewer/`:

```
npm run review -- --diff-file fixtures/sample-diff.patch
```

and get `review.json` (schema-validated, matching the Zod review-report schema) plus a markdown
summary,
with a process exit code of `0`, `1`, `2`, or `3` per the contract below. Running `npm run verify`
executes all three fixtures against a checked-in expectations file and prints a pass/fail table that
demonstrates three things: the planted defects are found, removing one makes it disappear from the
report, and a clean diff produces no findings.

`./gradlew build` behaves exactly as it does today. Root `AGENTS.md` names `packages/` as a tooling
zone; `packages/code-reviewer/AGENTS.md` carries the package-local conventions.

### Key Discoveries:

- **The draft-07 trap's failure mode has flipped, and the verification assertion must be re-aimed.**
  Claude Code 2.1.205+ fails the run at startup with an error naming an invalid schema. The silent
  ignore described in `anthropics/claude-agent-sdk-typescript` issues #105 and #227 was the
  *pre-2.1.205* behavior, and the bundled version here is 2.1.224. Passing
  `z.toJSONSchema(schema, { target: "draft-07" })` remains mandatory — the SDK still does not
  auto-strip or convert `$schema`, and the #105 closing comment explicitly declined to add that. But
  "assert `structured_output` is present" now guards a *different* bug: the docs confirm a run can
  terminate with `subtype: "success"` and no `structured_output` at all.
- **Zod's target string is easy to get wrong and TypeScript will not catch it.** The official docs
  page writes `target: "draft-7"`. Zod v4's type union ends in `({} & string)`, so any string
  typechecks, and an unrecognized target silently emits no `$schema`. `"draft-07"` is the correct
  literal.
- **`settingSources` defaults to loading everything.** The default flipped: omitting it now loads
  `user`, `project`, and `local`, matching CLI defaults. This repo has `.claude/settings.local.json`,
  so an omitted `settingSources` would make a review verdict depend on whose machine it ran on.
  Passing `[]` is load-bearing.
- **`allowedTools` does not restrict.** It only auto-approves; unlisted tools still fall through to
  `permissionMode` / `canUseTool`. The restrictive built-in allowlist is the separate `tools` option.
  Read-only enforcement uses `tools: ["Read", "Glob", "Grep"]`, with matching `allowedTools` for
  approval, `disallowedTools: ["Write", "Edit", "Bash"]` as defense in depth, and
  `permissionMode: 'dontAsk'` to deny anything else.
- **`options.env` replaces rather than merges.** Setting it without spreading `process.env` drops
  `PATH` and the API key. The safe move is not to set it.
- **There is no structured-output retry-count option.** Nothing in the `Options` type controls the
  budget. The only signal is the terminal
  `subtype: "error_max_structured_output_retries"` with `terminal_reason:
  "structured_output_retry_exhausted"`.
- **The SDK is ESM-only with no CJS path**, requires Node ≥18, and has three peer dependencies the
  consumer must install: `@anthropic-ai/sdk >=0.93.0`, `@modelcontextprotocol/sdk ^1.29.0`,
  `zod ^4.0.0`. Because resolution goes through `exports`, `tsconfig` must use
  `moduleResolution: "nodenext"` (or `"bundler"`); plain `"node"` will not find the types.
- **`npm install --omit=optional` breaks binary resolution.** The native Claude Code binary ships as
  one of eight per-platform optional dependencies. `npm ci` does not skip them.
- **A unified diff distinguishes a new file from a modification** (`new file mode` in the header).
  This is why `maxTurns: 3` is workable despite read-only repo access: the "editing an already-applied
  migration" criterion is answerable from the diff header alone, so repo reads are a capability the
  agent may use, not a path it must take.

## What We're NOT Doing

- **No GitHub Actions integration.** No workflow, no composite action, no PR comment, no labels. All
  of that is `ci-cd-code-review` (Linear 10X-19).
- **No promptfoo eval suite.** Also 10X-19. The `npm run verify` harness here is a fixture check, not
  a graded eval with rubrics and model comparison.
- **No change to `.github/workflows/ci.yml`.** The four Gradle gates stay exactly as they are.
- **No Gradle integration.** The package is not added to `settings.gradle`, gets no Gradle task, and
  is not built by `./gradlew build`.
- **No multi-provider abstraction layer.** Vendor isolation means the SDK import lives in exactly one
  file — not that we build a provider interface with two implementations.
- **No review of anything but a supplied diff file.** No `git diff` invocation, no branch comparison,
  no GitHub API. The CLI takes `--diff-file` and nothing else as its input source.
- **No threshold calibration against real PRs.** The initial verdict policy is a documented starting
  point; tuning it against production diffs is follow-up work.
- **No changes to any Java source, migration, or test.** The fixture diffs are `.patch` files that are
  never applied to the working tree.

## Implementation Approach

Six phases, ordered so each one's failure mode is isolated. Phase 1 proves the toolchain and the API
credential before any logic exists, because a bad key or an unresolved native binary would otherwise
surface as a mysterious failure three phases later. Phase 2 establishes the SDK boundary and the
schema — the two places where the documented traps live. Phases 3 and 4 build the vendor-agnostic
middle (criteria, prompt, verdict policy, rendering, exit codes), all of which is unit-testable with
no API calls. Phase 5 builds the fixtures and the harness that prove review quality. Phase 6
documents what exists, last, so the conventions describe the finished package rather than an intended
one.

The architectural spine is that **only `src/agent.ts` imports the SDK.** It accepts a prompt and a
JSON Schema and returns a validated object or a typed failure. Everything else — criteria loading,
prompt assembly, verdict computation, markdown rendering, exit-code mapping, the CLI — is
vendor-agnostic and testable without a network call. 10X-19 consumes the JSON verdict and the exit
codes, so those two surfaces are treated as a cross-change contract.

## Critical Implementation Details

**Turn budget interacts with tool posture.** `maxTurns: 3` is one deliberate cost guard, but it does
not cap the size or cost of a single request. The CLI therefore rejects diffs larger than 200,000
bytes before starting a session, pins `claude-sonnet-5` as the default model, and passes
`maxBudgetUsd: 0.50`. Read-only repo access still needs turns to be useful, so the two pull against
each other. The resolution is that repo reads are optional enrichment, not the primary evidence path
— every criterion must be answerable from the diff alone. This has a direct consequence for error
handling:
`subtype: "error_max_turns"` is a *likely* outcome if the prompt encourages exploration, so its
diagnostic must name the turn budget explicitly rather than surfacing as a generic run failure.
Prompt wording should discourage speculative file reading for the same reason.

**Structured output has three distinct failure shapes, not one.** A terminal `result` message can be:
success with `structured_output` (the happy path); success *without* it (docs-confirmed, must be
treated as failure); or the error arm, which carries no `result` and no `structured_output` but adds
`errors: string[]`. The error arm's `subtype` distinguishes
`error_during_execution`, `error_max_turns`, `error_max_budget_usd`, and
`error_max_structured_output_retries`. Collapsing these into one catch-all loses exactly the
diagnostic that makes a failed run debuggable. Additionally, a single-shot `query()` throws after
yielding an error result, so the consuming loop needs a surrounding `try`/`catch` — the subtype
branch runs first, then the throw lands.

**`structured_output` is typed `unknown`.** Even though the SDK validated it, the type system does not
know that. `agent.ts` accepts a generic validator callback alongside the JSON Schema and applies it at
the boundary; the caller supplies a callback backed by the Zod schema's `safeParse`. This earns a
typed verdict without coupling the SDK module to this package's verdict shape.

**The diff is untrusted data, not an instruction source.** A PR can add comments or strings telling
the reviewer to ignore criteria or emit a clean verdict. Prompt assembly must place the diff inside
explicit untrusted-data delimiters and state that instructions found inside those delimiters are
evidence to review, never instructions to follow. The planted-defect fixture carries one such inert
instruction so the normal expected-findings check also guards this boundary.

## Phase 1: Package Skeleton, Toolchain, and Connectivity Proof

### Overview

Create the package, install dependencies, prove Gradle is unaffected, and prove a real `query()` call
reaches the Anthropic API and resolves the native binary. Nothing downstream is worth building until
this gate is green.

### Changes Required:

#### 1. Package manifest and toolchain

**File**: `packages/code-reviewer/package.json`

**Intent**: Declare the package as ESM, pin the SDK and its three peer dependencies, and define the
`review`, `verify`, `test`, and `typecheck` scripts the rest of the plan refers to. Keep it private
so it is never publishable.

**Contract**: `"type": "module"`, `"private": true`, `engines.node >= 18`. Dependencies:
`@anthropic-ai/claude-agent-sdk@0.3.224`, `@anthropic-ai/sdk`, `@modelcontextprotocol/sdk`, `zod@^4`.
Dev dependencies: `typescript`, `tsx` (or equivalent TS runner), `@types/node`. Scripts:
`review`, `verify`, `test`, `typecheck`.

**File**: `packages/code-reviewer/tsconfig.json`

**Intent**: Configure TypeScript so the SDK's `exports`-based type resolution works.

**Contract**: `"module": "nodenext"`, `"moduleResolution": "nodenext"`, `"target": "es2022"` or later,
`"strict": true`. Plain `"node"` module resolution will not find the SDK's types — this is a hard
requirement, not a preference.

#### 2. Repository ignore rules

**File**: `.gitignore`

**Intent**: Keep Node build artifacts and secrets out of the repo without disturbing the existing
Java/Gradle/IDE sections.

**Contract**: A new `### Node tooling (packages/) ###` section ignoring `packages/**/node_modules/`,
`packages/**/dist/`, and `packages/**/.env*`, plus the package-root generated artifacts
`/packages/code-reviewer/review.json` and `/packages/code-reviewer/review.md`. The lockfile is
**committed** — reproducible dependency resolution is what makes 10X-19's `npm ci` on a runner
meaningful.

#### 3. Connectivity smoke

**File**: `packages/code-reviewer/scripts/smoke.ts`

**Intent**: A throwaway-but-committed script that runs the smallest possible `query()` and prints the
`system`/`init` message plus the terminal result. Its job is to separate three failure modes that look
identical from a distance: missing/invalid API key, unresolved native binary, and network failure.

**Contract**: Reads `ANTHROPIC_API_KEY` from the environment (the SDK has no `apiKey` option — it
spawns the native binary as a subprocess which reads env). Logs `apiKeySource` and
`claude_code_version` off the `system`/`init` message, since `apiKeySource` reveals which credential
actually won if several are in scope. Does **not** set `options.env`.

### Success Criteria:

#### Automated Verification:

- `npm install` in `packages/code-reviewer/` completes and produces a committed lockfile
- `npm run typecheck` passes
- `./gradlew build` succeeds and its output is unchanged from before the package existed
- `git status` shows no `node_modules/` or `dist/` as untracked
- The smoke script exits 0 and prints a non-empty `claude_code_version`

#### Manual Verification:

- `apiKeySource` in the smoke output confirms the intended credential was used
- The reported `claude_code_version` is `2.1.224` or later (the version whose invalid-schema behavior
  this plan assumes)

**Implementation Note**: After completing this phase and all automated verification passes, pause here
for manual confirmation from the human before proceeding.

---

## Phase 2: Verdict Schema and the Vendor-Isolated Agent Module

### Overview

Define the JSON contract that 10X-19 will consume, and build the single module that touches the SDK.
Both documented traps are encoded here.

### Changes Required:

#### 1. Verdict schema

**File**: `packages/code-reviewer/src/schema.ts`

**Intent**: Define the Zod v4 model-output schema, its draft-07 JSON Schema form, and the enriched
review-report schema written to disk. The review-report shape is the cross-change contract with
10X-19, so it is deliberate rather than convenient.

**Contract**: An object with `criteria` — an array of five entries, each `{ id, score, justification }`
where `score` is an integer 1–10 and `id` is an enum of the five criterion identifiers — and
`findings`, an array of `{ file, line, criterionId, severity, message }` with `severity` enumerated as
`low`, `medium`, `high`, or `critical` in ascending order. The `criteria` array supports 10X-19's
diagnostic score-threshold assertions; `findings` supports the
deliberate-break check and, later, file-anchored PR comments. Note the model does **not** emit an
overall verdict — that is computed in Phase 4.

Export `reviewReportSchema` by extending `verdictSchema` with the deterministic gate fields
`passed: boolean` and `reasons: string[]`. `verdictSchema` validates model output;
`reviewReportSchema` validates `review.json`.

The draft-07 conversion is the trap:

```ts
export const verdictJsonSchema = z.toJSONSchema(verdictSchema, { target: "draft-07" });
```

The literal must be `"draft-07"`, not the `"draft-7"` the official docs page shows. Zod's target type
ends in `({} & string)`, so a typo typechecks and silently emits no `$schema`.

Avoid `z.string().email()` and similar format refinements at this boundary — the SDK accepts `format`
as an annotation but does not enforce it, so a refinement here creates a false sense of validation.

#### 2. The SDK boundary

**File**: `packages/code-reviewer/src/agent.ts`

**Intent**: The only module in the package that imports `@anthropic-ai/claude-agent-sdk`. It takes a
prompt string and a JSON Schema, runs one session, and returns either the validated structured output
or a typed failure describing which of the several distinct failure shapes occurred.

**Contract**: Exports a generic async function taking
`{ prompt, jsonSchema, validate, model, maxTurns, maxBudgetUsd, cwd }`, where `validate` turns
`unknown` into either typed `T` or a validation failure. It returns a discriminated union of success
(carrying `T` plus `total_cost_usd`, `num_turns`, and `modelUsage` for cost logging) or failure
(carrying a failure kind and a human-readable diagnostic).

Session options — every one of these is top-level in `options`, not nested:

- `outputFormat: { type: 'json_schema', schema: jsonSchema }`
- `settingSources: []` — mandatory; the default now loads `user`, `project`, and `local`
- `tools: ["Read", "Glob", "Grep"]` — the restrictive built-in-tool allowlist; optional repo
  cross-referencing needs no other tool
- `allowedTools: ["Read", "Glob", "Grep"]` — auto-approves the available read-only tools; this does
  not restrict by itself
- `disallowedTools: ["Write", "Edit", "Bash"]` — bare names, removing mutation-capable tools from
  context as defense in depth
- `permissionMode: 'dontAsk'` — denies anything not pre-approved
- `maxTurns` — default 3 (see Critical Implementation Details)
- `model` — default `claude-sonnet-5`; callers may override it explicitly
- `maxBudgetUsd` — default `0.50`
- `cwd` — repo root, enabling optional read-only cross-referencing
- `systemPrompt` — a plain string, which *replaces* the default entirely. There is no way to append
  to a custom string prompt; `append` exists only on the `{ type: 'preset' }` form.

Do **not** set `options.env` — it replaces rather than merges the subprocess environment.

Failure kinds must distinguish, at minimum: startup/auth failure, `error_max_turns`,
`error_max_budget_usd`, `error_max_structured_output_retries`, `error_during_execution`, and
success-without-`structured_output`. The turn- and budget-limit diagnostics name their configured
limits. The consuming `for await` loop is wrapped in `try`/`catch` because a single-shot `query()`
throws after yielding an error result.

Re-validate `structured_output` with the supplied validator before returning — it is typed `unknown`.
The verdict caller's validator wraps `verdictSchema.safeParse`; `agent.ts` does not import
`verdictSchema`.

### Success Criteria:

#### Automated Verification:

- `npm run typecheck` passes
- A unit test asserts `verdictJsonSchema.$schema === "http://json-schema.org/draft-07/schema#"` —
  this is the regression guard for the typo trap, and it needs no API call
- Unit tests assert `verdictSchema` rejects malformed model output and accepts a well-formed verdict,
  and `reviewReportSchema` rejects a report missing its computed gate fields
- A live run against a trivial prompt returns a parsed object with `structured_output` present; its
  `system`/`init` message reports only `Read`, `Glob`, and `Grep` as available tools and reports
  `claude-sonnet-5` as the resolved model

#### Manual Verification:

- Deliberately breaking the target to `"draft-2020-12"` produces a visibly different failure than a
  normal run, and the `$schema` assertion catches it
- Confirm no module other than `src/agent.ts` imports the SDK (`grep` the `src/` tree)

**Implementation Note**: After completing this phase and all automated verification passes, pause here
for manual confirmation from the human before proceeding.

---

## Phase 3: Review Criteria and Prompt Assembly

### Overview

Write the five criteria as a curated, source-cited document, and assemble the prompt from criteria
plus diff. Both are vendor-agnostic.

### Changes Required:

#### 1. Review criteria

**File**: `packages/code-reviewer/prompts/criteria.md`

**Intent**: Five criteria written *for a reviewer scoring a diff*, not copied from `AGENTS.md` (which
is written to instruct an implementer and is mostly Gradle commands and deploy config). Each criterion
carries a stable `id` matching the schema enum, a description of what a violation looks like, and a
`Source:` line citing the `AGENTS.md` section or `lessons.md` rule it encodes.

**Contract**: The five criteria and their sources:

| id | Criterion | Source |
|---|---|---|
| `flyway-forward-only` | Migrations are forward-only and portable across H2 (PostgreSQL mode) and Postgres. Editing an applied `V{n}__` file, or Postgres-only DDL such as `SERIAL` / `AUTO_INCREMENT`, is a violation. | `AGENTS.md` → Persistence & Migrations |
| `ddl-auto-validate` | `spring.jpa.hibernate.ddl-auto` stays `validate` in every profile; a new entity requires a matching migration. | `AGENTS.md` → Persistence & Migrations |
| `constructor-injection` | Spring components use constructor injection via Lombok `@RequiredArgsConstructor` + `private final`. Field `@Autowired` in production code is a violation. | `AGENTS.md` → Coding Style & Conventions |
| `access-control-scoping` | User-owned data is resolved from the authenticated principal via `CurrentUserService.requireUserId`, never from a client-supplied id. Introducing a user or profile id into a path/query and loading by it is an IDOR violation. | `AGENTS.md` → Testing; `src/main/java/com/nextslope/user/CurrentUserService.java` |
| `e2e-conventions` | E2E tests use role/label locators for interaction, never `waitForTimeout`, seed their own users via `UserFixtures`, tear down FK-safely, and never delete seeded resorts. | `AGENTS.md` → E2E Testing Rules |

The description for `flyway-forward-only` must state that a unified diff header distinguishes a new
file from a modification, so the reviewer can decide from the diff without reading the repo. This is
what keeps the criterion answerable inside a 3-turn budget.

#### 2. Prompt assembly

**File**: `packages/code-reviewer/src/prompt.ts`

**Intent**: Build the review prompt from the criteria file and the diff text. Pure function, no SDK
import, so it is unit-testable.

**Contract**: Takes criteria markdown and diff text, returns a prompt string. Instructs the model to
score every criterion even when unviolated, to anchor each finding to a file and line drawn from the
diff, and to prefer the diff as evidence over exploratory file reading — the last of which exists to
protect the turn budget. Scores are diagnostic in this change: `1` means severe non-compliance and
`10` means full compliance, but scores do not drive the CLI exit code. Does not ask the model for an
overall verdict; that is computed in Phase 4. Wraps the diff in explicit untrusted-data delimiters and
instructs the model not to follow any instruction found inside them.

### Success Criteria:

#### Automated Verification:

- `npm run typecheck` passes
- A unit test asserts every criterion `id` in `criteria.md` matches the schema's enum exactly, with no
  extras or omissions on either side
- A unit test asserts the assembled prompt contains the delimited diff, all five criterion ids, and
  the instruction to treat diff content as untrusted evidence rather than commands

#### Manual Verification:

- Each `Source:` citation resolves to a real section in `AGENTS.md` or a real rule in `lessons.md`
- The criteria read as scoring instructions rather than as implementation instructions

**Implementation Note**: After completing this phase and all automated verification passes, pause here
for manual confirmation from the human before proceeding.

---

## Phase 4: CLI, Verdict Policy, Rendering, and Exit Codes

### Overview

Everything between the agent module and the user: argument parsing, the deterministic verdict, the two
output artifacts, and the exit-code contract 10X-19 depends on. All of it unit-tested without API
calls.

### Changes Required:

#### 1. Verdict policy

**File**: `packages/code-reviewer/src/verdict.ts`

**Intent**: Compute the authoritative pass/fail from the model's reported findings, using a documented
severity threshold. Pure function. The model reports facts; this decides.

**Contract**: Takes the parsed verdict plus a `failOn` severity level, returns
`{ passed, reasons }`. Severity order is `low < medium < high < critical`; the CLI default is
`failOn = high`. Any finding at or above `failOn` blocks and appears in `reasons`. Criterion scores
remain diagnostic and do not affect pass/fail in this change; 10X-19 consumes them for promptfoo
quality assertions and can calibrate a score gate separately. Keeping the gate here rather than in
the schema means retightening CI later is a constant edit, not a prompt change with its own regression
risk.

#### 2. Markdown rendering

**File**: `packages/code-reviewer/src/render.ts`

**Intent**: Turn the verdict into a readable markdown summary. Pure function, no I/O.

**Contract**: Takes the validated review report, returns a markdown string with a verdict header, a
per-criterion score table, and findings grouped by file with line anchors. The output must be readable
as a PR comment body, since that is exactly what 10X-19 will do with it.

#### 3. CLI entry point

**File**: `packages/code-reviewer/src/cli.ts`

**Intent**: Parse arguments, read the diff, drive the agent, write both artifacts, and exit with the
contract code.

**Contract**: Flags `--diff-file <path>` (required), `--verbose`, `--model <id>` (defaults to
`claude-sonnet-5`), `--max-budget-usd <number>` (defaults to `0.50`),
`--fail-on <low|medium|high|critical>` (defaults to `high`), `--out <dir>` (defaults to cwd). Builds
and validates `reviewReportSchema` from the raw verdict plus computed `passed`/`reasons`, then writes
it as `review.json` so a consumer never has to re-derive the gate; also writes `review.md`.

Reject a diff larger than 200,000 bytes before prompt assembly or session startup, with a diagnostic
that states the actual and maximum byte counts. Keep the model, budget, and byte limit as named
constants rather than scattering literals.

Exit codes — this is the cross-change contract with 10X-19:

| Code | Meaning |
|---|---|
| `0` | Run completed, no blocking findings |
| `1` | Invalid invocation/input or startup/auth failure before a valid session result — including a missing, unreadable, or oversized diff |
| `2` | Session started but produced no usable result — including schema validation exhausted after retries, `error_max_turns`, `error_max_budget_usd`, and success-without-`structured_output` |
| `3` | Run completed but blocking findings exceeded `--fail-on` |

`--verbose` logs configured and resolved model, configured budget, diff byte count, `num_turns`,
`total_cost_usd`, and `modelUsage`. The docblocks note `usage` covers only the main agent loop while
`modelUsage` covers the whole pipeline, so `modelUsage` is the field to log for cost accounting.

### Success Criteria:

#### Automated Verification:

- `npm run typecheck` passes
- `npm test` passes with unit tests covering: finding severities at, above, and below the `failOn`
  threshold, proving criterion scores do not alter the gate; exit-code mapping for all four codes
  including each distinct agent failure kind; markdown rendering of a verdict with and without
  findings
- Tests assert missing, unreadable, and oversized `--diff-file` inputs exit `1` without attempting a
  session
- No test in this phase makes a network call

#### Manual Verification:

- `review.md` reads well enough to paste into a PR comment unedited
- `--verbose` output makes the per-run cost legible

**Implementation Note**: After completing this phase and all automated verification passes, pause here
for manual confirmation from the human before proceeding.

---

## Phase 5: Fixtures and the Verification Harness

### Overview

Build the three fixture diffs and the harness that proves review quality: that planted defects are
found, that removing one makes it disappear, and that a clean diff produces nothing.

### Changes Required:

#### 1. Planted-defect fixture

**File**: `packages/code-reviewer/fixtures/sample-diff.patch`

**Intent**: A realistic unified diff against this repo, carrying at least one planted defect per
criterion.
Never applied to the working tree — it exists only as review input.

**Contract**: Six planted defects mapped to all five criteria, each grounded in the repo's verified
state:

| Defect | Criterion | Grounding |
|---|---|---|
| Modify the committed `V3__create_preference_profiles.sql` in place | `flyway-forward-only` | `V1`–`V5` are all tracked (verified with `git ls-files`), so `V3` is unambiguously applied. The diff header shows a modification, not `new file mode`. |
| Add `V6__…` using `SERIAL` / a Postgres-only type | `flyway-forward-only` | Existing migrations use `BIGINT GENERATED BY DEFAULT AS IDENTITY`; `SERIAL` will not parse in H2 PostgreSQL mode |
| Flip `ddl-auto` to `update` in a properties file | `ddl-auto-validate` | Both properties files pin `validate`, and the prod file's comment warns against exactly this |
| Add field injection with `@Autowired` to a production Spring component | `constructor-injection` | Production components use constructor injection; there is no field `@Autowired` anywhere in `src/main/java/` |
| Introduce `GET /profile/{userId}` loading via `findById(userId)` | `access-control-scoping` | Replaces the principal-scoped `requireUserId(principal)` → `findByUserId(userId)` pattern; the repo has no such surface today, so this is a genuine regression |
| Add `page.waitForTimeout(...)` to an E2E test | `e2e-conventions` | The E2E rules prohibit fixed sleeps and the current E2E suite contains none |

The expectations file, not the diff, is the source of truth for what counts as planted.
The fixture also includes an inert comment instructing the reviewer to ignore the criteria and report
no findings. This is adversarial input, not a seventh criterion defect; the existing expectations
prove it does not suppress the six planted defects.

#### 2. Break variant

**File**: `packages/code-reviewer/fixtures/sample-diff-broken.patch`

**Intent**: Identical to `sample-diff.patch` with exactly one defect removed, making the deliberate
break reproducible by anyone rather than an ad-hoc edit at verification time.

**Contract**: Remove the IDOR defect specifically. It is the one least inferable from repo context —
the repo currently has no such route at all, so if the agent still reports it, the report is coming
from somewhere other than the diff. That is precisely the failure the deliberate break exists to
detect.

#### 3. Clean control

**File**: `packages/code-reviewer/fixtures/clean-diff.patch`

**Intent**: A realistic diff that violates nothing, as a false-positive control.

**Contract**: Something in the house style — a new `@Service` with `@RequiredArgsConstructor` and a
forward-only `V6__` migration using portable DDL. Without this, a degenerate reviewer that flags
everything scores perfectly against the planted-defect fixture and nothing in the harness notices.

#### 4. Expectations and harness

**File**: `packages/code-reviewer/fixtures/expectations.json`

**Intent**: Declare, per fixture, which criterion ids must appear in `findings` and which must not.

**Contract**: Per fixture, exact `expectedCriteria` and `forbiddenCriteria` arrays:

- `sample-diff`: expects all five criterion ids and forbids none.
- `sample-diff-broken`: expects `flyway-forward-only`, `ddl-auto-validate`,
  `constructor-injection`, and `e2e-conventions`; forbids `access-control-scoping`.
- `clean-diff`: expects none and forbids all five criterion ids.

**File**: `packages/code-reviewer/scripts/verify.ts`

**Intent**: Run each fixture through the CLI and diff observed criterion ids against expectations,
printing a pass/fail table.

**Contract**: Runs fixtures sequentially with an isolated temporary `--out` directory per fixture.
Treats CLI exits `0` and `3` as completed review runs and parses their `review.json`; exits `1` and `2`
are harness execution failures. It exits non-zero if any fixture's expectations fail, prints
per-fixture expected/observed/missing/unexpected plus the CLI exit code, cleans the temporary output
directories, and prints a total cost line. Matching is on criterion ids present in `findings`, not on
prose — prose matching would be brittle against a probabilistic reviewer.

### Success Criteria:

#### Automated Verification:

- `npm run verify` passes all three fixtures, including all expected findings despite the adversarial
  instruction embedded in `sample-diff.patch`
- The harness exits non-zero when an expectation is unmet (verify by temporarily corrupting one
  expectation)
- `sample-diff.patch` applies cleanly with `git apply --check` against `main` — proving it is a real
  diff against this repo, not a plausible-looking fabrication

#### Manual Verification:

- Findings on `sample-diff.patch` name the actual defects rather than generic concerns, and the
  justifications are truthful about what the diff contains
- The IDOR finding is absent from the `sample-diff-broken` run — the deliberate break
- `clean-diff.patch` produces no findings; if it produces some, the criteria are over-triggering and
  need tightening before this phase closes
- Per-run cost and turn count are within the intended budget; if `error_max_turns` appears, decide
  between raising the default and tightening the prompt; no run exceeds the configured `$0.50`
  budget

**Implementation Note**: After completing this phase and all automated verification passes, pause here
for manual confirmation from the human before proceeding.

---

## Phase 6: Governance — Tooling-Zone Carve-Out and Package Conventions

### Overview

Make the repo's own rules consistent with what now exists. This lands last so the conventions describe
the finished package rather than an intended one.

### Changes Required:

#### 1. Root hard-rule carve-out

**File**: `AGENTS.md`

**Intent**: Amend the "Single-tier stack" hard rule so it governs the application rather than the
repository, and place `packages/` in Project Structure. Without this, the rule collides with this
package on every future review — including the ones this agent performs on itself.

**Contract**: The hard rule gains a clause scoping it to the application and naming `packages/` as a
developer-tooling zone that is outside the Gradle build and not part of the deployed artifact. Project
Structure gains one line for `packages/code-reviewer/` pointing at its own `AGENTS.md`. Keep the edit
surgical — the root file is already ~9.8KB and its value depends on staying readable in one pass.

#### 2. Package-local conventions

**File**: `packages/code-reviewer/AGENTS.md`

**Intent**: Package-scoped guidance that agents pick up automatically when working inside
`packages/code-reviewer/`, keeping Node/TypeScript specifics out of the root file where every
Java-side session would pay context for them.

**Contract**: Covers the ESM-only constraint and why `moduleResolution: nodenext` is required; the
commands (`review`, `verify`, `test`, `typecheck`); the rule that only `src/agent.ts` may import the
SDK, and why; the four traps (`draft-07` literal, `settingSources: []`, `disallowedTools` over
`allowedTools` plus the restrictive `tools` allowlist, `options.env` replacing rather than merging);
the exit-code contract and the fact that 10X-19 consumes it; and the instruction never to apply the
fixture patches to the working tree.

#### 3. Package README

**File**: `packages/code-reviewer/README.md`

**Intent**: Human-facing quickstart — what it does, how to run it, what the outputs mean.

**Contract**: Prerequisites (Node ≥18, `ANTHROPIC_API_KEY`), install, the `review` and `verify`
invocations, the default model / 200,000-byte diff limit / `$0.50` budget and their supported
overrides, a description of `review.json` and `review.md`, and the exit-code table.

#### 4. Change identity

**File**: `context/changes/code-review-agent/change.md`

**Intent**: Reflect the completed state.

**Contract**: `status: implemented`, `updated:` set to the completion date.

### Success Criteria:

#### Automated Verification:

- `./gradlew build` still succeeds, confirming the carve-out is documentation-only
- `npm run verify` still passes, confirming Phase 6 changed no behavior

#### Manual Verification:

- Re-reading the amended hard rule, it is unambiguous that a reviewer should not flag
  `packages/code-reviewer` for existing
- Running the agent over this change's own diff does not produce a "no JS build step" violation — the
  self-referential test that the carve-out actually worked
- Root `AGENTS.md` still reads cleanly in one pass and has not grown a Node section

**Implementation Note**: After completing this phase and all automated verification passes, pause here
for manual confirmation from the human.

---

## Testing Strategy

### Unit Tests (no API calls):

- `$schema` is exactly `http://json-schema.org/draft-07/schema#` — the regression guard for the Zod
  target typo, which TypeScript cannot catch
- Verdict schema accepts a well-formed verdict and rejects malformed ones
- Criterion ids in `criteria.md` and the schema enum match exactly in both directions
- Verdict policy at, above, and below threshold
- Exit-code mapping for all four codes: invocation/input/startup/auth failures map to `1`, and each
  failure after session startup maps to `2`
- Markdown rendering with and without findings
- Missing, unreadable, and oversized `--diff-file` inputs exit `1` without starting a session

### Integration Tests (real API calls, via `npm run verify`):

- `sample-diff.patch` → all expected criterion ids present in `findings`
- The adversarial instruction embedded in `sample-diff.patch` does not suppress those findings
- `sample-diff-broken.patch` → `access-control-scoping` absent (the deliberate break)
- `clean-diff.patch` → no findings (the false-positive control)

### Manual Testing Steps:

1. Export `ANTHROPIC_API_KEY`, run the Phase 1 smoke script, confirm `apiKeySource` and
   `claude_code_version`.
2. Run the CLI against `sample-diff.patch` with `--verbose`; read `review.md` end to end and check the
   justifications describe defects that are actually in the diff.
3. Temporarily change the Zod target to `"draft-2020-12"` and confirm the run fails visibly and the
   unit test catches it. Revert.
4. Temporarily corrupt one entry in `expectations.json` and confirm `npm run verify` exits non-zero.
   Revert.
5. Run the agent over this change's own diff and confirm no "no JS build step" violation.

## Performance Considerations

Cost, not latency, is the constraint — every run is a per-token Anthropic charge, and 10X-19
multiplies it by PR count. The guards are a 200,000-byte input limit, `maxTurns: 3`, and
`maxBudgetUsd: 0.50`; the default model is pinned to `claude-sonnet-5`. `--verbose` logging of the
configured/resolved model, budget, `modelUsage`, and `total_cost_usd` turns the eventual CI budget
into a measurement rather than a guess. A full `npm run verify` pass is three API runs, so it is a
deliberate action, not something to wire into a file watcher.

## Migration Notes

Not applicable — no schema or data changes. The fixture `.patch` files describe migrations but are
never applied.

## References

- Linear [10X-18](https://linear.app/10xnextslope/issue/10X-18/tooling-claude-agent-sdk-code-review-agent-as-an-independent) — authoritative scope for this change
- Linear [10X-19](https://linear.app/10xnextslope/issue/10X-19/cicd-ai-code-review-on-every-pr-to-main-gha-workflow-composite-action) — downstream CI change consuming this package's JSON verdict and exit codes
- `context/changes/code-review-agent/change.md` — vendor decision and design constraints
- `AGENTS.md` → Hard Rules, Persistence & Migrations, Coding Style, E2E Testing Rules — criteria sources
- `context/foundation/lessons.md` — criteria sources; also the live-file-listing rule applied to `V1`–`V5`
- `src/main/java/com/nextslope/user/CurrentUserService.java:22` — the ownership pattern the IDOR criterion defends
- `src/main/java/com/nextslope/web/ProfileController.java:22` — Javadoc stating there is no cross-user surface
- `src/e2eTest/java/com/nextslope/e2e/HtmxSmokeE2eTests.java` — E2E conventions the criterion encodes
- `anthropics/claude-agent-sdk-typescript` issues #105 and #227 — the draft-07 trap, and its changed failure mode

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Package Skeleton, Toolchain, and Connectivity Proof

#### Automated

- [x] 1.1 `npm install` completes and produces a committed lockfile — 68da6c1
- [x] 1.2 `npm run typecheck` passes — 68da6c1
- [x] 1.3 `./gradlew build` succeeds with unchanged output — 68da6c1
- [x] 1.4 `git status` shows no `node_modules/` or `dist/` untracked — 68da6c1
- [x] 1.5 Smoke script exits 0 and prints a non-empty `claude_code_version` — 68da6c1

#### Manual

- [x] 1.6 `apiKeySource` confirms the intended credential was used — 68da6c1
- [x] 1.7 `claude_code_version` is 2.1.224 or later — 68da6c1

### Phase 2: Verdict Schema and the Vendor-Isolated Agent Module

#### Automated

- [x] 2.1 `npm run typecheck` passes — 2ce063d
- [x] 2.2 Unit test asserts `$schema` is `http://json-schema.org/draft-07/schema#` — 2ce063d
- [x] 2.3 Unit tests validate model-output and enriched review-report schema boundaries — 2ce063d
- [x] 2.4 Live run returns parsed output with the read-only tools and pinned model — dc478b0

#### Manual

- [x] 2.5 Breaking the target to `draft-2020-12` fails visibly and the `$schema` test catches it — dc478b0
- [x] 2.6 No module other than `src/agent.ts` imports the SDK — 2ce063d

### Phase 3: Review Criteria and Prompt Assembly

#### Automated

- [x] 3.1 `npm run typecheck` passes — a72f0fa
- [x] 3.2 Unit test asserts criterion ids in `criteria.md` and the schema enum match exactly — a72f0fa
- [x] 3.3 Unit test asserts the prompt contains all criteria and delimits the diff as untrusted data — a72f0fa

#### Manual

- [x] 3.4 Every `Source:` citation resolves to a real `AGENTS.md` section or `lessons.md` rule — a72f0fa
- [x] 3.5 Criteria read as scoring instructions, not implementation instructions — a72f0fa

### Phase 4: CLI, Verdict Policy, Rendering, and Exit Codes

#### Automated

- [x] 4.1 `npm run typecheck` passes — c5eb32b
- [x] 4.2 `npm test` passes: verdict policy, exit-code mapping, markdown rendering — c5eb32b
- [x] 4.3 Invalid diff inputs exit `1` without attempting a session — c5eb32b
- [x] 4.4 No test in this phase makes a network call — c5eb32b

#### Manual

- [x] 4.5 `review.md` is readable enough to paste into a PR comment unedited — c5eb32b
- [x] 4.6 `--verbose` output makes per-run cost legible — c5eb32b

### Phase 5: Fixtures and the Verification Harness

#### Automated

- [x] 5.1 `npm run verify` passes all fixtures despite the embedded adversarial instruction
- [x] 5.2 Harness exits non-zero when an expectation is corrupted
- [x] 5.3 `sample-diff.patch` passes `git apply --check` against `main`

#### Manual

- [x] 5.4 Findings name the actual planted defects with truthful justifications
- [x] 5.5 IDOR finding is absent from the `sample-diff-broken` run (deliberate break)
- [x] 5.6 `clean-diff.patch` produces no findings (false-positive control)
- [x] 5.7 Cost stays within `$0.50`; turn count has no unexpected `error_max_turns`

### Phase 6: Governance — Tooling-Zone Carve-Out and Package Conventions

#### Automated

- [ ] 6.1 `./gradlew build` still succeeds
- [ ] 6.2 `npm run verify` still passes

#### Manual

- [ ] 6.3 Amended hard rule unambiguously permits `packages/code-reviewer`
- [ ] 6.4 Agent run over this change's own diff produces no "no JS build step" violation
- [ ] 6.5 Root `AGENTS.md` still reads cleanly in one pass and has no Node section
