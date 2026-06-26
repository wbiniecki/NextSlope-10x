# Lessons Learned

> Append-only register of recurring rules and patterns. Re-read at start by /10x-frame, /10x-research, /10x-plan, /10x-plan-review, /10x-implement, /10x-impl-review.

## Sync Linear issue status with real work progress

- **Context**: Any phase/change that starts or finishes work mapped to a roadmap item
- **Problem**: Linear status drifts from reality; issues stay in the wrong state, losing traceability between work and tracker
- **Rule**: For each item being worked on, find the corresponding Linear issue in the project and check whether its status needs an update (and update it to reflect real progress).
- **Applies to**: implement, impl-review

## Plan navigation to every new screen

- **Context**: Any plan phase that introduces a new screen/page/view
- **Problem**: Screens get built but are unreachable for the user — it is possible to reach them only by entering a direct path
- **Rule**: When a plan adds a new screen, it must specify the navigation path to it (entry point, link/route, and where it's surfaced in existing UI)
- **Applies to**: plan, plan-review, implement, impl-review

## Verify file listings live before correctness-critical decisions

- **Context**: Any time Glob/file-index results feed a correctness-critical decision.
- **Problem**: The Glob/file-index can be stale (miss recently committed files); planning a duplicate Flyway version (e.g. two `V3__`) makes Flyway fail on boot.
- **Rule**: Before choosing the next sequential migration version (or relying on a file listing for a correctness decision), confirm the live filesystem with `ls`/`git ls-files` — never trust Glob/index alone.
- **Applies to**: all
