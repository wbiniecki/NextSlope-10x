# Post-change verification — six-criterion reviewer, compared against the Phase 1 baseline

Captured 2026-08-09 on branch `feature/10x-20-test-verifies-behavior`, after Phases 2–5 landed
(`4a91930`, `39aa1e3`, `387783e`, `4e0a417`, plus review fixes `031d093` and `3b90b2d`).

Baseline: `../baseline/` — the five-criterion reviewer, captured before any source edit. Both sides
resolved the same model, `claude-sonnet-5` (Claude Code `2.1.224`), with the same configured budget
(`$0.50`, `max turns 3`) and the same gate (`--fail-on high`).

## Why there are three post-change runs

Phase 6's contract (`plan.md:596-599`) says that if an existing finding crosses the `--fail-on`
boundary, the phase pauses, the rubric is revised, and one paid confirmation runs before hand-off.
Two findings crossed on the first run, so that branch fired. It then took two attempts, because the
first revision was falsified by its own confirmation run — which is recorded here rather than
quietly overwritten.

| Directory | Prompt under test | Harness | Retained |
| --- | --- | --- | --- |
| `fixtures/` | Phase 3 rubric, as shipped by Phase 5 | **4/4** | `verify.log`, four fixture dirs |
| `confirmation/` | + medium/high tie-break, first wording | **3/4** | `verify.log`, four fixture dirs |
| `confirmation-2/` | + medium/high tie-break, reworded | **4/4** | `verify.log`, four fixture dirs |

`confirmation-2/` is the run that matches the prompt this change ships. The other two are kept as
evidence: the first establishes the problem, the second is the falsified fix.

## Harness outcomes

All three runs passed on criterion ids for every fixture that produced a report. The other
assertions are narrower than a `4/4` line makes them sound, and it is worth being exact: only
`clean-diff` declares `expectedNotApplicable`, and only `assertion-free-tests` declares finding
ranges, so for the remaining fixtures those clauses are vacuous rather than satisfied.

The not-applicable check is also one-directional by construction (`scripts/verify.ts:280-282`): it
asserts that a *declared* N/A criterion came back N/A, and never asserts that an undeclared one
stayed applicable. So the `10 → N/A` transitions below are read from the reports, not gated.

The single failure was `confirmation/sample-diff`: exit 2, `max_budget_usd`, no report produced. See
"Budget and reliability" below — it is not a review-quality result.

## Run facts

| Fixture | Baseline | Run 1 | Confirmation 1 | Confirmation 2 |
| --- | --- | --- | --- | --- |
| `sample-diff` | exit 3, 2t, $0.1578 | exit 3, 2t, $0.1591 | **exit 2, budget exhausted** | exit 3, 2t, $0.1716 |
| `sample-diff-broken` | exit 3, 2t, $0.0869 | exit 3, 2t, $0.1748 | exit 3, 2t, $0.1345 | exit 3, 2t, $0.1375 |
| `clean-diff` | exit 0, 2t, $0.0522 | exit 0, 2t, $0.0810 | exit 0, 2t, $0.0856 | exit 0, 2t, $0.0844 |
| `assertion-free-tests` | — | exit 0, 2t, $0.1107 | exit 0, 2t, $0.1084 | exit 0, 2t, $0.1169 |
| **Booked total** | $0.2969 | $0.5256 | $0.3285 | $0.5104 |

Every run that produced a report did so in 2 turns of 3, on both sides of the change. Across the
three fixtures common to baseline and the shipped prompt, cost rose $0.2969 → $0.5104 (+72%) — the
measured price of a sixth criterion, a severity rubric, and the tie-break. Booked totals exclude the
exhausted session, whose ~$0.50 the harness cannot parse because no completed-run cost line is
emitted; true phase spend is roughly $1.86 plus one promptfoo eval.

## Per-criterion scores — baseline vs shipped prompt

`N/A` means `applicable: false`, rendered as an em dash.

| Fixture | Criterion | Baseline | Confirmation 2 |
| --- | --- | --- | --- |
| `sample-diff` | flyway-forward-only | 2 | 2 |
| | ddl-auto-validate | 2 | 2 |
| | constructor-injection | 2 | 2 |
| | access-control-scoping | 1 | 1 |
| | e2e-conventions | 3 | 4 |
| | test-verifies-behavior | — | 9 |
| `sample-diff-broken` | access-control-scoping | 10 | **N/A** |
| `clean-diff` | e2e-conventions | 10 | **N/A** |
| | test-verifies-behavior | — | **N/A** |

Unchanged rows on the other two fixtures are omitted; nothing else moved.

The three `10 → N/A` transitions are the defect that motivated `applicable`, corrected. `clean-diff`
touches no e2e source, so the baseline's `e2e-conventions` 10/10 asserted compliance with a
convention the diff never engaged. `sample-diff-broken` is `sample-diff` minus the IDOR controller
hunk, so with no owned route in the diff `access-control-scoping` has nothing to judge.

Two of those three are my reading of the diffs, not harness-enforced: neither fixture declares the
criterion in `expectedNotApplicable`, so a regression back to a confident 10/10 would pass silently.
Only `clean-diff`'s `test-verifies-behavior` N/A is actually asserted. Declaring the other two is a
cheap follow-up.

`test-verifies-behavior` scoring 9 rather than `N/A` on both diff fixtures is the sharp control
working: both patches modify `HtmxSmokeE2eTests`, so the criterion *does* apply, is scored, and
reports no finding because the touched test's `assertThat` calls remain intact. A reviewer that fired
there would be a false positive; one that declared `N/A` would be dodging a judgement it owed.

## Per-finding severities — the whole arc

| Fixture | Finding | Baseline | Run 1 | Conf 1 | Conf 2 |
| --- | --- | --- | --- | --- | --- |
| `sample-diff` | flyway V3 (edited applied migration) | critical | critical | *(no report)* | critical |
| | flyway V6 (`SERIAL PRIMARY KEY`) | medium | **high** | *(no report)* | medium |
| | ddl-auto (`validate` → `update`) | critical | high | *(no report)* | high |
| | constructor-injection (field `@Autowired`) | high | high | *(no report)* | **medium** |
| | access-control (unscoped `/profile/{userId}`) | critical | critical | *(no report)* | critical |
| | e2e-conventions (`waitForTimeout`) | medium | medium | *(no report)* | medium |
| `sample-diff-broken` | flyway V3 | critical | critical | critical | critical |
| | flyway V6 (`SERIAL PRIMARY KEY`) | medium | medium | **high** | medium |
| | ddl-auto | critical | high | high | high |
| | constructor-injection | high | **medium** | **high** | **medium** |
| | e2e-conventions | medium | medium | medium | medium |

Every criterion the baseline found is still found, in every run. No criterion stopped being reported,
which would have been a fixture failure rather than drift.

### What the three runs established

**Run 1 — the crossings, and an argument that overreached.** Two findings crossed the boundary in
opposite directions: `sample-diff`'s V6 finding rose `medium → high`, and `sample-diff-broken`'s
field-injection finding fell `high → medium`. `sample-diff-broken` is byte-identical to `sample-diff`
apart from the removed `ProfileController` hunk — verified by diffing the patches — so the same two
hunks drew different severities under one rubric within a single run. That was originally written up
here as proof of run-to-run variance. It was not: the two fixtures are different prompts, so it
showed context sensitivity at N=1, and it ignored that the baseline had been internally *consistent*
on exactly these two defects.

**Confirmation 1 — the first tie-break, falsified.** A global tie-break was added to `src/prompt.ts`
resolving `medium` against `high` for a pure addition: if nothing that worked before is removed, and
the failure is conditional, report `medium`. The prediction, recorded before spending, was that V6
and field injection would both settle at `medium`. **Both came back `high`.** The model's own words
show why, and the wording was at fault: for V6 it wrote that the migration *"is reachable by both
engines... and risks failing to apply identically"* — attaching "reachable" to the artifact rather
than to the failure, while still calling the failure a *risk*, which the tie-break assigns to
`medium`. The field-injection finding engaged the tie-break not at all.

This run also delivered the repeated-trial evidence Run 1 lacked. On `sample-diff-broken`, the same
input moved `medium → high` on **both** frontier defects between Run 1 and Confirmation 1. The
medium/high frontier is genuinely unstable for these two, not merely context-sensitive.

**Confirmation 2 — the reworded tie-break, confirmed.** The tie-break was rewritten to make the
model's own hedging vocabulary the operative test: if the most accurate description is that the
change *risks*, *could*, or *may* cause a failure, it is `medium`; `high` requires a failure
definitely present in the merged change. All six severity predictions, again recorded before
spending, came true — and the two fixtures now agree with each other on every shared finding, which
is the internal consistency the baseline had and the two earlier post-change runs had lost.

Critically, the tie-break did **not** demote the `critical` anchors it was written to leave alone:
the IDOR route stayed `critical` and the edited applied migration stayed `critical` in every run.

### The one remaining crossing, and it is deliberate

Against the shipped prompt, exactly one existing finding crosses the `--fail-on high` boundary:
**field injection, `high` → `medium`**, now identically in both fixtures rather than flickering.

This is the rubric working, not drifting. Field injection removes no protection and breaks nothing at
runtime; it is a convention violation, which the rubric places at `medium` by design. The baseline's
`high` was improvised against no written rubric at all — the absence this change exists to fix.

It has a real consequence worth stating rather than burying: under `--fail-on high`, a PR whose only
defect is field injection will now be **reported but not blocked**. The finding still appears in the
PR comment. If field injection should block, the fix is a per-criterion severity anchor on
`constructor-injection`, not a change to the global rubric — the same mechanism
`test-verifies-behavior` already uses for its rollout cap.

V6's crossing is gone: `medium` in the baseline, `medium` in both fixtures under the shipped prompt.

No fixture's deterministic outcome changed in any run: `sample-diff` and `sample-diff-broken` blocked
(exit 3) throughout, `clean-diff` and `assertion-free-tests` passed (exit 0) throughout.

## Verdict 1 — did the sixth criterion degrade the other five?

**No.** All five existing criteria are still reported wherever the baseline reported them, with no
criterion lost and no new false positive on `clean-diff`. Scores moved by at most one point on a
single criterion (`e2e-conventions` 3→4), which is diagnostic-only and does not touch the gate. The
three `10 → N/A` transitions are corrections, not degradations: each replaces an asserted compliance
the diff never earned.

The measured costs are budget and one severity policy change, not review quality.

## Verdict 2 — did the global severity rubric shift verdicts?

**Not on the gate. At the finding level, yes — once, deliberately, and now stably.**

Every fixture's passed/blocked outcome is identical to the baseline across all three runs. Under the
shipped prompt, one finding crosses the boundary — field injection `high → medium` — and it is a
principled correction of an unwritten baseline severity, reproducible across both fixtures, with a
named remedy (a per-criterion anchor) if the project disagrees.

The two `critical → high` moves on `ddl-auto` stay on the blocking side and read as the rubric doing
its job: `critical` is now anchored to irreversible or production-breaking change, and a reversible
config flag is not that.

The honest caveat: reaching that stability took two attempts, and the first attempt's prose read
plausibly while producing the opposite of its intent. A global prose rubric is a probabilistic
instrument. Against that, the one **mechanical, per-criterion** cap in this change —
`test-verifies-behavior`'s all-`medium` rollout override — returned exactly three `medium` findings in
all three runs without a single deviation. That contrast is the most transferable thing this phase
measured: **per-criterion caps hold; global prose anchors need to be verified against a paid run
before they can be trusted.**

## promptfoo

Run twice, once per prompt. Both evals produced **four test cases, not nineteen** — the
var-expansion trap survives the three nested structures Phase 5 added to `tests.yaml`.

| Eval | Prompt | Log | Result |
| --- | --- | --- | --- |
| `eval-LbU-2026-08-09T18:35:24` | baseline, five criteria | `../baseline/promptfoo.log` | 2 passed, 1 failed (`llm-rubric` grader returned unparseable JSON) |
| `eval-dCj-2026-08-09T21:26:34` | Phase 3 rubric, pre-tie-break | `promptfoo.log` | 3 passed, 0 failed, 1 error (`clean-diff` exhausted max turns) |
| `eval-ixW-2026-08-09T22:51:51` | **shipped prompt** | `confirmation-2/promptfoo.log` | **4 passed, 0 failed, 0 errors** |

The shipped prompt is the only one of the three to produce a completely clean eval — cleaner than the
baseline, which had a grader failure. No `is-json` schema failure in any post-change eval, so the
criterion entries carrying the new required `applicable` field validate against the regenerated
draft-07 schema, and the score-shape assertion agreed with every fixture's planted defects.

The middle eval's turn exhaustion did not recur under the shipped prompt, which is consistent with
the budget failures being intermittent session pathologies rather than a prompt-length effect. Both
non-clean results are infrastructure-class outcomes rather than review defects, and neither was
re-run to chase a green — a failing run is evidence.

## Budget and reliability — the sharpest operational finding

Two budget-class failures in three paid rounds, on different limits:

- `clean-diff` exhausted **max turns** under promptfoo.
- `sample-diff` exhausted the **$0.50 budget** in Confirmation 1, producing no report at all.

Neither is systematic, and neither recurred: `sample-diff` cost $0.1591 and $0.1716 in the runs
either side of its failure, the shipped-prompt promptfoo eval completed all four cases cleanly, and
every other session in this phase finished in 2 turns of 3. These are intermittent session
pathologies rather than a prompt-length effect — but they are not rare either: two in roughly sixteen
paid sessions.

That matters because it is the shipped path. `DEFAULT_MAX_TURNS = 3` (`src/agent.ts:20`) has no CLI
override — `cli.ts:270` prints it but never reads a flag for it — so the ceiling exhausted here is
exactly the one `.github/workflows/review.yml` runs on every pull request. Exhaustion means exit 2,
which the composite action maps to a failure verdict and an `ai-cr:failed` label on code that may be
perfectly correct.

It does not block this change: `review.yml` is deliberately not a required check, precisely because
an LLM verdict is probabilistic. But it converts a speculative worry into a measured one.

### Follow-ups this phase earned

1. **Raise or expose `DEFAULT_MAX_TURNS`, and reconsider `DEFAULT_MAX_BUDGET_USD`.** Measured: two
   exhaustions, one of each kind, on the path CI runs.
2. **Per-criterion severity anchors for `constructor-injection` and `flyway-forward-only`.** The
   frontier needed two attempts to stabilize globally; anchors are the mechanical fix, and the
   `test-verifies-behavior` cap is the proof they work.
3. **Declare `expectedNotApplicable` for `clean-diff`'s `e2e-conventions` and
   `sample-diff-broken`'s `access-control-scoping`,** so the two unasserted N/A corrections are
   actually gated.
4. **Promote `test-verifies-behavior` protection-removal findings to `high`** once a false-positive
   rate against real PRs earns it — the deferral this change shipped with.

## New fixture — `assertion-free-tests`

No baseline exists; this fixture ships with the change. All six criteria resolved as intended in
every run: the five existing criteria came back `N/A` (the patch touches only a JUnit test file), and
`test-verifies-behavior` produced exactly three findings.

| Planted violation | Line | Severity | Declared range |
| --- | --- | --- | --- |
| Assertion weakened in place to `isNotNull()` | 35 | medium | `[29, 36]` |
| `@Disabled` added with no replacement | 38 | medium | `[37, 41]` |
| New test that never asserts | 82 | medium | `[81, 90]` |
| `assertThrows`-only decoy | — | *(none)* | forbidden `[91, 97]` — no hit |

Identical in all three runs: same three lines, same three severities, decoy silent every time.

All three are `medium`, as the rollout override requires, including the two protection-removal cases
the global rubric would otherwise place at `high`. The reviewer applied the cap knowingly rather than
by accident — in Run 1 the weakened-assertion finding said so in its own words: *"severity capped at
medium per the criterion's rollout rule despite this being a removed protection."*

**The F6 failure mode never fired.** `change.md` warned the tight `expectedFindings` ranges were the
most likely cause of a Phase 6 failure that is not a review-quality failure, naming `[37, 41]` as the
specific risk. All three findings anchored inside their declared ranges on all three runs, so no
range needed widening.

## Note on Phase 6's "no change under `src/`" criterion

Success criterion 6.5 reads "no change under `.github/` or `src/`". Phase 6 modified
`packages/code-reviewer/src/prompt.ts` and `test/prompt.test.ts`, because the contract's crossing
branch mandates revising the rubric and Phase 3 put the rubric in `src/prompt.ts`. The two
instructions conflict as literally written; the crossing branch is the more specific and explicitly
conditional one, so it governs. 6.5 is read as its evident intent — the Java application's `src/` and
the CI workflows — and both are untouched.
