<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: `test-verifies-behavior` Criterion, `applicable` Flag, and Severity Rubric

- **Plan**: `context/changes/test-verifies-behavior/plan.md`
- **Scope**: Phase 5 of 6 (commit `4e0a417`)
- **Date**: 2026-08-09
- **Verdict**: NEEDS ATTENTION → all findings triaged
- **Findings**: 0 critical, 5 warnings, 5 observations
- **Triage**: 9 FIXED (F1–F5, F7–F10), 1 ACCEPTED (F6, recorded in `change.md` for Phase 6).
  Suite grew 88 → 113 tests; `npm run typecheck` and `npm ci` clean.

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

Plan Adherence: all four Phase 5 contract items verified MATCH, nothing MISSING. Both
easy-to-skip prose edits landed (`sample-diff`'s "all five criteria" in `expectations.json` and
`tests.yaml`, and the `$comment` documentation of the three new fields).

Scope Discipline: every "What We're NOT Doing" guardrail holds — `4e0a417` touches exactly six
files, nothing under `.github/`, `DEFAULT_MAX_TURNS`/`DEFAULT_MAX_BUDGET_USD` untouched at
`src/agent.ts:20-21`, no fixture patch applied, the existing five criteria's prose not trimmed. Two
EXTRAs, both benign: optional `note` strings on the structured finding entries, and the `dodged`
guard in `verify.ts`. The latter sits outside §3's literal wording but is exactly what the plan's
Critical Implementation Details calls "the guard", and Phase 6 gates on `verify.ts` rather than
promptfoo — so without it the named guard would have been absent from the actual gate.

Safety & Quality: no CRITICAL. Data safety is explicitly clean — `rmSync(..., { recursive: true,
force: true })` runs only in the non-retained branch, where `outDir` is unconditionally a
`mkdtempSync` result under `os.tmpdir()`; no argument, config value, or error path lets a
user-chosen directory reach it, and the retained branch never deletes anything. The one-to-one
finding match is deterministic (ECMAScript specifies `Set` insertion order; `unconsumed.delete(hit)`
runs on every match), the inclusive range check is correct, and no path was found where a fixture
reports PASS despite a genuine expectation violation *as the fixtures stand today*. The warnings
below are latent, silent-degradation, or evidence-loss risks.

Success Criteria: all five automated criteria re-verified independently (88/88 tests, clean
typecheck, `git apply --check` passes while `--reverse` fails, `tests.yaml` parses to 4 cases with
one object var each, all four ranges disjoint and each containing an added line). All three manual
items carry observable evidence.

## Findings

### F1 — `--artifacts-dir` accepts only one flag form and ignores unknown arguments

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: `packages/code-reviewer/scripts/verify.ts:118-136`, `:408-411`
- **Detail**: `parseArtifactsDir` finds the flag with `argv.indexOf("--artifacts-dir")` and ignores
  every other argument. So `npm run verify -- --artifacts-dir=/tmp/x` misses, returns `undefined`,
  runs all four fixtures, deletes all four temp directories, and retains nothing — after four paid
  API calls, with no warning. Any typo (`--artifact-dir`) behaves the same. This directly
  contradicts `src/cli.ts:139-140`, which supports both forms and says why in a comment: "`--flag
  value` and `--flag=value` are both common enough that supporting only one of them turns into a
  confusing failure at the worst moment." `cli.ts:198` also rejects unknown arguments. The comment
  at `verify.ts:408-410` compounds it by claiming "a mistyped `--artifacts-dir`" is reported as a
  one-line message — a mistyped flag is not reported at all; only a missing or `--`-prefixed *value*
  throws. In a package whose comment culture is about recording what breaks otherwise, a comment
  describing a guard that is not there is worse than no comment. Phase 6 is the next thing that runs
  and is the only caller that will ever pass this flag.
- **Fix**: Parse with the same `separator = argument.indexOf("=")` loop `cli.ts:133-147` uses,
  reject unknown arguments, and correct the `:408-410` comment so it describes real behavior.
- **Decision**: FIXED — `parseArtifactsDir` now loops over argv with `=` support and throws
  `Unknown argument "..."` on anything else; the closing comment was corrected to describe the
  guard that now exists. Verified both `--artifacts-dir fixtures` and `--artifacts-dir=fixtures`
  reach the freshness guard identically, and `--artifact-dir x` is rejected. `npm test` (88/88) and
  `npm run typecheck` pass.

### F2 — Phase 6's retained `review.json` and `review.md` are gitignored

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: `.gitignore:63-64`
- **Detail**: `review.json` and `review.md` are ignored unanchored, so they match at any depth.
  Confirmed with `git check-ignore -v` against the exact path Phase 6 prescribes
  (`plan.md:580-582`): both files under
  `context/changes/test-verifies-behavior/verification/fixtures/<name>/` are ignored;
  only `run.log` is not. Phase 6's contract is that each of the four fixture directories *retains*
  all three, and `comparison.md` is specified to read the retained `review.json`. Someone already
  hit this once — the Phase 1 baseline equivalents are tracked
  (`git ls-files .../baseline/fixtures/clean-diff/` returns all three), which can only have happened
  via `git add -f`. Left alone, Phase 6 pays for four sessions and then silently commits one third
  of its evidence.
- **Fix**: Add a `!context/changes/**/review.json` / `!context/changes/**/review.md` negation to
  `.gitignore`, so retained change evidence is tracked while ordinary run output stays ignored.
- **Decision**: FIXED — added `!context/changes/**/` and `!context/archive/**/` negations for both
  filenames, with a comment explaining that deliberately retained evidence is the exception.
  Verified `git check-ignore` no longer ignores the three Phase 6 artifact paths, while a stray
  `packages/code-reviewer/review.json` is still ignored.

### F3 — `expectations.json` is cast rather than validated, so a key typo silently disables an assertion

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: `packages/code-reviewer/scripts/verify.ts:100-110`, consequences at `:158`, `:178`, `:340-371`
- **Detail**: `loadExpectations` does `JSON.parse(...) as { fixtures?: FixtureExpectation[] }` and
  checks only that the array is non-empty. Every field this phase added is optional and read through
  `?? []`. Write `expectedFinding`, or nest it one level wrong, or `forbiddenFindingRange`, and the
  `??` swallows it: that entire assertion silently does not run and the fixture passes on criterion
  ids alone — the weaker gate this phase exists to replace. These keys are spelled once in JSON and
  re-spelled by hand in YAML, so the typo is plausible rather than theoretical. What makes it worse
  is that `report()` prints nothing about finding matching on a *pass*: a run where three range
  assertions matched and a run where zero were declared produce byte-identical output, and Phase 6
  treats the `4/4` line as proof. (A typo *inside* an entry, e.g. `lineRanges`, is safe — the
  destructure throws and the outer catch fails the fixture. Only top-level keys fail open.)
- **Fix**: Validate `expectations.json` with a zod schema in the same file — `reviewReportSchema` is
  already imported, so the dependency and the idiom are both present — with `.strict()` so an
  unknown key errors instead of being ignored. Complement it by having `report()` print what it
  asserted, e.g. `findings   3/3 matched, 0 forbidden`, so a pass is self-describing.
  - Strength: Turns the whole class of silent-skip into a load-time error, and makes the `4/4` line
    Phase 6 relies on actually mean something. Matches how the package treats every other contract —
    model output is `safeParse`d at the `cli.ts` boundary rather than trusted.
  - Tradeoff: ~30 lines in a script with no test, and it re-derives a shape that already exists as a
    TypeScript type.
  - Confidence: HIGH — zod is a direct dependency, and the same pattern is already used one function
    away on `review.json`.
  - Blind spot: `.strict()` would reject the `note` fields unless they are declared; easy to miss on
    first pass.
- **Decision**: FIXED — `expectations.json` is now parsed through `expectationsFileSchema`, built
  from `z.strictObject` throughout, with `lineRange` a `[start, end]` tuple refined so `start <= end`.
  `loadExpectations` takes an optional path and reports zod issues the same way `cli.ts` does.
  `report()` now prints a `findings   N/N matched, M hit in K forbidden range(s)` line on passes as
  well as failures. Covered by `test/verify.test.ts`: a mistyped `expectedFinding` key and a
  backwards range both throw.

### F4 — The harness gate has no unit test, and the module is structured so it cannot easily get one

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Pattern Consistency
- **Location**: `packages/code-reviewer/scripts/verify.ts:377-414`
- **Detail**: `npm test` runs only `test/*.test.ts`, and there is no test for `verify.ts`.
  `promptfoo/assertions.js` is checked by nothing at all — it is a `.js` file outside
  `tsconfig.json`'s `include`, so not even `tsc` sees it. That leaves roughly 200 lines of decision
  logic added by this phase validated only by paying for a run. This is out of line with the
  package's own stated convention: `src/cli.ts:5-8` says the exit-code mapping lives "in an exported
  pure function with tests rather than in scattered `process.exit` calls" because it is a contract,
  and `verdict.ts` — the analogous gate — has 12 dedicated tests. `verify.ts` now holds four pure,
  trivially testable pieces: `inRange`, `matchFindings`, `parseArtifactsDir`, and the six-way
  `passed` conjunction at `:310-316`. The structural blocker is that the module exports nothing and
  runs `await main()` at top level, so importing it in a test would trigger four paid calls.
  There is a sharp irony here: this phase's own criterion is `test-verifies-behavior`.
- **Fix A** ⭐ Recommended: Export `inRange`, `matchFindings`, `parseArtifactsDir`, and an
  `evaluateReport(fixture, report)`; guard the top-level run with the same `invokedDirectly` check
  `cli.ts:359-370` already uses; add `test/verify.test.ts`.
  - Strength: Closes F3's and F5's blast radius too, since a test would pin the shapes. Roughly 60
    lines for four cases — one finding cannot satisfy two expectations, a line one outside the range
    does not match, a non-empty artifacts dir is refused, any single mismatch clears `passed` —
    and it runs free in the existing `npm test`.
  - Tradeoff: Restructures a script mid-change, and `smoke.ts`/`emit-schema.ts` keep the old shape,
    so the scripts directory becomes internally inconsistent.
  - Confidence: HIGH — the `invokedDirectly` pattern already exists in this package, and the logic
    is pure.
  - Blind spot: Does nothing for `assertions.js`, which stays untested unless it is also brought
    under `tsc` or given its own test.
- **Fix B**: Defer to a follow-up after Phase 6, recorded in `follow-ups/`.
  - Strength: Keeps Phase 5 closed and gets to the paid measurement sooner, which is the thing the
    whole plan is sequenced around.
  - Tradeoff: Phase 6 spends real money gated by untested logic; if the gate is wrong, the money is
    spent before anyone finds out.
  - Confidence: MEDIUM — the logic was reviewed by hand twice and no defect was found in it, but
    "reviewed" is not "tested", which is this criterion's entire thesis.
  - Blind spot: Follow-ups queued against a change that is about to be archived have a poor track
    record of being picked up.
- **Decision**: FIXED via Fix A — extracted `evaluateReport(fixture, report)` as the whole pure
  comparison and exported it alongside `inRange`, `matchFindings`, `parseArtifactsDir`, and
  `loadExpectations`; the top-level run is now behind the `invokedDirectly` check `cli.ts:359-370`
  uses, so importing the module spawns nothing. Added `test/verify.test.ts` — 22 tests, suite now
  110/110. Both central guards verified by deliberate break: removing `unconsumed.delete(hit)` fails
  "does not let one reported finding satisfy two expectations", and dropping `dodged` from the
  `passed` conjunction fails the escape-hatch test. Both restored and re-verified green.
  `assertions.js` remains untested — see F7.

### F5 — `expectedFindings` matches a line number without a file

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: `packages/code-reviewer/scripts/verify.ts:54-59`, `:151-192`; mirrored in `promptfoo/assertions.js:110-133`
- **Detail**: `ExpectedFinding` carries `criterionId`, `severity`, and `lineRange` but no `file`,
  while `findingSchema` (`src/schema.ts:74`) requires one and `expectations.json`'s own `$comment`
  describes the range as "post-change line numbers" — meaningful only relative to a file. Harmless
  today because `assertion-free-tests.patch` touches exactly one file, so any line 29–36 finding is
  in the right file by construction. It bites the moment a multi-file patch gets `expectedFindings`,
  and `sample-diff.patch` (six files) is the obvious next candidate: a finding at line 35 of
  `application.properties` would satisfy an expectation written about line 35 of a Java test. The
  forbidden ranges have the mirror problem, firing on an unrelated file's same-numbered line.
- **Fix**: Add `file` to `ExpectedFinding` and `ForbiddenFindingRange`, compare it, and populate it
  in `expectations.json` and `tests.yaml`.
- **Decision**: FIXED — `file` is now a **required** field on both schemas (optional would have left
  the hole open by default), compared in `matchFindings` and in `assertions.js`, and populated in
  `expectations.json`, `tests.yaml`, and the `$comment` docs. `describeRange` now renders
  `criterion @ file:start-end`. Covered by a new test: a finding at the right line in
  `application.properties` does not satisfy an expectation about the Java test.

### F6 — The expected ranges are tight enough that a defensible anchor choice fails a paid run

- **Severity**: 📋 OBSERVATION
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Success Criteria
- **Location**: `packages/code-reviewer/fixtures/expectations.json:74-96`
- **Detail**: The ranges are arithmetically correct — verified independently twice against the hunk
  headers. The risk is not correctness but anchor choice. `[37, 41]` covers the `@Disabled` line
  (38), `@Test` (39), and the signature (40), but not the disabled test's body (42–47); a reviewer
  anchoring that finding to the now-dead assertion, or to the added `import ...Disabled` on line 9,
  is not wrong and produces a FAIL. The forbidden decoy range starts at 91, the blank line
  immediately after the assertion-free test's closing brace on 90, so a one-line slip on that
  finding fails twice — unmatched expectation *and* false-positive hit. This is the most likely
  cause of a Phase 6 failure that is not a review-quality failure.
- **Fix A** ⭐ Recommended: Keep the ranges tight and name this failure mode in Phase 6's
  `comparison.md` before running, so a range miss is diagnosed rather than mistaken for a criterion
  defect.
  - Strength: Costs nothing and preserves the precision that is the point of structured matching.
    Phase 6 already writes `comparison.md`, so it is one paragraph in a file that has to exist.
  - Tradeoff: A miss still costs a re-run to confirm, and re-runs cost money.
  - Confidence: MEDIUM — no model has been run against this fixture, so the anchoring behavior is
    genuinely unknown.
  - Blind spot: If the model anchors inconsistently across runs, no fixed range works and the whole
    range-matching approach needs rethinking.
- **Fix B**: Widen each range to cover its whole method body before spending.
  - Strength: Removes the most likely spurious failure without a paid run to discover it.
  - Tradeoff: Widening `[37, 41]` to the method body pushes it toward the assertion-free test's
    range, and the ranges must stay disjoint for the greedy one-to-one match to be exact — so this
    trades one failure mode for a weaker guarantee.
  - Confidence: MEDIUM — cheap to do, but tuning a gate against a model that has never run it is
    guessing in the other direction.
  - Blind spot: A wider range also accepts a finding that anchored to the wrong construct, which is
    precisely what the tight range was for.
- **Decision**: ACCEPTED (Fix A) — ranges stay tight. The failure mode is recorded in `change.md`'s
  Notes, beside the rollout-intent note, so Phase 6 reads it before spending: if the run fails only
  on ranges, widen them and say so in `comparison.md` rather than reading it as the criterion
  misfiring.

### F7 — `expectations.json` and `tests.yaml` duplicate three nested structures with no drift check

- **Severity**: 📋 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: `packages/code-reviewer/promptfoo/tests.yaml:1-11`
- **Detail**: The duplication is deliberate and documented, and the reason given is sound. But
  before this phase it was two flat string arrays; it is now two arrays plus an N/A list plus three
  `{criterionId, severity, lineRange}` objects plus a forbidden range, restated in another syntax.
  They agree today — verified field-for-field. Nothing keeps them agreeing, and a drift would show
  up as a promptfoo failure with no obvious cause.
- **Fix**: Add a free unit test that loads both files and asserts each `fixture` var equals its
  `expectations.json` entry; it needs no network and would fold naturally into F4's `verify.test.ts`.
- **Decision**: FIXED — added a `tests.yaml mirrors expectations.json` suite covering the case count,
  the single-object-var shape (the expansion trap), and every list field per fixture. Needed a YAML
  parser: `js-yaml` was only present transitively via promptfoo, so it is now an explicit
  devDependency (`js-yaml@^5.2.3`, which is the registry's `latest` — 4.x is tagged `v4-legacy` —
  and ships its own types, so no `@types` stub). `npm ci` re-verified clean, since the lockfile is a
  CI contract; local npm 11 matches the Node 24 pin the action documents. Verified by deliberate
  break: changing one `lineRange` in `tests.yaml` fails with
  `assertion-free-tests.expectedFindings drifted`.

### F8 — Run-failure diagnostics are lossy

- **Severity**: 📋 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: `packages/code-reviewer/scripts/verify.ts:239-253`, `:326-332`, `:238`
- **Detail**: Three small losses on the paths that matter most when something goes wrong. On a spawn
  failure (`ENOENT`, `EMFILE`) `result.status` is null so the fixture correctly fails, but
  `result.error.message` is discarded and the operator sees `exited -1` over a `run.log` containing
  only the synthetic header. The post-parse throw path calls `failedOutcome(fixture, -1, 0, ...)`,
  so a run that completed and cost money is booked as exit `-1` and `$0.0000` and drops out of the
  total. And the header written at `:238` (`$ code-reviewer --diff-file ... --verbose`) omits
  `--out` and names a binary that does not exist, in an artifact whose whole purpose is being
  compared against a baseline whose logs open with the real invocation.
- **Fix**: Include `result.error?.message` in the failure text, hoist `exitCode`/`costUsd` out of the
  `try` so the accounting survives, and write the real `process.execPath` + argv as the header.
- **Decision**: FIXED — all three. `spawnError` is captured and prefixed onto the failure message,
  `exitCode`/`costUsd` are hoisted so a post-run throw still books what was spent, and the `run.log`
  header is now the actual `process.execPath` + argv rather than a stand-in naming a binary that
  does not exist.

### F9 — `assertions.js` thresholds pass vacuously on a missing or non-numeric score

- **Severity**: 📋 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: `packages/code-reviewer/promptfoo/assertions.js:82`, `:91`
- **Detail**: `undefined > 5` and `undefined < 6` are both `false`, so a criterion entry with no
  `score` clears both thresholds. Unlike `verify.ts`, this file reads raw model output rather than
  zod-validated output, so the case is reachable. The sibling `is-json` assertion in
  `promptfooconfig.yaml:33-34` fails the row anyway, so the aggregate verdict stays right — but this
  assertion's reason string stays misleadingly silent about the real problem.
- **Fix**: Report `typeof entry.score !== "number"` as an explicit problem.
- **Decision**: FIXED — both loops now report `<id> carries no numeric score` explicitly instead of
  falling through the comparison. The forbidden loop was restructured to an early `continue` so the
  not-applicable skip and the score check read as separate decisions.

### F10 — `fixture.name` is interpolated into artifact paths without validation

- **Severity**: 📋 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: `packages/code-reviewer/scripts/verify.ts:220-226`
- **Detail**: Two duplicate `name` values in `expectations.json` make the second run overwrite the
  first's artifacts inside `--artifacts-dir`, quietly defeating the freshness guarantee `:112-117`
  exists to provide — and it would go unnoticed, because the run itself still passes. A name
  containing `../` escapes both the artifacts dir and `tmpdir()`; not a data-destruction vector,
  since `mkdtempSync` creates what `rmSync` removes and `expectations.json` is committed
  repo-controlled input, but it is free to close.
- **Fix**: Assert unique names and `/^[a-z0-9-]+$/` at load time.
- **Decision**: FIXED — `name` now carries a `/^[a-z0-9-]+$/` regex in the fixture schema, and the
  fixtures array carries a refinement asserting names are unique, both enforced at load time by the
  F3 zod schema.
