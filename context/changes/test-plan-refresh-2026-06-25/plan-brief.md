# Test-Plan Refresh: Scoped PIT Mutation Gate — Plan Brief

> Full plan: `context/changes/test-plan-refresh-2026-06-25/plan.md`
> Research: `context/changes/test-plan-refresh-2026-06-25/research.md`

## What & Why

Amend `context/foundation/test-plan.md` to fold a **recommender-scoped** PIT mutation-testing gate
into the Phase 2 (recommender correctness) spec. Mutation testing earns its keep exactly on the
recommender's branchy scorer/filter/rationale logic — it proves the correctness tests would actually
*fail* if the ranking broke, guarding the product's reason to exist (truthful, complete matching).

## Starting Point

The test plan is a frozen-strategy guide; Phase 2 is `not started` and gated on S-05
(`three-resort-recommendation`, still `proposed`). No mutation testing is mentioned anywhere, and no
PIT config exists in the repo. CI today is just `./gradlew test --no-daemon` on push/PR to `main`.

## Desired End State

Six sections of `test-plan.md` (§3, §4, §5, §6.5, §7, §8) document the scoped PIT gate so a future
implementer of S-05's test phase knows the plugin/versions, the recommender-only scope, the
qualitative mutation-score target, and the oracle-problem guard. **No build/CI is wired** — that's
deferred to the S-05 implement plan.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Plugin + versions | `info.solidsoft.pitest` 1.19.0 + `junit5PluginVersion 1.2.3` | Both compatible with Java 21 / JUnit 5 / Spring Boot 4 BOM | Research |
| Gradle version note | Record real **Gradle 9.4.1** + "initial Gradle-9 support, smoke-verify at wiring" | change.md's "8.4+" is only the plugin minimum; wrapper is 9.4.1 | Research |
| Scope | `com.nextslope.recommendation.*` (filter/scorer/rationale), exclude user/web/config/support | Recommender-only = cost × signal; final names set at S-05 | Research |
| Mutation-score threshold | Qualitative now, exact % at S-05 implement | Can't calibrate a number against code that doesn't exist | Plan |
| CI cadence + 9.4.1 smoke-verify | Defer to the S-05 implement plan | Keeps this a pure plan-amendment; decisions belong where code lands | Plan |
| Wiring scope | Docs only — no `build.gradle`/CI/`pitest{}` changes | change.md "plan-amendment ONLY"; S-05 unbuilt | Frame/change.md |

## Scope

**In scope:** Six additive edits to `context/foundation/test-plan.md` (§3 Phase 2 goal/test-types,
§4 stack row + grounding note, §5 quality-gate row, §6.5 cookbook + oracle guard, §7 deliberate-scope
exclusion, §8 freshness stamp).

**Out of scope:** `build.gradle` plugin/`pitest{}` wiring, CI changes, any recommender code/tests, a
concrete numeric threshold, repo-wide mutation testing, rewriting §1/§2 beyond required citations.

## Architecture / Approach

One coherent editing pass over a single prose file. Each edit is localized to its section, preserves
existing table shapes and status vocabulary, and consistently frames PIT wiring as deferred-until-S-05.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Amend test-plan.md | All six PIT-gate edits applied to the guide | Over-reaching into build/CI wiring (guard: §7 + automated `git status` check on build.gradle/ci.yml) |

**Prerequisites:** None — research complete; change.md fully itemized.
**Estimated effort:** ~1 short session, single file.

## Open Risks & Assumptions

- Recommender package names are assumed `com.nextslope.recommendation.*`; finalized at S-05 (spec
  phrases scope as a pattern, so a different final name doesn't invalidate the guide).
- Gradle 9.4.1 is above the plugin's release smoke-tested 9.0; recorded as a verify-at-wiring caveat,
  not assumed working.
- Spring Boot 4's JUnit Platform is newer than the plugin's documented 1.10 ceiling ("probably above");
  recorded as a second verify-at-wiring caveat.

## Success Criteria (Summary)

- `test-plan.md` documents the scoped PIT gate across §3/§4/§5/§6.5/§7/§8, with Gradle 9.4.1 + both caveats.
- The guide never implies PIT runs today; wiring is consistently deferred to S-05.
- `build.gradle` and `.github/workflows/ci.yml` are untouched.
