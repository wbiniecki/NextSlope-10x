<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: `test-verifies-behavior` Criterion, `applicable` Flag, and Severity Rubric

- **Plan**: `context/changes/test-verifies-behavior/plan.md`
- **Scope**: Full plan — Phases 1–6 (merge-base `4ce6bf0` → `c7fb94e`)
- **Date**: 2026-08-10
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 3 warnings, 7 observations

Supersedes nothing. Two earlier phase-scoped reviews (`impl-review-phases-2-4.md`,
`impl-review-phase-5.md`) were triaged and their accepted fixes landed in `031d093` and `3b90b2d`;
every one of those fixes was re-verified present in the current tree and is not re-reported here.

## Independent verification

Re-run during this review, not taken from the plan's checkboxes:

| Check | Result |
|---|---|
| `npm test` | 114/114 pass, 0 fail |
| `npm run typecheck` | clean |
| `git apply --check fixtures/assertion-free-tests.patch` | well-formed |
| `git apply --check --reverse` | fails — patch never applied to the tree |
| `rg -i 'five criteria\|these five ids'` | one match, inside `expectations.json` prose where "the other five" is correct |
| `git diff --stat 4ce6bf0..HEAD -- .github/ src/` | empty |
| Linear 10X-20 | **In Progress** — correct, PR not yet open |

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | WARNING |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | WARNING |

All 15 planned targets across the six phases match their contracts, with no MISSING item and no
guardrail violation. The two dimensions most likely to hide a real bug are clean on inspection: the
new `--artifacts-dir` flag has no reachable destructive delete (the single `rmSync` is gated on the
same expression that produces a `mkdtempSync` temp path, so a user-supplied path can never reach
it), and the one-to-one `expectedFindings` matcher genuinely consumes matches and genuinely gates
`passed`. The package's hard convention — the model reports facts, `src/verdict.ts` alone decides —
survives the new severity rubric, now guarded by `doesNotMatch` assertions in `test/prompt.test.ts`.

## Findings

### F1 — The harness under-reports what a run cost, on exactly the runs that cost the most

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: `packages/code-reviewer/scripts/verify.ts:387`
- **Detail**: `costUsd` is scraped with `Number(/total cost: \$([0-9.]+)/.exec(runLog)?.[1] ?? 0)`.
  `cli.ts` returns on its failure path before the verbose block that prints `total cost:`, so a
  session that started, spent money, and then failed is booked as `$0.0000`. This is not
  hypothetical — it happened in this change's own retained evidence.
  `verification/confirmation/verify.log` records `Hit the configured $0.50 budget before producing a
  result` for `sample-diff` and then reports `Total cost: $0.3285`, understating real spend by
  roughly 60%. Budget exhaustion is precisely the failure that costs the ceiling, so the accounting
  is wrong in the worst direction. `comparison.md:53-55` discloses the gap in prose, but the harness
  itself still prints a confidently wrong number to the next operator, in a package whose
  `AGENTS.md` opens the `npm run verify` entry with "costs money". Distinct from phase-5 F8, which
  hoisted `costUsd` out of the `try` — hoisting does not help when no number was ever emitted.
- **Fix A ⭐ Recommended**: Log `totalCostUsd` in `cli.ts`'s failure branch under `--verbose`, in the
  same `total cost: $N` format the success path uses.
  - Strength: `StructuredSessionFailure` already carries `totalCostUsd` (`agent.ts:87`), and the
    existing regex picks it up with no change to `verify.ts`. Fixes the number at the source, so
    `run.log` is also correct for anyone reading it directly.
  - Tradeoff: Touches `cli.ts`, which this change had otherwise left alone.
  - Confidence: HIGH — the field exists and the format is already established one branch away.
  - Blind spot: Have not checked whether the SDK populates `totalCostUsd` on every terminal reason
    or only on `budget_exhausted`.
- **Fix B**: Leave `cli.ts` alone; have `runFixture` mark cost unknown on a non-completed exit and
  print `Total cost: $X (excludes N failed run(s))`.
  - Strength: Confined to the harness; never claims a number it cannot substantiate.
  - Tradeoff: The total stays incomplete — honest, but still not the answer to "what did this cost".
  - Confidence: HIGH — purely local change.
  - Blind spot: `run.log` remains silent about the cost of a failed session.
- **Decision**: FIXED via Fix A — `cli.ts` now logs `turns: N, total cost: $N` on the failure path
  under `--verbose`, guarded on `totalCostUsd` being present. Covered by two new `cli.test.ts` tests
  that pin the exact regex `scripts/verify.ts` scrapes and the quiet-without-verbose behavior;
  verified failing with the fix disabled. 116/116 pass, typecheck clean.

### F2 — The plan-named canonical evidence directory holds a superseded run

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Success Criteria
- **Location**: `context/changes/test-verifies-behavior/verification/fixtures/`
- **Detail**: Phase 6 §1 names `verification/fixtures/` as the post-change evidence, and
  criterion 6.1 is written against it. But that directory holds Run 1, captured against the
  pre-tie-break prompt. The prompt this change actually ships is evidenced in `confirmation-2/`.
  The difference is material, not cosmetic: `verification/fixtures/sample-diff/review.json` shows
  the V6 finding at `high`, which is *not* the shipped behavior (`confirmation-2` has it at
  `medium`). `comparison.md:18-25` states the mapping in a table, so nothing is misrepresented — but
  a future reader following the plan to the named path, or a `/10x-archive` reader skimming
  directory names, reads the wrong run and draws the wrong conclusion about what shipped.
- **Fix A ⭐ Recommended**: Add a short `README.md` inside `verification/fixtures/` saying this is
  Run 1 against the pre-tie-break prompt and pointing at `confirmation-2/` as the shipped-prompt
  evidence.
  - Strength: Zero risk to the retained artifacts, which are the whole point of Phase 1's ordering
    discipline; fixes the navigation hazard where the reader actually lands.
  - Tradeoff: The plan's named path still is not the canonical run, so the plan and the folder
    disagree unless the plan is annotated too.
  - Confidence: HIGH — a pointer file cannot break evidence.
  - Blind spot: None significant.
- **Fix B**: Rename the directories so the shipped run occupies the canonical path (e.g.
  `fixtures/` → `run-1/`, `confirmation-2/fixtures` → `fixtures/`).
  - Strength: The plan's named path then genuinely holds the shipped evidence, needing no note.
  - Tradeoff: Rewrites paths that `comparison.md` cites by name throughout, so every cross-reference
    in a carefully written document must be updated in lockstep or the evidence trail breaks.
  - Confidence: MEDIUM — mechanical, but the citation count is high and a missed one is worse than
    the problem being solved.
  - Blind spot: Have not counted how many `comparison.md` references would need editing.
- **Decision**: FIXED via Fix A — added `verification/fixtures/README.md` naming this as Run 1
  against the pre-tie-break prompt, tabling the three runs, calling out the concrete V6
  `high`-vs-`medium` trap, and pointing at `confirmation-2/` as canonical. Confirmed not gitignored.

### F3 — Progress 6.2 is ticked, but one run did exceed the budget

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: `context/changes/test-verifies-behavior/plan.md:797`
- **Detail**: "6.2 No fixture run exceeds 3 turns or $0.50" is `- [x]`, yet Confirmation 1's
  `sample-diff` exhausted the $0.50 budget and produced no report at all
  (`verification/confirmation/verify.log:19`, `3/4 fixtures passed`). The shipped-prompt run is
  genuinely clean — 2 turns of 3 and ≤ $0.1716 per fixture — and `comparison.md` reports the
  exhaustion prominently and converts it into follow-up #1, so this is a checkbox that overstates a
  fully disclosed result rather than a hidden failure. Still, the Progress rows are what
  `/10x-archive` and `/10x-status` read, and a bare `[x]` here reads as "the budget question is
  settled" when the measured answer is "settled for the shipped prompt, with two exhaustions in
  roughly sixteen paid sessions".
- **Fix**: Annotate 6.2 in place — scope the tick to the shipped-prompt run and cross-reference the
  disclosed exhaustion, e.g. `— c7fb94e (shipped-prompt run; Confirmation 1's sample-diff exhausted
  the budget, see comparison.md → Budget and reliability)`.
- **Decision**: FIXED — 6.2 now scopes its tick to the shipped-prompt run and cross-references the
  disclosed Confirmation 1 exhaustion and follow-up #1.

### F4 — Two paid confirmation runs where the plan authorized one, plus a Phase 6 edit under `src/`

- **Severity**: 📋 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: `context/changes/test-verifies-behavior/verification/confirmation-2/`,
  `packages/code-reviewer/src/prompt.ts:96-112`
- **Detail**: Phase 6 §2's crossing branch authorizes "one paid confirmation" after a rubric
  revision. Two ran, because the first tie-break wording was falsified by its own confirmation run —
  the model attached "reachable" to the artifact rather than the failure and graded a defect it had
  itself called a "risk" as `high`. The second run is real spend (~$0.33 plus one exhausted session)
  beyond the plan. Separately, that revision edited `src/prompt.ts`, which criterion 6.5 ("no change
  under `.github/` or `src/`") literally forbids. Both are disclosed rather than hidden:
  `comparison.md:10-25` explains the third run and retains the falsified one as negative evidence,
  and `comparison.md:275-282` reasons explicitly that the conditional crossing branch is the more
  specific instruction and that 6.5's evident intent is the Java `src/` and the CI workflows, both
  of which are untouched. The reasoning is sound and the outcome is better than a plan followed
  literally would have produced. Recording it so the deviation is a decision on the record rather
  than something rediscovered at archive time.
- **Fix**: Accept as a documented deviation; no code change. Optionally reword 6.5 to name the Java
  `src/` explicitly so the next change does not inherit the same conflict.
- **Decision**: ACCEPTED + FIXED — deviation accepted on the record; criterion 6.5 reworded in both
  the Phase 6 Success Criteria block and its Progress row to name the Java application's `src/` and
  to state that `packages/code-reviewer/src/prompt.ts` is in scope whenever the crossing branch
  fires.

### F5 — The matcher's disjoint-range precondition is stated but not enforced

- **Severity**: 📋 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: `packages/code-reviewer/scripts/verify.ts:107` (schema),
  invariant stated at `:218-222`
- **Detail**: The matcher's docblock states its precondition outright — "The fixture keeps its ranges
  non-overlapping, which makes greedy consumption in declaration order exact rather than merely
  close" — and phase-5 F6 leaned on that same invariant when it declined to widen the ranges. But
  `fixtureExpectationSchema` validates only `start <= end` within a range and says nothing about
  ranges relative to each other, so the next person to add an `expectedFindings` entry can violate
  the precondition and still load cleanly. The blast radius is bounded: because `unconsumed.delete`
  makes consumption strictly one-to-one, overlapping ranges can produce a spurious FAIL but never a
  false PASS. So this is a paid-run robustness gap, not a gate hole.
- **Fix**: Add a `.refine` on `expectedFindings` asserting no two entries sharing a `criterionId` and
  `file` have overlapping `lineRange`s — turning the comment into a check, in a file that already
  validates the cheaper invariant.
- **Decision**: FIXED — `expectedFindingsSchema` now carries the overlap refinement, covered by a
  new `loadExpectations` test using two ranges that touch at line 36.

### F6 — `fixture.patch` escaped the path validation that `fixture.name` received

- **Severity**: 📋 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: `packages/code-reviewer/scripts/verify.ts:100`, consumed at `:362`
- **Detail**: Phase-5 F10 constrained `name` to `/^[a-z0-9-]+$/` with a comment explaining that it
  becomes a path segment and `../` would escape the artifacts directory. `patch` becomes a path
  segment too — `join(FIXTURES_DIR, fixture.patch)` — but it is only `z.string().min(1)`. There is
  no exploit: `expectations.json` is committed repo-controlled input, the operation is a read, and
  `cli.ts` caps it at `MAX_DIFF_BYTES`. It is the asymmetry that is worth closing, since the
  reasoning that justified the `name` regex applies verbatim and leaving one of two path segments
  unvalidated invites a reader to assume both are.
- **Fix**: `z.string().regex(/^[a-z0-9-]+\.patch$/)` on `patch`.
- **Decision**: FIXED — regex applied with a comment mirroring the `name` rationale; covered by a
  new test asserting `../../../etc/passwd` is rejected. The four shipped fixtures still load.

### F7 — `--artifacts-dir` pointing at a file surfaces a raw errno instead of a shaped message

- **Severity**: 📋 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: `packages/code-reviewer/scripts/verify.ts:202`
- **Detail**: `if (existsSync(dir) && readdirSync(dir).length > 0)` assumes the path, if it exists,
  is a directory. Point the flag at a regular file and `readdirSync` throws
  `ENOTDIR: not a directory, scandir '<path>'`, printed bare by the top-level handler. The sibling
  handles the exact analogue deliberately: `cli.ts:215` does `statSync` then `if (!stats.isFile())`
  and returns `--diff-file is not a regular file: ${path}`. The comment at `verify.ts:521-523`
  enumerates the operator errors this script means to report as one-liners, and this one is missing
  only because it was not anticipated. The freshness guard itself is correct and does run before any
  write.
- **Fix**: `statSync` the path and reject a non-directory with a house-style message before reaching
  `readdirSync`.
- **Decision**: FIXED — the guard moved into the new `prepareArtifactsDir` and now `statSync`s
  first, returning `--artifacts-dir <path> is not a directory.`; covered by a test that asserts the
  shaped message *and* the absence of `ENOTDIR`/`scandir` in it.

### F8 — `parseArtifactsDir` mutates the filesystem, unlike the parser it says it is modeled on

- **Severity**: 📋 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: `packages/code-reviewer/scripts/verify.ts:169-208` (side effects at `:202-206`)
- **Detail**: The comment at `:172` says the function is "Shaped like `src/cli.ts`'s parser", and the
  argv loop now is. The separation of concerns is not: `cli.ts`'s `parseArgs` is pure, returns a
  discriminated union, and leaves `mkdirSync` to `run()` (`cli.ts:316`), whereas `parseArtifactsDir`
  folds `existsSync`, `readdirSync`, and `mkdirSync` into the parse step and signals failure by
  throwing. Two visible consequences: the artifacts directory is created before `loadExpectations()`
  has validated the fixtures file, so an invalid expectations file leaves an empty directory behind;
  and the unit tests must create and `rmSync` real temp directories to exercise what is otherwise
  pure argument parsing (`test/verify.test.ts:334-357`). Relatedly, the script has no `USAGE` block —
  `cli.ts` defines one at `:76-87` and prints it after every parse failure, while `verify.ts` throws
  `Unknown argument "..."` and stops, so an operator who types `--artifact-dir` at the start of a
  four-fixture paid run learns only that their argument was wrong.
- **Fix**: Split it — return the resolved path from `parseArtifactsDir` and let `main()` do the
  freshness check and `mkdirSync`, matching the `parseArgs`/`run` division; add a two-line `USAGE`
  constant printed alongside the error.
- **Decision**: FIXED — `parseArtifactsDir` is now pure (parse and `resolve` only); the freshness
  check, the new non-directory guard, and `mkdirSync` live in an exported `prepareArtifactsDir`
  that `main()` calls *after* `loadExpectations()`, so an invalid expectations file no longer leaves
  an empty directory behind. Added a `UsageError` class and a `USAGE` block printed on argv
  mistakes only — verified by hand: `npm run verify -- --artifact-dir /tmp/x` now prints the error
  plus usage and spends nothing.

### F9 — Retained `run.log` files commit absolute developer paths

- **Severity**: 📋 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: `packages/code-reviewer/scripts/verify.ts:371`
- **Detail**: The log header is deliberately the real, pasteable invocation — the right call for
  baseline comparability, and the comment says so — but it writes `/Users/<username>/...` into files
  that the new `.gitignore` negations explicitly re-include and that are now committed. No
  credential is exposed; the full verbose surface is paths, model id, turn count, cost, and
  `modelUsage`. The Phase 1 baseline logs already have this property, so it is consistent rather than
  new. Worth noting only because these artifacts are headed for a GitHub repository.
- **Fix**: Accept, or write the header with `PACKAGE_ROOT`-relative paths and note the cwd once.
- **Decision**: ACCEPTED — a pasteable real invocation is worth more than hiding a home-directory
  path, and the Phase 1 baseline already has this property, so changing it now would break
  like-for-like comparison with the control.

### F10 — Phase 6 bookkeeping is uncommitted and `change.md` is unstamped

- **Severity**: 📋 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: `context/changes/test-verifies-behavior/plan.md` (working tree),
  `context/changes/test-verifies-behavior/change.md:4-6`
- **Detail**: `git status` shows `plan.md` modified with the ` — c7fb94e` SHA annotations for 6.1–6.9
  present but not committed, so the convention's traceability exists only in the working tree.
  `change.md` still reads `status: impl_reviewed` / `updated: 2026-08-09` from the previous
  phase-scoped review, and Phase 6 §4's contract is to stamp it on completion. Consistent with 6.10
  (Linear → In Review) still being open and the branch not yet pushed, so this is ordinary
  work-in-flight rather than drift. Linear 10X-20 is correctly **In Progress** and should stay there
  until the PR opens, per `.cursor/rules/linear-sync.mdc`.
- **Fix**: Commit the plan's Progress annotations; this review re-stamps `change.md` to
  `impl_reviewed` / `2026-08-10`. Move 10X-20 to In Review only when the PR to `main` is open.
- **Decision**: FIXED — the Progress annotations, the `change.md` stamp, this report, and the F1–F8
  fixes were committed together. 10X-20 deliberately left **In Progress**; it moves to In Review
  when the PR to `main` opens, per `.cursor/rules/linear-sync.mdc`.

## Triage outcome

| Finding | Decision |
|---|---|
| F1 cost under-reporting | FIXED via Fix A (+2 tests, verified with a deliberate break) |
| F2 superseded canonical evidence dir | FIXED via Fix A (`verification/fixtures/README.md`) |
| F3 Progress 6.2 overstated | FIXED (annotated in place) |
| F4 second confirmation run + `src/` edit | ACCEPTED + 6.5 reworded |
| F5 unenforced disjoint-range invariant | FIXED (+1 test) |
| F6 unvalidated `patch` path segment | FIXED (+1 test) |
| F7 raw `ENOTDIR` on a non-directory | FIXED (+1 test) |
| F8 impure parser, no usage text | FIXED (+2 tests, usage verified by hand) |
| F9 absolute paths in `run.log` | ACCEPTED |
| F10 uncommitted bookkeeping | FIXED |

Gate after triage: **121/121 tests pass** (114 before), typecheck clean, fixture patch still
well-formed and still unapplied, `.github/` and the Java `src/` still untouched. No paid run was
needed — every fix is in free, offline code, and none of them touches the prompt, so the Phase 6
evidence remains valid for the shipped prompt.

## Not re-reported

- **Every fix from the two prior reviews was verified present**: F2 (`.gitignore` negation, confirmed
  effective with `git check-ignore`), F3 (`z.strictObject` expectations schema), F4 (`evaluateReport`
  exported + `invokedDirectly` guard), F5 (`file` required and compared in both matchers), F7
  (`scoreCell` scores a criterion carrying a finding), F9, F10 (name regex + uniqueness refinement).
- **Gate reasoning in `criteria.md`'s rollout cap** — phases 2–4 F1, ACCEPTED under Fix B.
- **Unplanned changes, all judged justified**: the `.gitignore` negations (the recorded F2 fix,
  without which Phase 6's retention contract is unmeetable), `js-yaml` as a devDependency (backs the
  `tests.yaml` drift test from the F7 fix; verified as the genuine package, dev-only, ships its own
  types), and `test/verify.test.ts` (the explicit Fix A resolution of F4, and the only free proof
  that the gate Phase 6 spends money against actually enforces anything).
- **`expectedNotApplicable` is one-directional** — it asserts a declared N/A criterion came back N/A
  but never that an undeclared one stayed applicable, so two of the three `10 → N/A` corrections are
  read from reports rather than gated. Inherent to the plan's own contract, and already named and
  queued by the author as follow-up #3 in `comparison.md`.
