# Baseline — five-criterion reviewer, before any source edit

Captured 2026-08-09, on branch `feature/10x-20-test-verifies-behavior`, with
`packages/code-reviewer` unmodified (`git status` clean for `src/`, `prompts/`, `fixtures/`).

This is the control for Phase 6's two questions: did a sixth criterion degrade the other five, and
did writing severity anchors shift verdicts on them.

## Free checks

| Check | Exit | Log |
| --- | --- | --- |
| `npm test` | 0 | `test.log` |
| `npm run typecheck` | 0 | `typecheck.log` |

## Fixture runs — direct CLI, one session each

Invoked from `packages/code-reviewer` as
`npm run review -- --diff-file fixtures/<name>.patch --out <this dir>/fixtures/<name> --verbose`,
retaining `run.log`, `review.json`, and `review.md` per fixture. Resolved model
`claude-sonnet-5` (Claude Code `2.1.224`) on all three; configured budget `$0.50`, `max turns 3`,
`--fail-on high`.

| Fixture | Exit | Turns | Cost | Outcome |
| --- | --- | --- | --- | --- |
| `sample-diff` | 3 (blocked) | 2 | $0.1578 | All five expected criteria found |
| `sample-diff-broken` | 3 (blocked) | 2 | $0.0869 | Four expected found; `access-control-scoping` correctly silent |
| `clean-diff` | 0 (passed) | 2 | $0.0522 | No findings |

Fixture-run total: **$0.2969** across three sessions. Every run finished in 2 turns, one under the
3-turn ceiling — the headroom a sixth criterion and a severity rubric will eat into.

### Per-criterion scores and finding severities

| Fixture | Scores | Finding severities |
| --- | --- | --- |
| `sample-diff` | flyway 2, ddl-auto 2, ctor-injection 2, access-control 1, e2e 3 | flyway critical + medium, ddl-auto critical, ctor-injection high, access-control critical, e2e medium |
| `sample-diff-broken` | flyway 2, ddl-auto 2, ctor-injection 2, access-control 10, e2e 4 | flyway critical + medium, ddl-auto critical, ctor-injection high, e2e medium |
| `clean-diff` | all five 10 | none |

`clean-diff` scoring 10/10 across all five is the exact defect motivating `applicable`: nothing in
that diff touches a migration or a controller, so four of those five tens mean "not applicable", not
"fully compliant". After this change the same run should render em dashes.

## promptfoo

`npm run promptfoo` → `promptfoo.log`, eval id `eval-LbU-2026-08-09T18:35:24`, duration 1m 10s,
exit 100. **Three test cases, not fourteen** — the var-expansion trap is not tripped in the
pre-change `tests.yaml`.

Results: 2 passed, 1 failed, 0 errors. The failure is `sample-diff-broken` on the `llm-rubric`
assertion with reason `Could not extract JSON from llm-rubric response`. Both deterministic
assertions on that same case passed (`is-json`, and the `javascript` score-shape check reporting
"Criterion scores agree with the fixture's planted defects"), and the direct CLI run of the same
fixture above found exactly the four expected criteria while staying silent on
`access-control-scoping`.

So this is a grader-side infrastructure failure — the rubric model's own response was unparseable —
not a review defect. Recorded rather than re-run, per the plan: a failing baseline run is evidence.
Phase 6 should compare against this known-flaky case rather than treating a green rubric there as an
improvement.

## Spend

Three fixture sessions ($0.2969) plus the promptfoo eval's three sessions. Acknowledged as the
expected cost of an irrecoverable baseline.
