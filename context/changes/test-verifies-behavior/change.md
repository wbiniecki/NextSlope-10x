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

Dropped from scope after consideration, recorded so they are not rediscovered: an advisory criterion
tier (no members once this criterion gates) and `internal-consistency` / plan-conformance checking
(needs `--plan-file` plumbing and overlaps `/10x-impl-review`).
