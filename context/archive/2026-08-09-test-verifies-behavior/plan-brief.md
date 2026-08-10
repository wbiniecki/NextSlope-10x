# `test-verifies-behavior` Criterion, `applicable` Flag, and Severity Rubric — Plan Brief

> Full plan: `context/changes/test-verifies-behavior/plan.md`
> Authoritative scope: Linear [10X-20](https://linear.app/10xnextslope/issue/10X-20/code-reviewer-test-verifies-behavior-criterion-applicable-flag)

## What & Why

`packages/code-reviewer` gains a sixth **gating** criterion, `test-verifies-behavior`, catching tests
that cannot fail — no assertion, no mock verification, no expected exception. Alongside it, every
criterion score gains `applicable: boolean` so "not applicable" stops rendering as 10/10, and severity
gets a written rubric for the first time. The three ship together because all three touch
`criterionScoreSchema` and the shape of `review.json`, and because a gating criterion produces findings
while `DEFAULT_FAIL_ON = "high"` gates on severity — the rubric stops being optional the moment the
criterion lands.

## Starting Point

The reviewer scores five criteria today and decides pass/fail deterministically in `src/verdict.ts`
from finding severities. Nothing in `src/prompt.ts` or `prompts/criteria.md` says how to pick a
severity, even though that enum is the gate. A review of PR #37 scored all five criteria 10/10 with
justifications reading "does not apply here" — not-applicable and fully-compliant are currently
indistinguishable.

## Desired End State

Six criteria, each carrying `applicable`. A diff touching no test file reports
`test-verifies-behavior` as not applicable, rendering an em dash rather than `10/10`, and an
all-not-applicable run no longer headlines "no findings". An assertion-free new test produces a
`medium` finding; a weakened assertion, an `@Disabled` with no replacement, or a swallowed exception
also produces `medium` during the staged rollout. Protection-removal cases move to `high` only in a
later change backed by measured false-positive data.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Gating vs advisory | Gating, sixth criterion | "This test has no assertion" is falsifiable, and the existing fixture harness covers a gating criterion for free. | Linear 10X-20 |
| Not-applicable representation | `applicable: boolean` | A nullable score risks the emitted draft-07 schema; a boolean is independently assertable in fixtures. | Linear 10X-20 |
| Criterion document | A sixth `##` section in `prompts/criteria.md` | `parseCriterionIds` already scans one file's headings, so the id-lockstep test keeps working with zero plumbing. | Plan |
| Severity rubric scope | Global rubric for all six + a temporary all-medium override for the new criterion | Closes the real gap without rewriting five live criteria while preserving Linear's staged rollout. | Plan |
| Rubric location | Global block in `src/prompt.ts`, anchors in `criteria.md` | Mirrors the 1–10 score guidance, which already sits in `prompt.ts` and is unit-tested there. | Plan |
| `score` when not applicable | Still a valid 1–10 integer, declared meaningless, rendered as an em dash | Avoids coupling two fields via a refinement that would not survive into the JSON schema handed to the model. | Plan |
| promptfoo score checks | Skip not-applicable criteria; flag any expected criterion marked N/A | Stops a spurious paid failure, and guards `applicable` from becoming a way to dodge judgement. | Plan |
| Asserting `applicable` | New `expectedNotApplicable` list in `expectations.json` + `verify.ts` | Otherwise the field's core promise has no automated check anywhere. | Plan |
| New fixture shape | One patch with three medium ranges and a forbidden `assertThrows` range | Proves the staged rollout and false-positive rule in one paid run without matching model prose. | Plan |
| Node test scope | Java only (`src/test/`, `src/e2eTest/`); `packages/` out of scope | Matches the scoped read root and the five existing Java/Spring criteria. | Plan |
| Turn budget | Measure it; gate Phase 6 on ≤3 turns and ≤$0.50 | Avoids raising the per-PR cost ceiling to fix a problem that may not exist. | Plan |
| Merge condition | Fixtures gate structured findings; existing-criterion drift gates only when it crosses `--fail-on` | Scores remain diagnostic while a change to the live passed/blocked outcome cannot slip through as mere drift. | Plan |
| Rollout | Every finding from the new criterion ships `medium` initially | Reports on every PR without blocking under `--fail-on high`; protection-removal cases are promoted only after measured false-positive data earns it. | Linear 10X-20 |

## Scope

**In scope:** the sixth criterion in `criteria.md` + `CRITERION_IDS`; `applicable` on
`criterionScoreSchema`; prompt guidance for `applicable` and severity; em-dash rendering and the
all-N/A summary clause; five unit-test files; the raw-model output instruction and README example; one
new fixture patch; `expectations.json`, `verify.ts`, `tests.yaml`, `assertions.js`; paid baseline and
post-change comparison; package `AGENTS.md`.

**Out of scope:** an advisory criterion tier; `internal-consistency` / plan-conformance; nullable
score; full per-criterion anchors for the existing five; blanket promotion to `high`; changes to
`DEFAULT_MAX_TURNS` / `DEFAULT_MAX_BUDGET_USD`; anything under `.github/` (including the queued F4
follow-up that wires the package's free tests into `ci.yml`); applying any fixture patch to the
working tree.

## Architecture / Approach

Nothing structural changes. `CRITERION_IDS` and `criteria.md` gain one entry each and must land in the
same commit, since a lockstep test asserts they match in both directions. `criterionScoreSchema` gains
one field, which propagates automatically into `verdictJsonSchema` and the gitignored generated schema
promptfoo validates against. `verdict.ts` is untouched — the gate stays deterministic and the model
still never decides pass/fail. Both promptfoo suites share `tests.yaml` and `assertions.js`, so the
compare config needs no edit. `review.json` gains a field, but the composite action only reads the exit
code and posts `review.md` verbatim, so no workflow changes.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Baseline capture | Pre-change free-check logs plus per-fixture CLI reports/run metadata and promptfoo log | Irreversible ordering — skip it and the change has no control; costs real API calls |
| 2. `applicable` end-to-end | Schema field, prompt rule, em-dash render, four test builders and output docs repaired | The field becoming an escape hatch that silently weakens every fixture |
| 3. Global severity rubric | Four levels defined in `prompt.ts`, unit-guarded | Shifts severities on the five live criteria — measured, with fail-on crossings stopped |
| 4. The sixth criterion | `criteria.md` section, enum entry, count assertions, prose fixes | False positives on correct code; two carve-outs fire on the repo today |
| 5. Fixtures and harness | New patch, N/A and structured-finding expectations, retained artifacts | promptfoo var expansion turning four cases into nineteen |
| 6. Paid verification | 4/4 fixtures, baseline comparison and fail-on drift gate, docs, Linear → In Review | Six criteria may not fit 3 turns / $0.50 |

**Prerequisites:** on branch `feature/10x-20-test-verifies-behavior` (already checked out) with a clean
working tree; `npm install` done in `packages/code-reviewer`; a working Claude subscription OAuth
credential, since Phases 1 and 6 make real API calls.

**Estimated effort:** ~4–6 sessions across 6 phases. Phases 2–5 are entirely free and offline; all cost
sits in Phases 1 and 6 (roughly a dozen paid sessions total across both suites).

## Open Risks & Assumptions

- The criterion has no in-repo examples of `assertThrows`, `assertNotNull`, `verifyNoInteractions`, or
  `@Disabled` to generalize from — they appear nowhere today, so recognition rests entirely on the
  criterion's own prose.
- The global severity rubric may shift severities on the existing five. Same-side drift is recorded;
  any crossing of the configured fail-on boundary stops hand-off until the rubric is revised and a
  paid confirmation run removes the gate change.
- Six criteria may not fit the 3-turn budget. Phase 6 gates on it; the remedy is a follow-up decision
  made against data.
- Both paid suites are probabilistic, so a single fixture failure may be noise rather than regression —
  and re-running to find out costs money.
- The package's free unit tests still do not run in CI (queued follow-up F4), so these invariants remain
  guarded only by local runs.

## Success Criteria (Summary)

- A PR that adds an assertion-free test, weakens an assertion, or disables a test with no replacement
  gets a `medium` finding during the staged rollout.
- A PR touching no test file shows `test-verifies-behavior` as an em dash, not `10/10`, and a review
  where nothing applied says so instead of reading as a clean pass.
- The five existing criteria still find every defect they found before, proven by the baseline diff.
