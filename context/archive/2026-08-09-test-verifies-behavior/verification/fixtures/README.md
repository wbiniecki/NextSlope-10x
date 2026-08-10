# Run 1 — pre-tie-break prompt. Not what shipped.

These artifacts were captured against the **Phase 3 rubric as shipped by Phase 5**, before the
`medium`/`high` tie-break was added to `src/prompt.ts`. Two findings crossed the `--fail-on high`
boundary on this run, which fired Phase 6's revise-and-confirm branch.

**The evidence for the prompt this change actually ships is in
[`../confirmation-2/`](../confirmation-2/).**

| Directory | Prompt under test | Harness |
| --- | --- | --- |
| `./` (this one) | Phase 3 rubric, pre-tie-break | 4/4 |
| `../confirmation/` | + tie-break, first wording — **falsified by its own run** | 3/4 |
| `../confirmation-2/` | + tie-break, reworded — **shipped** | 4/4 |

The concrete trap: `sample-diff/review.json` here grades the Flyway V6 finding `high`. Under the
shipped prompt it is `medium`. Reading this directory as "the post-change behavior" gets that
backwards.

Retained rather than overwritten because a superseded run is evidence: it is what established the
boundary crossing in the first place. Full analysis, including why there are three post-change runs,
is in [`../comparison.md`](../comparison.md).

Phase 6 §1 of the plan names this path as the post-change evidence directory. That was written
before the crossing branch fired and produced two further runs; treat `../confirmation-2/` as the
canonical one.
