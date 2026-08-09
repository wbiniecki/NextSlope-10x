# `test-verifies-behavior` Criterion, `applicable` Flag, and Severity Rubric — Implementation Plan

## Overview

`packages/code-reviewer` gains a sixth **gating** criterion, `test-verifies-behavior`, which catches
tests that cannot fail — no assertion, no mock verification, no expected exception. Every criterion
score gains an explicit `applicable: boolean`, so "not applicable" stops rendering as 10/10. And
severity — the enum that actually decides pass/fail — gets a written rubric for the first time.

The three ship together because all three touch `criterionScoreSchema` and the shape of `review.json`.
A gating criterion produces findings, and `DEFAULT_FAIL_ON = "high"` gates on severity, so the rubric
stops being optional the moment the criterion lands.

Authoritative scope: Linear
[10X-20](https://linear.app/10xnextslope/issue/10X-20/code-reviewer-test-verifies-behavior-criterion-applicable-flag).

## Current State Analysis

The package is a Node/TypeScript CLI that reviews a unified diff against five criteria and emits
`review.json` plus `review.md`. The model reports facts; `src/verdict.ts` decides pass/fail. That
split is a hard convention (`packages/code-reviewer/AGENTS.md` → Conventions) and this change does not
touch it.

What the current state means for this work:

- **The criteria count is already derived in code, but hardcoded in prose and one test.**
  `verdictSchema` uses `.length(CRITERION_IDS.length)` (`src/schema.ts:72`), not a literal `.length(5)`,
  and `buildReviewPrompt` derives its count from `parseCriterionIds` (`src/prompt.ts:60`). So adding an
  id propagates automatically through the schema and the prompt. What does not propagate:
  `test/schema.test.ts:136-139` asserts `CRITERION_IDS.length === 5`, and five prose locations say
  "five".
- **`applicable` breaks four test builders at once.** `test/schema.test.ts:16`,
  `test/render.test.ts:10`, `test/cli.test.ts:48`, and `test/verdict.test.ts:21` each build criteria
  arrays with `CRITERION_IDS.map(...)` and no `applicable` field. A required boolean invalidates
  every one.
- **`promptfoo/assertions.js` will judge a meaningless number.** It checks every `forbiddenCriteria`
  entry against `UNVIOLATED_CRITERION_MIN_SCORE = 6` (`assertions.js:58-63`). On `clean-diff.patch` the
  new criterion is not applicable; if the model pairs that with a low score, the suite fails for a
  reason that is not a review defect — after a paid run.
- **Severity is entirely unspecified today.** Neither `src/prompt.ts` nor `prompts/criteria.md` says
  how to pick `low`/`medium`/`high`/`critical`. The prompt specifies the 1–10 score in detail and
  states outright that scores do not decide acceptance (`src/prompt.ts:69-72`) — while the enum that
  does decide gets no guidance at all.
- **The existing fixtures already contain a strong false-positive control.**
  `fixtures/sample-diff.patch` modifies `HtmxSmokeE2eTests` by inserting `page.waitForTimeout(500)`
  between `toggle.click()` and two intact `assertThat(toggle)` calls. That is a modified test that
  still verifies: `e2e-conventions` must fire on the sleep while `test-verifies-behavior` stays silent.
  Sharper than anything hand-built, and it costs nothing to reuse.
- **The repo's verification vocabulary is measured, not guessed.** In `src/test/` and `src/e2eTest/`:
  AssertJ `assertThat` 431, MockMvc `.andExpect` 178, Mockito `verify(` 32, JUnit
  `assertTrue`/`assertEquals` 13, `PlaywrightAssertions` 3. `assertThrows`, `assertNotNull`,
  `verifyNoInteractions`, `assertAll`, and `@Disabled`/`@Ignore` appear **nowhere** today — so the
  criterion must recognize them from its own prose, with no in-repo example to generalize from.
- **Two "not a violation" rules fire on the repo as it stands.**
  `NextslopeApplicationTests.contextLoads()` has a literally empty body, and `AGENTS.md` names it the
  dual-engine verification standard. `support/AccessControlAssertions` wraps `.andExpect(...)` behind
  named helpers like `assertRedirectedToLogin(actions)`, so the criterion must recognize that fixed
  project vocabulary rather than require an assertion in the test method body.
- **No cost or turn evidence exists.** Nothing in `context/archive/2026-08-08-ci-cd-code-review/`
  records turns or per-run cost. The baseline genuinely cannot be reconstructed after the fact.
- **The blast radius is contained.** `packages/code-reviewer/AGENTS.md` calls `review.json` a
  cross-change contract, but `.github/workflows/review.yml` never parses it — the composite action
  maps the exit code to a verdict label and posts `review.md` verbatim. Adding an optional-to-consumers
  field and a criterion changes no workflow.

### Key Discoveries

- `verdictJsonSchema.required` is asserted only at the root (`test/schema.test.ts:43`), so adding a
  property to `criterionScoreSchema` does not break that assertion.
- `promptfoo/promptfooconfig.compare.yaml` shares `tests.yaml`, `assertions.js`, and
  `generated/verdict.schema.json` with the default suite — so both suites move together and the
  compare file itself needs no edit.
- `promptfoo/generated/verdict.schema.json` is gitignored (`.gitignore:58`) and regenerated by
  `scripts/emit-schema.ts` on every promptfoo run, so the schema change needs no committed update.
- `src/test/java/com/nextslope/recommendation/WeightedDistanceScorerTests.java` is an ideal fixture
  target: repeated `assertThat(breakdown.alignDiff()).isEqualTo(1.0, within(EPS))` lines that can be
  weakened in place for the protection-removal case.
- Prose saying "five" lives in exactly five places: `prompts/criteria.md:3` and `:11`, `README.md:4`,
  `fixtures/expectations.json:12`, `promptfoo/tests.yaml:12`.

## Desired End State

`npm run review` scores six criteria, each carrying `applicable`. A diff that touches no test file
reports `test-verifies-behavior` as not applicable, rendering `—` rather than `10/10`, and a run where
*every* criterion is not applicable no longer headlines "no findings". A diff that adds an
assertion-free test, weakens an existing assertion, adds `@Disabled` with no replacement, or swallows
an exception produces a `medium` finding during the initial rollout. The criterion reports on every
PR without blocking under the default `--fail-on high`; promoting protection-removal cases to `high`
is a separate decision made only after measuring false positives. Every severity is chosen against a
written rubric plus this explicit rollout override rather than improvised.

Verified by: four fixtures passing `npm run verify` on criterion ids, not-applicable state, and the
new fixture's severity/location expectations, with the post-change run diffed against a baseline
captured before any edit.

## What We're NOT Doing

- **No advisory criterion tier.** Dropped in 10X-20 — zero members once this criterion gates.
- **No `internal-consistency` / plan-conformance criterion.** Needs `--plan-file` plumbing and overlaps
  `/10x-impl-review`.
- **No enum split, no narrowed `findingSchema.criterionId`, no two-section render.** All of that
  belonged to the dropped advisory tier.
- **No nullable `score`.** Rejected in 10X-20 to avoid risk in the emitted draft-07 schema.
- **No full per-criterion severity anchors for the existing five.** They get the global rubric only;
  writing prescriptive anchors for all six in the same change that adds a sixth would make any verdict
  shift unattributable.
- **No `test-verifies-behavior` finding at `high` in this change.** Every case ships `medium` so the
  criterion reports on every PR without blocking under `--fail-on high`. Promoting the
  protection-removal cases waits for a measured false-positive rate and a separate change.
- **No change to `DEFAULT_MAX_TURNS` or `DEFAULT_MAX_BUDGET_USD`.** The turn budget is measured, not
  preemptively raised.
- **No change to `.github/workflows/`, `ci.yml`, or the composite action.** Wiring the package's free
  tests into CI is a separate queued follow-up
  (`context/archive/2026-08-08-ci-cd-code-review/follow-ups/review-fixes.md` → F4).
- **No trimming of the existing five criteria's prose.** It would confound the baseline.
- **Fixture patches are never applied to the working tree.** They are review input and describe real
  defects.

## Implementation Approach

Sequence the work so the only irreversible thing happens first and everything expensive is bunched at
the ends. Phase 1 captures the paid baseline before a single byte changes, because it is the sole
control for two questions — did a sixth criterion degrade the other five, and did writing severity
anchors shift verdicts. Each baseline fixture runs directly through the CLI so its complete report and
verbose run metadata survive; the current aggregate harness deletes those artifacts. Phases 2 through
5 are entirely free and offline, verified by `npm test` and `npm run typecheck`. Phase 6 spends money
once more and compares retained reports against the baseline; only a fail-on-boundary crossing
authorizes the explicit confirmation run described there.

Within the free phases, order by dependency: `applicable` is independent of the criterion, so it lands
first and absorbs the four-test-file churn on its own. The global severity rubric lands next, so it is
reviewable as a behavior change to the existing five in isolation. Only then does the sixth criterion
arrive, which must land together with its `criteria.md` section — the id-lockstep test in
`test/prompt.test.ts` fails if the enum and the document disagree even for one commit.

## Critical Implementation Details

**Ordering requirement.** The sixth `CRITERION_IDS` entry and the `## \`test-verifies-behavior\``
heading in `prompts/criteria.md` must land in the same commit. `parseCriterionIds` and the schema enum
are asserted equal in both directions (`test/prompt.test.ts:36-40`), so splitting them across commits
leaves the suite red in between.

**`applicable` is not an escape hatch.** The prompt must state that a criterion governing anything the
diff touches is scored, not declared inapplicable — otherwise the field becomes the cheapest way for
the model to avoid a hard judgement, and every fixture's `expectedCriteria` silently weakens. The
Phase 5 assertion that expected criteria are never marked N/A is the guard.

**promptfoo var expansion.** `promptfoo/tests.yaml` carries exactly one var per test, an object. A
top-level array var makes promptfoo expand one test into one case per element, so `expectedCriteria`
would arrive as a bare string and any assertion iterating it walks it character by character. The new
`expectedNotApplicable` list must be nested inside the existing `fixture` object for the same reason.
After editing, four fixtures must report as four test cases.

## Phase 1: Baseline Capture

### Overview

Record the current five-criterion reviewer's behavior — free checks and both paid suites — into the
change folder, before any source edit. This is the control for the whole change and cannot be
reconstructed later.

### Changes Required:

#### 1. Baseline artifacts

**File**: `context/changes/test-verifies-behavior/baseline/` (new directory)

**Intent**: Capture the pre-change behavior of the reviewer as committed evidence. Keep `npm test`,
`npm run typecheck`, and `npm run promptfoo` logs, plus one complete CLI artifact directory per
fixture and a short `notes.md` recording the date, resolved model, and per-fixture turns and cost.

**Contract**: `test.log`, `typecheck.log`, and `promptfoo.log` contain interleaved stdout and stderr.
For each of `sample-diff`, `sample-diff-broken`, and `clean-diff`, run `npm run review` directly once
from `packages/code-reviewer` with `--verbose` and a distinct
`--out ../../context/changes/test-verifies-behavior/baseline/fixtures/<name>/`; retain `run.log`,
`review.json`, and `review.md` in that directory. This is still three fixture API sessions — it
replaces the baseline `npm run verify` rather than supplementing it. `notes.md` indexes the three runs
and records their resolved model, turns, cost, and outcome. Record what actually happened: a failing
baseline run is evidence worth keeping, not a reason to re-run until it passes.

#### 2. Linear status

**Intent**: Move 10X-20 to **In Progress** as work genuinely begins, per `.cursor/rules/linear-sync.mdc`.

**Contract**: Issue 10X-20 in project NextSlope MVP, team 10xNextSlope, transitions Backlog → In Progress.

### Success Criteria:

#### Automated Verification:

- `npm test` and `npm run typecheck` pass on the unmodified package, and both logs are captured
- Each of the three `baseline/fixtures/<name>/` directories contains a non-empty `run.log`,
  schema-valid `review.json`, and rendered `review.md`
- `baseline/promptfoo.log` exists, is non-empty, and records exactly three test cases (not fourteen —
  the var-expansion trap)
- `git status` shows no modification to any file under `packages/code-reviewer/src/`,
  `prompts/`, or `fixtures/`

#### Manual Verification:

- `baseline/notes.md` records the resolved model, per-fixture turn count, and per-fixture cost
- The spend is acknowledged as expected (six paid sessions across the two suites)
- Linear 10X-20 shows **In Progress**

**Implementation Note**: After completing this phase and all automated verification passes, pause for
manual confirmation before proceeding. This is the one phase whose ordering cannot be recovered.

---

## Phase 2: `applicable` End-to-End

### Overview

Add the not-applicable state through the whole path — schema, prompt instruction, rendering, tests —
with no criterion change. Self-contained and free.

### Changes Required:

#### 1. The schema field

**File**: `packages/code-reviewer/src/schema.ts`

**Intent**: Add `applicable` to `criterionScoreSchema` so a criterion that governs nothing in the diff
can say so, instead of being forced into a misleading 10/10.

**Contract**: A required `z.boolean()` on `criterionScoreSchema`, placed after `id` and before `score`,
carrying a `.describe()` that states the criterion does not apply when the diff contains nothing it
governs. `score` keeps its existing `1..10` integer constraint and is declared meaningless when
`applicable` is false — no refinement couples the two fields, because refinements do not survive into
`verdictJsonSchema` and would surface only as a failed review at the `cli.ts` parse boundary.

#### 2. The prompt instruction

**File**: `packages/code-reviewer/src/prompt.ts`

**Intent**: Tell the model when to set `applicable` false, and close off its use as an escape hatch.

**Contract**: A short paragraph in the existing `## How to review` block, beside the score guidance.
It must say three things: set it false only when the diff contains nothing the criterion governs; a
criterion that governs anything the diff touches is scored normally even when unviolated; and `score`
is ignored when `applicable` is false, so there is no need to pick a flattering number.

#### 3. Rendering

**File**: `packages/code-reviewer/src/render.ts`

**Intent**: Make not-applicable visually distinct from full compliance, and stop an all-not-applicable
run reading as a clean review.

**Contract**: In `criterionScoresSection`, the Score cell renders an em dash instead of `N/10` when
`applicable` is false; the justification cell is unchanged. In `summaryLine`, when the report has no
findings *and* every criterion is not applicable, the trailing clause becomes a statement that no
criterion applied to this diff. The bold verdict word stays `Passed` — it is tied to the exit code that
drives the PR label, and a third word would make the comment contradict the label.

#### 4. Test fixtures, raw-model instruction, and documentation

**File**: `packages/code-reviewer/test/schema.test.ts`, `test/render.test.ts`, `test/cli.test.ts`,
`test/verdict.test.ts`, `promptfoo/prompt.js`, `README.md`

**Intent**: Repair all four criteria-array builders that a required field invalidates, cover the new
behavior, and keep both documented output shapes in lockstep with the schema.

**Contract**: `wellFormedVerdict()`, `reportWith()`, and both `verdictWith()` helpers each set
`applicable: true`. New assertions: the schema rejects a criterion score missing `applicable`; a
not-applicable criterion renders an em dash in its Score cell while keeping its justification; an
all-not-applicable clean report does not claim "no findings" and still renders as passed.
`rawModelPrompt()` adds the required boolean to the exact criterion-entry shape it asks the raw model
to return, so `promptfoo:compare` remains compatible with the generated schema. The README's
`review.json` example adds `applicable` and explains that a false entry's integer score is retained
only for schema compatibility and carries no meaning.

### Success Criteria:

#### Automated Verification:

- `npm test` passes in `packages/code-reviewer`
- `npm run typecheck` passes
- A test asserts the schema rejects a criterion score with no `applicable` field
- A test asserts an em dash renders in place of a score for a not-applicable criterion
- A test asserts the all-not-applicable summary line no longer says "no findings" and still reads as
  passed

#### Manual Verification:

- The rendered table remains valid GitHub-flavored markdown with the em dash in place
- The prompt's `applicable` paragraph reads as a rule, not a suggestion, and explicitly blocks the
  escape-hatch reading
- The raw-model output instruction and README example both include `applicable`, with the README
  documenting the not-applicable score semantics

---

## Phase 3: Global Severity Rubric

### Overview

Give the model the first written guidance on the enum that actually gates merges. Applies to all six
criteria; the new criterion's temporary all-medium rollout override arrives in Phase 4.

### Changes Required:

#### 1. The rubric

**File**: `packages/code-reviewer/src/prompt.ts`

**Intent**: Define `low`, `medium`, `high`, and `critical` so severity stops being improvised. It sits
in `prompt.ts` rather than `criteria.md` for symmetry with the 1–10 score guidance that already lives
there and is already unit-tested.

**Contract**: A block in `## How to review`, after the score guidance, stating that severity — unlike
score — is what decides whether the change is blocked, and anchoring the four levels on a single axis:
whether the diff *adds something weakly* or *removes an existing protection*. `low` is a cosmetic or
stylistic deviation with no functional risk; `medium` is a real convention violation that regresses
nothing; `high` removes or defeats a protection that existed, or opens a correctness or security hole;
`critical` is irreversible or breaks production — the anchor cases being an edit to an already-applied
migration and an owned route reachable without ownership resolution. The block must not restate the
gate threshold as a number: `--fail-on` is configurable and `verdict.ts` owns it.

#### 2. Rubric coverage

**File**: `packages/code-reviewer/test/prompt.test.ts`

**Intent**: Guard the rubric the way the score guidance is guarded, so an edit cannot silently drop it.

**Contract**: Assertions that the prompt names all four severity levels and states that severity
decides blocking while score does not. The existing "does not ask the model for an overall pass or
fail decision" assertion must still hold — the rubric explains how to label a finding, never how to
decide the verdict.

### Success Criteria:

#### Automated Verification:

- `npm test` passes
- `npm run typecheck` passes
- A test asserts all four severity names appear in the prompt
- A test asserts the prompt distinguishes severity (decides blocking) from score (diagnostic)
- The existing assertion that the prompt does not request an overall pass/fail still passes

#### Manual Verification:

- The rubric's four levels read as mutually exclusive, with the add-weakly vs remove-protection axis
  stated plainly enough to apply to a criterion it was not written for

---

## Phase 4: The Sixth Criterion

### Overview

Add `test-verifies-behavior` to the criteria document and the schema enum, in one commit, with its
all-medium rollout override and explicit non-violations.

### Changes Required:

#### 1. The criterion

**File**: `packages/code-reviewer/prompts/criteria.md`

**Intent**: Encode "a test that cannot fail is not a test" as a criterion decidable from the diff
alone, with false-positive carve-outs precise enough not to block correct code.

**Contract**: A new `## \`test-verifies-behavior\`` section following the established shape of the
other five — what the rule is, **A violation looks like**, **Not a violation**, and a `Source:` line
naming `AGENTS.md` → Testing and `context/foundation/test-plan.md`. It must carry:

- *Scope*: test sources under `src/test/java/` and `src/e2eTest/java/` only. `packages/` is explicitly
  out of scope, so the reviewer's own Node tests are not judged by this criterion.
- *Verification vocabulary that counts*: AssertJ `assertThat`, MockMvc `.andExpect`, Mockito `verify` /
  `verifyNoInteractions`, JUnit `assertTrue` / `assertEquals` / `assertThrows` / `assertNotNull`,
  `PlaywrightAssertions`, and the project's own `support/AccessControlAssertions`. State that
  `assertThrows`, `assertNotNull`, and `verifyNoInteractions` appear nowhere in the repo today but
  count fully — the model has no in-repo example to generalize from.
- *Violations*: no verification at all; tautological assertions such as `assertTrue(true)`; MockMvc
  asserting only `status().isOk()` on a body-producing endpoint; `assertNotNull` standing in for the
  real assertion; mock verification aimed at the test's own stub; a `catch` that swallows a failure
  with no `fail()`; assertions in unreachable position, such as inside an unexecuted lambda or an empty
  `Optional`; soft assertions never `assertAll()`-ed; fixture-only tests that never invoke the unit;
  commented-out or TODO'd assertions; `@Disabled`/`@Ignore` added with no replacement; an assertion
  weakened in place; and a test that relies only on inherited fixture setup or lifecycle methods.
- *Not violations*: `contextLoads()` with an empty body — the verification is that the context starts,
  and `AGENTS.md` names it the dual-engine standard; verification through
  the named `support/AccessControlAssertions` helpers, whose verification semantics are fixed by this
  criterion; parameterized tests delegating to an assertion helper whose real verification is visible
  in the same diff; `assertThrows` as the whole body; Playwright auto-waiting assertions; non-test
  helpers under a test source set such as `UserFixtures` and `ResortTestRepository`. Extending
  `TwoUserIntegrationTestBase`, or having `@BeforeEach` / `@AfterEach`, is setup rather than
  verification and never excuses an otherwise assertion-free test.
- *Severity rollout override*: all findings from this criterion are `medium` in the initial rollout —
  both additive weakness (a new test with no verification, a tautological assertion, status-only on a
  body-producing endpoint, a fixture-only test) and protection removal (an assertion weakened in
  place, `@Disabled` added with no replacement, a swallowed-exception catch). State explicitly that
  protection removal would normally map to `high` under the Phase 3 global rubric, but this
  criterion-specific cap takes precedence until a separate, measured promotion change.

Also update the document's own prose: the "Five criteria" opening and the "these five ids" note.

#### 2. The schema enum

**File**: `packages/code-reviewer/src/schema.ts`

**Intent**: Register the id so the criterion is scoreable.

**Contract**: `"test-verifies-behavior"` appended to `CRITERION_IDS`. Appending rather than inserting
puts it last in the rendered table, since `render.ts` orders by `CRITERION_IDS.indexOf`. No other
schema edit is needed — `.length(CRITERION_IDS.length)` and the uniqueness refinement already derive
from the array.

#### 3. Count assertions and prose

**File**: `packages/code-reviewer/test/schema.test.ts`, `test/prompt.test.ts`,
`packages/code-reviewer/README.md`

**Intent**: Update the one test that hardcodes the count, extend the embedding check to the new
criterion, and correct the user-facing count.

**Contract**: `schema.test.ts`'s "enumerates five distinct criteria" becomes six, in both the length
and the `Set` size assertion, and its title is renamed to match. `prompt.test.ts` gains an assertion
that a distinctive phrase from the new criterion's carve-outs — `contextLoads` is the natural choice —
is embedded in the prompt, mirroring the existing `BIGINT GENERATED BY DEFAULT AS IDENTITY` check.
`README.md`'s "scores five criteria" becomes six. The id-lockstep tests need no edit; they derive from
`CRITERION_IDS` and must simply stay green.

### Success Criteria:

#### Automated Verification:

- `npm test` passes — in particular the id-lockstep test asserting `parseCriterionIds` and
  `CRITERION_IDS` match in both directions
- `npm run typecheck` passes
- The criteria-count assertion reads six and passes
- A test asserts the new criterion's carve-out prose reaches the assembled prompt
- `rg -i 'five criteria|these five ids' packages/code-reviewer` returns no match outside
  `fixtures/expectations.json` and `promptfoo/tests.yaml`, which Phase 5 owns

#### Manual Verification:

- Every "Not a violation" rule is checked against a representative repo file or fixture; at minimum
  verify `NextslopeApplicationTests.contextLoads()`, a `support/AccessControlAssertions` caller, and
  that a `TwoUserIntegrationTestBase` subclass still needs its own visible verification
- The criterion is genuinely decidable from a diff alone, with no repo read required
- The all-medium rollout override explicitly takes precedence over the Phase 3 default for this
  criterion and points high-severity promotion to a separate measured change

---

## Phase 5: Fixtures and Harness

### Overview

Make the new criterion and the new field measurable: one new fixture as the positive case, the three
existing fixtures as false-positive controls, and harness support for asserting not-applicable.

### Changes Required:

#### 1. The positive-case fixture

**File**: `packages/code-reviewer/fixtures/assertion-free-tests.patch` (new)

**Intent**: Plant additive weakness, two protection-removal cases, and a decoy in one patch, so one
paid run proves the staged all-medium rollout and the false-positive rule together.

**Contract**: A valid unified diff — correct `diff --git` headers and hunk line counts — touching only
test sources, so the other five criteria have nothing to fire on. Against
`src/test/java/com/nextslope/recommendation/WeightedDistanceScorerTests.java`: an existing
`assertThat(breakdown.alignDiff()).isEqualTo(...)` weakened in place to a bare non-null check
(`medium` under the rollout cap), and `@Disabled` added to another test method with no replacement
(`medium` under the rollout cap). Plus a new `@Test` method with a fully populated fixture that never
asserts anything (`medium`). The decoy: a new test whose entire body is an `assertThrows` call —
correct by the criterion's own carve-out, and the thing that would catch a criterion firing on any
touched test file.

This patch is review input and must never be applied to the working tree.

#### 2. Fixture expectations

**File**: `packages/code-reviewer/fixtures/expectations.json`

**Intent**: Add the new fixture, arm the three existing ones against false positives, and make
`applicable` independently assertable.

**Contract**: A new `expectedNotApplicable` array alongside `expectedCriteria` and
`forbiddenCriteria`, documented in the existing `$comment` block with the same semantics the others
have — every listed criterion must come back with `applicable: false`. `clean-diff` declares
`test-verifies-behavior` as expected-not-applicable, since it touches no test file. All three existing
fixtures add `test-verifies-behavior` to `forbiddenCriteria`; for `sample-diff` and
`sample-diff-broken` this is the sharp control, because both modify `HtmxSmokeE2eTests` while leaving
its `assertThat` calls intact. The new fixture expects `test-verifies-behavior` and forbids the other
five. Update the `sample-diff` description's "all five criteria" phrasing.

The new fixture also declares optional `expectedFindings` entries, each with `criterionId`,
`severity`, and an inclusive `lineRange`, plus optional `forbiddenFindingRanges`. Give the
assertion-free test and both protection-removal cases distinct `medium` ranges, and give the
`assertThrows` decoy a forbidden range for `test-verifies-behavior`. Keep the four method ranges
non-overlapping in the static patch so matching is one-to-one without relying on probabilistic
message text. Existing fixtures may omit both fields.

#### 3. Harness support for not-applicable

**File**: `packages/code-reviewer/scripts/verify.ts`

**Intent**: Check the new expectation the same way criterion ids are already checked.

**Contract**: `FixtureExpectation` gains `expectedNotApplicable`; `FixtureOutcome` gains the
corresponding mismatch list, read from `report.data.criteria` where `applicable` is false rather than
from `findings`. The pass condition extends to it, and `report()` prints a line for it consistent with
the existing `MISSING` / `UNEXPECTED` output. Treat the field as optional in the JSON so a fixture that
omits it is simply not asserted on, matching how a criterion in neither existing list behaves.

Add the optional `expectedFindings` and `forbiddenFindingRanges` shapes from `expectations.json`.
Match every expected entry one-to-one against a finding with the same criterion, severity, and a line
inside its range; fail on any unmatched entry or any criterion finding inside a forbidden range.
Report those mismatches separately from missing/unexpected criterion ids. This makes one paid fixture
prove all three planted violations and the decoy without matching free-text messages.

Add an optional `--artifacts-dir <path>` to the harness. Without it, keep today's temporary-directory
cleanup. With it, require a fresh destination, write each CLI run to `<path>/<fixture-name>/`, retain
that run's `review.json` and `review.md`, and write the captured interleaved stdout/stderr as
`run.log`. This changes only evidence retention; it must not add another model call or alter the
review prompt.

#### 4. promptfoo assertion and test vars

**File**: `packages/code-reviewer/promptfoo/assertions.js`, `promptfoo/tests.yaml`

**Intent**: Stop the score floor judging a meaningless number, add the escape-hatch guard, and mirror
the fixtures into promptfoo's var shape.

**Contract**: In `assertions.js`, the per-id map carries the whole criterion entry rather than just
the score, so both loops can skip entries with `applicable === false` before applying
`PLANTED_DEFECT_MAX_SCORE` or `UNVIOLATED_CRITERION_MIN_SCORE`. A new problem is reported when an
`expectedCriteria` entry comes back not applicable — a criterion the fixture plants a defect against
cannot legitimately be inapplicable, and this is the guard against `applicable` becoming a way to dodge
judgement. Apply the same one-to-one severity/range matching and forbidden-range check as `verify.ts`.
`tests.yaml` gains the fourth fixture plus `expectedNotApplicable`, `expectedFindings`, and
`forbiddenFindingRanges`, all nested inside the single `fixture` object so promptfoo does not expand
them; its "all five criteria" description text is updated too.

### Success Criteria:

#### Automated Verification:

- `npm test` passes
- `npm run typecheck` passes
- `git apply --check --reverse` confirms the new patch does not already describe the working tree, and
  `git apply --check` confirms it is a well-formed patch that *would* apply — run as a validity check
  only, never followed by an actual apply
- `promptfoo/tests.yaml` yields exactly four test cases, not nineteen — confirming no var expansion
- `expectations.json` parses, its fixture names match the four patch filenames, and every structured
  finding range points to added lines in the new static patch

#### Manual Verification:

- The new patch's decoy (`assertThrows`-only test) is genuinely correct by the criterion's carve-outs,
  so a finding against it would be a true false positive
- Each fixture's criterion lists are internally consistent, and the new fixture declares one expected
  `medium` finding for each of its three planted violations plus one forbidden decoy range
- The patch was never applied to the working tree (`git status` clean for `src/`)

---

## Phase 6: Paid Verification and Hand-off

### Overview

Spend the second and normally last set of API calls, diff the result against the Phase 1 baseline,
and record the comparison as the evidence for both open questions. A fail-on-boundary crossing is the
only condition that triggers the additional confirmation run defined below.

### Changes Required:

#### 1. Post-change verification artifacts

**File**: `context/changes/test-verifies-behavior/verification/` (new directory)

**Intent**: Capture the six-criterion reviewer's behavior through the same CLI path and in the same
per-fixture artifact shape as the baseline, while using the post-change harness as the 4/4 gate.

**Contract**: Run `npm run verify -- --artifacts-dir
../../context/changes/test-verifies-behavior/verification/fixtures` and capture the harness output as
`verify.log`; each of the four fixture directories retains `run.log`, `review.json`, and `review.md`.
Capture `promptfoo.log` from `npm run promptfoo` as before.

#### 2. The comparison

**File**: `context/changes/test-verifies-behavior/verification/comparison.md`

**Intent**: Answer the two questions the baseline exists for, in writing.

**Contract**: Build a table per fixture from the retained baseline and post-change `review.json` plus
`run.log`, comparing criterion ids reported in `findings`, per-criterion score, per-finding severity,
turns, and cost. Then state two explicit verdicts — did the sixth criterion degrade the other five,
and did the global severity rubric shift verdicts on them. Score and severity drift on the existing
five is treated according to whether it changes the live gate: score drift and severity movement that
stays on the same side of the configured `--fail-on` boundary are recorded, not gated. If an existing
finding crosses that boundary or changes the fixture report's deterministic passed/blocked outcome,
pause Phase 6, revise the rubric, and run one paid confirmation before hand-off. A criterion that
stopped being found is already a fixture failure.

#### 3. Documentation

**File**: `packages/code-reviewer/AGENTS.md`

**Intent**: Record the three new facts a future contributor to this package would otherwise have to
rediscover.

**Contract**: Under Conventions — that severity now has a rubric, that the global rubric lives in
`src/prompt.ts` while per-criterion anchors live beside their criterion in `prompts/criteria.md`, and
that `applicable: false` means the score carries no meaning and renders as an em dash. Note the
`expectedNotApplicable` fixture list alongside the existing expectation lists. The Cross-Change
Contract section needs no change: `review.json` gained a field, and `review.yml` never parses it.

#### 4. Change and issue status

**File**: `context/changes/test-verifies-behavior/change.md`

**Intent**: Reflect completion and move the tracker.

**Contract**: `status` and `updated` stamped in `change.md`. Linear 10X-20 moves to **In Review** when
the PR to `main` opens — the final pre-merge gate, not intermediate phase work, per
`.cursor/rules/linear-sync.mdc`.

### Success Criteria:

#### Automated Verification:

- `npm run verify -- --artifacts-dir .../verification/fixtures` reports 4/4 fixtures passed on
  criterion ids, N/A state, required severity/range matches, and the forbidden decoy range, while
  retaining a schema-valid `review.json`, rendered `review.md`, and verbose `run.log` for every fixture
- No fixture run exceeds 3 turns or $0.50 — the measured answer to whether six criteria still fit the
  budget
- `npm run promptfoo` completes with four test cases and no `is-json` schema failure
- `npm test` and `npm run typecheck` still pass
- `git diff --stat` shows no change under `.github/` or `src/`

#### Manual Verification:

- `comparison.md` states both verdicts explicitly, backed by retained reports and logs, and confirms
  that no existing finding crossed the configured fail-on boundary; any crossing stopped hand-off
  until a rubric revision and confirmation run removed it
- Each `test-verifies-behavior` finding on the new fixture carries the severity its rubric and rollout
  override predict — `medium` for the assertion-free test, weakened assertion, and `@Disabled`
  addition
- No finding was reported against the `assertThrows` decoy
- The rendered `review.md` for `clean-diff` shows an em dash for `test-verifies-behavior`, not `10/10`
- Linear 10X-20 shows **In Review** once the PR is open

---

## Testing Strategy

### Unit Tests (free, offline, `npm test`)

- Schema: `applicable` required; six distinct criterion ids; existing duplicate/unknown-id/score-range
  guards still hold
- Prompt: id lockstep in both directions; all four severity names present; severity-vs-score
  distinction stated; new criterion's carve-out prose embedded; every existing prompt-injection and
  nonce guard untouched
- Render: em dash for not-applicable; all-not-applicable summary wording; canonical criterion ordering
  with six entries; table integrity against pipes and newlines
- CLI: unchanged exit-code contract, with criteria arrays carrying `applicable`

### Integration Tests (paid, manual)

- `npm run verify -- --artifacts-dir .../verification/fixtures` — four fixtures on criterion ids,
  not-applicable state, and structured finding expectations, with reports and run metadata retained
  for comparison
- `npm run promptfoo` — schema conformance, score-shape assertion, and the prose rubric

### Manual Testing Steps

1. Run `npm run review -- --diff-file fixtures/clean-diff.patch --out /tmp/na-check --verbose` and
   confirm `review.md` renders an em dash for `test-verifies-behavior` and the summary does not claim a
   clean sweep of six compliant criteria.
2. Run the same against `fixtures/assertion-free-tests.patch` and read the findings: all three planted
   violations must be `medium` under the rollout override, and the `assertThrows` decoy must be silent.
3. Re-read the three "not a violation" rules that fire on the repo today against their real files.
4. Confirm `git status` shows no working-tree change under `src/` — no fixture patch was applied.

## Performance Considerations

The only budget that matters is the review session's: `DEFAULT_MAX_TURNS = 3` and
`DEFAULT_MAX_BUDGET_USD = 0.5` (`src/agent.ts:20-21`). A sixth criterion plus a global severity rubric
lengthens every prompt, and `AGENTS.md` already notes that repo reads compete with the turn budget —
which is why the new criterion is written to be decidable from the diff alone. This plan measures the
effect instead of preempting it: Phase 6 gates on no run exceeding 3 turns or $0.50, with Phase 1's
baseline as the comparison. If it does exceed, raising the ceiling is a follow-up decision made against
data, not a guess made in advance.

## Migration Notes

`review.json` gains a required field on each criterion score. The only consumer,
`.github/actions/ai-reviewer`, reads the exit code and posts `review.md` verbatim without parsing the
JSON, so no consumer migration is needed. Old `review.json` files from before this change will not
validate against the new `reviewReportSchema`. Ordinary run artifacts are temporary and never replayed;
the deliberately retained Phase 1 baseline is the exception, so `comparison.md` reads its raw common
fields rather than trying to validate the five-criterion snapshot with the new six-criterion schema.

## References

- Authoritative scope: Linear [10X-20](https://linear.app/10xnextslope/issue/10X-20/code-reviewer-test-verifies-behavior-criterion-applicable-flag)
- Predecessor change: `context/archive/2026-08-08-ci-cd-code-review/` (Linear 10X-19)
- Queued follow-up, deliberately not in scope: `context/archive/2026-08-08-ci-cd-code-review/follow-ups/review-fixes.md` → F4
- Package conventions and the four traps: `packages/code-reviewer/AGENTS.md`
- Criterion / schema lockstep: `packages/code-reviewer/src/prompt.ts:47-49`, `src/schema.ts:24-30`
- Score-shape assertion thresholds: `packages/code-reviewer/promptfoo/assertions.js:12-13`
- promptfoo var-expansion trap: `packages/code-reviewer/promptfoo/tests.yaml:6-10`
- Fixture false-positive control: `packages/code-reviewer/fixtures/sample-diff.patch` (e2e hunk)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: Baseline Capture

#### Automated

- [x] 1.1 `npm test` and `npm run typecheck` pass on the unmodified package, and both logs are captured — e1005c9
- [x] 1.2 All three baseline fixture directories retain a run log, schema-valid JSON, and rendered markdown — e1005c9
- [x] 1.3 `baseline/promptfoo.log` exists, non-empty, records exactly three test cases — e1005c9
- [x] 1.4 `git status` shows no modification under `src/`, `prompts/`, or `fixtures/` — e1005c9

#### Manual

- [x] 1.5 `baseline/notes.md` records resolved model, per-fixture turns, and per-fixture cost — e1005c9
- [x] 1.6 The spend is acknowledged as expected — e1005c9
- [x] 1.7 Linear 10X-20 shows **In Progress** — e1005c9

### Phase 2: `applicable` End-to-End

#### Automated

- [x] 2.1 `npm test` passes — 4a91930
- [x] 2.2 `npm run typecheck` passes — 4a91930
- [x] 2.3 A test asserts the schema rejects a criterion score with no `applicable` field — 4a91930
- [x] 2.4 A test asserts an em dash renders in place of a score for a not-applicable criterion — 4a91930
- [x] 2.5 A test asserts the all-not-applicable summary drops "no findings" and still reads as passed — 4a91930

#### Manual

- [x] 2.6 The rendered table remains valid GitHub-flavored markdown with the em dash in place — 4a91930
- [x] 2.7 The prompt's `applicable` paragraph blocks the escape-hatch reading — 4a91930
- [x] 2.8 The raw-model instruction and README example include `applicable` and its N/A semantics — 4a91930

### Phase 3: Global Severity Rubric

#### Automated

- [x] 3.1 `npm test` passes — 39aa1e3
- [x] 3.2 `npm run typecheck` passes — 39aa1e3
- [x] 3.3 A test asserts all four severity names appear in the prompt — 39aa1e3
- [x] 3.4 A test asserts the prompt distinguishes severity from score — 39aa1e3
- [x] 3.5 The existing "no overall pass/fail requested" assertion still passes — 39aa1e3

#### Manual

- [x] 3.6 The four levels read as mutually exclusive on the add-weakly vs remove-protection axis — 39aa1e3

### Phase 4: The Sixth Criterion

#### Automated

- [x] 4.1 `npm test` passes, including the id-lockstep test in both directions — 387783e
- [x] 4.2 `npm run typecheck` passes — 387783e
- [x] 4.3 The criteria-count assertion reads six and passes — 387783e
- [x] 4.4 A test asserts the new criterion's carve-out prose reaches the assembled prompt — 387783e
- [x] 4.5 No "five criteria" / "these five ids" prose remains outside the Phase 5 files — 387783e

#### Manual

- [x] 4.6 Every carve-out checked against an example, including that the two-user base alone does not count — 387783e
- [x] 4.7 The criterion is decidable from a diff alone, with no repo read required — 387783e
- [x] 4.8 The all-medium rollout override clearly precedes the global default for this criterion — 387783e

### Phase 5: Fixtures and Harness

#### Automated

- [x] 5.1 `npm test` passes — 4e0a417
- [x] 5.2 `npm run typecheck` passes — 4e0a417
- [x] 5.3 `git apply --check` confirms the new patch is well-formed (validity check only, never applied) — 4e0a417
- [x] 5.4 `promptfoo/tests.yaml` yields exactly four test cases, confirming no var expansion — 4e0a417
- [x] 5.5 Expectations parse, match all four patches, and structured ranges target added fixture lines — 4e0a417

#### Manual

- [x] 5.6 The `assertThrows` decoy is genuinely correct by the criterion's carve-outs — 4e0a417
- [x] 5.7 Criterion lists do not overlap; the new fixture declares 3 medium and 1 decoy range — 4e0a417
- [x] 5.8 The patch was never applied to the working tree — 4e0a417

### Phase 6: Paid Verification and Hand-off

#### Automated

- [x] 6.1 `npm run verify -- --artifacts-dir ...` reports 4/4 on ids, N/A, severities, and decoy silence, retaining all artifacts
- [x] 6.2 No fixture run exceeds 3 turns or $0.50
- [x] 6.3 `npm run promptfoo` completes with four test cases and no `is-json` failure
- [x] 6.4 `npm test` and `npm run typecheck` still pass
- [x] 6.5 `git diff --stat` shows no change under `.github/` or `src/`

#### Manual

- [x] 6.6 Comparison proves no existing finding crossed the fail-on boundary after any required revision
- [x] 6.7 All three planted findings are medium under the staged rollout override
- [x] 6.8 No finding was reported against the `assertThrows` decoy
- [x] 6.9 `clean-diff`'s `review.md` shows an em dash for `test-verifies-behavior`
- [ ] 6.10 Linear 10X-20 shows **In Review** once the PR is open
