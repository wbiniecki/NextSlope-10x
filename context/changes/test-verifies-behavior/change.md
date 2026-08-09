---
change_id: test-verifies-behavior
title: Sixth gating criterion for tests that cannot fail, plus applicable flag and severity rubric
status: impl_reviewed
created: 2026-08-09
updated: 2026-08-09
archived_at: null
---

## Notes

Linear: [10X-20](https://linear.app/10xnextslope/issue/10X-20/code-reviewer-test-verifies-behavior-criterion-applicable-flag)
carries the authoritative scope. Follows
[10X-19](https://linear.app/10xnextslope/issue/10X-19/cicd-ai-code-review-on-every-pr-to-main-gha-workflow-composite-action)
(`ci-cd-code-review`), archived at `context/archive/2026-08-08-ci-cd-code-review/`.

Three things ship together because all three touch `criterionScoreSchema` and the shape of
`review.json`:

1. `test-verifies-behavior` as a sixth **gating** criterion — tests that cannot fail (no assertion,
   no mock verification, no expected exception).
2. `applicable: boolean` on each criterion score, so "not applicable" stops rendering as 10/10.
3. A severity rubric — unavoidable now, because the new criterion produces findings and severity is
   what `DEFAULT_FAIL_ON` gates on. Nothing in `prompt.ts` or `prompts/criteria.md` specifies it
   today.

**Do the baseline before any code change.** Run each current fixture directly through the CLI once
with `--verbose`, retaining its log, `review.json`, and `review.md`, then run `npm run promptfoo`.
It cannot be reconstructed afterward and it is the only control for two questions: did a sixth
criterion degrade the other five, and did writing severity anchors shift verdicts. Costs real API
calls.

**Rollout intent, easy to lose during implementation:** ship every `test-verifies-behavior` finding
at `medium` so it reports on every PR without blocking under `--fail-on high`. Promote the
protection-removal cases (assertion weakened in place, `@Disabled` with no replacement, swallowed
exception) to `high` only once the false-positive rate against real PRs earns it.

**Before spending in Phase 6, know the one failure mode that is not a review-quality failure.** The
new fixture's `expectedFindings` ranges are deliberately tight, and a miss is more likely to be an
anchoring disagreement than a criterion defect. `[37, 41]` covers the `@Disabled` line, `@Test`, and
the method signature but not the disabled test's body, and a reviewer anchoring that finding to the
now-dead assertion — or to the added `import ...Disabled` on line 9 — is not wrong. The forbidden
decoy range starts at line 91, the blank line immediately after the assertion-free test's closing
brace, so a one-line slip fails twice: unmatched expectation *and* false-positive hit. If Phase 6
fails only on ranges, say so in `comparison.md` and widen them; do not read it as the criterion
misfiring. Decided in `reviews/impl-review-phase-5.md` → F6.

**Phase 6 outcome, and the one deliberate gate change.** The F6 range risk never materialized — all
three findings anchored inside their tight ranges on all three paid runs. What did happen: two
existing findings crossed the `--fail-on high` boundary on the first run, firing the contract's
revise-and-confirm branch. It took two attempts. The first tie-break was falsified by its own
confirmation run (the model attached "reachable" to the artifact instead of the failure and graded a
self-described "risk" as `high`); the reworded one, which makes the model's own hedging vocabulary
the test, held across both fixtures and matched all six pre-registered predictions.

Against the shipped prompt exactly one crossing remains, deliberately: **field injection is now
`medium`, not `high`**, so a PR whose only defect is field injection is reported but no longer
blocked under `--fail-on high`. That is the rubric working — field injection removes no protection
and breaks nothing — and the baseline's `high` was improvised against no rubric at all. If it should
block, the fix is a per-criterion anchor on `constructor-injection`, not a global rubric change.
Full evidence and the four earned follow-ups (turn/budget ceilings, per-criterion anchors, two
unasserted N/A declarations, the deferred `high` promotion) are in `verification/comparison.md`.

Dropped from scope after consideration, recorded so they are not rediscovered: an advisory criterion
tier (no members once this criterion gates) and `internal-consistency` / plan-conformance checking
(needs `--plan-file` plumbing and overlaps `/10x-impl-review`).
