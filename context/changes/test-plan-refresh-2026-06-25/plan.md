# Test-Plan Refresh: Scoped PIT Mutation Gate (Phase 2) Implementation Plan

## Overview

Amend `context/foundation/test-plan.md` to fold a **recommender-scoped** PIT mutation-testing
gate into the Phase 2 (recommender correctness) specification. This is a **documentation-only
plan amendment** — it updates the frozen test-plan guide so that *when* the S-05
(`three-resort-recommendation`) slice is implemented, its test phase includes mutation testing
confined to the recommender scoring/filter/rationale packages. **No `build.gradle`, no CI, no
`pitest {}` block is wired in this change** — S-05 is still `proposed` and unbuilt.

## Current State Analysis

- `context/foundation/test-plan.md` is a frozen-strategy guide (§1–§5) plus a cookbook (§6) that
  fills in as phases ship. Phase 2 (recommender correctness) is `not started` and gated on S-05.
- The test plan has **no mention of mutation testing** anywhere today.
- No PIT configuration exists in the repo (`build.gradle` has only `useJUnitPlatform()` at
  `build.gradle:50-52`); CI runs `./gradlew test --no-daemon` on push/PR to `main`
  (`.github/workflows/ci.yml`).
- The change is fully specified in `context/changes/test-plan-refresh-2026-06-25/change.md` and
  validated by `context/changes/test-plan-refresh-2026-06-25/research.md`.

## Desired End State

`context/foundation/test-plan.md` documents the scoped PIT gate across six sections (§3, §4, §5,
§6.5, §7, §8) such that a future reader implementing S-05's test phase knows: which plugin/version
to apply, that it must be scoped to recommender packages only, the qualitative mutation-score
target, the oracle-problem guard, and that the gate is deliberately recommender-only. Verify by
reading the six amended sections; no behavioral/test change accompanies this (the guide is prose).

### Key Discoveries:

- **Gradle version correction:** wrapper is **Gradle 9.4.1** (`gradle/wrapper/gradle-wrapper.properties`),
  not the "8.4+" the `change.md` note assumed. 8.4 is the plugin *minimum*; the stack is well above
  it. The plugin's Gradle-9 support is labeled "initial" (smoke-tested vs 9.0 at release) → record
  a verify-at-wiring caveat (research Open Question #2).
- **Version contract:** `info.solidsoft.pitest` **1.19.0** (Java 17+, Gradle 8.4+) +
  `junit5PluginVersion = '1.2.3'` (pitest-junit5-plugin, requires pitest 1.19.4+); both compatible
  with Java 21. JUnit-Platform support documented as "1.5.0–1.10.0-M1 (and probably above)"; Spring
  Boot 4.0.6 ships a newer platform → second verify-at-wiring caveat (research Open Question #3).
- **Scope target:** recommender follows `com.nextslope.<domain>` → `com.nextslope.recommendation.*`
  (`.filter`/`.scorer`/`.rationale`), excluding `user`/`config`/`web`/`support`. Phrase as a pattern
  finalized at S-05 implement time (final names TBD — research Open Question #1).
- **Oracle guard:** test-plan §2 Risk Response Guidance #1/#2 already mandate expected
  scoring/rationale values derive from the user's input, never the generator — §6.5 must carry this
  forward, since PIT only earns signal if assertions encode an independent oracle.
- **Decisions settled this session:** mutation-score threshold expressed **qualitatively** now
  (exact % at S-05 implement); CI cadence + Gradle 9.4.1 smoke-verify **deferred** to the S-05 plan.

## What We're NOT Doing

- NOT adding the `info.solidsoft.pitest` plugin to `build.gradle`.
- NOT adding a `pitest {}` configuration block or any `pitestVersion`/`junit5PluginVersion` wiring.
- NOT modifying `.github/workflows/ci.yml` or adding a PIT CI step/job.
- NOT writing any recommender code or recommender tests (S-05 owns that).
- NOT setting a concrete numeric mutation-score threshold (decided: qualitative until S-05).
- NOT rewriting test-plan §1 or §2 beyond the citations the change requires.
- NOT applying mutation testing repo-wide — the entire point is recommender-only scope.

## Implementation Approach

One coherent editing pass over `context/foundation/test-plan.md`, touching six sections. Each edit
is additive and localized to its section, preserving the existing table shapes, status vocabulary,
and prose tone. The amendment frames PIT wiring as deferred-until-S-05 throughout, honoring the
`change.md` "plan-amendment ONLY" guardrail.

## Phase 1: Amend test-plan.md with the scoped PIT gate

### Overview

Apply all six itemized edits to `context/foundation/test-plan.md` in a single pass.

### Changes Required:

#### 1. §4 Stack — add the PIT row

**File**: `context/foundation/test-plan.md`

**Intent**: Add a mutation-testing row to the §4 Stack table and a grounding note so a future
implementer knows the exact plugin/versions and the Gradle-9 caveat. Correct the `change.md`'s
stale "8.4+" to the real Gradle 9.4.1.

**Contract**: New table row under the existing §4 table — Layer "mutation testing (recommender
only)", Tool `info.solidsoft.pitest` Gradle plugin + `pitest-junit5-plugin`, Version
`plugin 1.19.0 / junit5PluginVersion 1.2.3`, Notes naming Java 21 ✓ / Gradle 9.4.1 (≥ plugin min
8.4; plugin's Gradle-9 support is "initial" → smoke-verify at S-05 wiring) and the
"JUnit-Platform support documented to 1.10, Spring Boot 4 ships newer → verify at wiring" caveat.
Add a matching `checked: 2026-06-25` to the "Stack grounding tools" bullet block.

#### 2. §3 Phase 2 — extend goal & test types

**File**: `context/foundation/test-plan.md`

**Intent**: Extend the Phase 2 row so its goal and test-types include the scoped PIT run alongside
the existing unit+integration coverage.

**Contract**: Edit the Phase 2 row (currently `Recommender correctness suite … unit + integration`)
— append to the goal "…plus a recommender-scoped mutation-testing gate" and to Test types
"+ mutation (PIT, recommender packages only)". Status vocabulary unchanged.

#### 3. §5 Quality Gates — add the mutation-score gate row

**File**: `context/foundation/test-plan.md`

**Intent**: Add a quality-gate row for the package-scoped mutation-score gate, required after
Phase 2, with a qualitative (not numeric) threshold per this session's decision.

**Contract**: New §5 table row — Gate "recommender mutation-score gate (PIT, scoped to recommender
packages)", Where "local + CI", Required? "required after §3 Phase 2", Catches "surviving mutants
in scorer/filter/rationale logic (weak/tautological assertions)". Threshold described as "a high
package-scoped mutation score, exact % calibrated when S-05 lands — never repo-wide".

#### 4. §6.5 Cookbook — replace the TBD with the PIT note + oracle guard

**File**: `context/foundation/test-plan.md`

**Intent**: Flesh out §6.5 (currently "TBD — see §3 Phase 2") with the PIT gate guidance: kill
mutants in scorer/filter/rationale, scope `targetClasses`/`targetTests` to recommender packages,
and carry forward the oracle-problem guard from §2 Risk Response Guidance #1/#2.

**Contract**: §6.5 body gains: the all-axes differential pattern + visited/new-only hard filter +
rationale-truthfulness oracle (already drafted as TBD), plus a "Mutation gate" sub-note —
`info.solidsoft.pitest` scoped via `targetClasses = com.nextslope.recommendation.*`
(`.filter`/`.scorer`/`.rationale`) and `targetTests` mirror, excluding `user`/`config`/`web`/
`support`; expected values derive from user input, never the generator (oracle guard). Note the
gate + threshold are wired when S-05 ships (final package names + exact % + CI cadence +
Gradle 9.4.1 smoke check resolved in the S-05 plan).

#### 5. §7 What We Deliberately Don't Test — record the scope decision

**File**: `context/foundation/test-plan.md`

**Intent**: Record that mutation testing is deliberately confined to the recommender (cost × signal),
not applied repo-wide, so a future contributor doesn't "helpfully" expand it.

**Contract**: New §7 bullet — "Repo-wide mutation testing — PIT is deliberately scoped to the
recommender (scorer/filter/rationale) only; the auth/web/config surfaces are guarded by cheaper
slice tests where a surviving mutant is near-zero signal. (Source: cost × signal, §1 principle #1;
test-plan-refresh-2026-06-25.)"

#### 6. §8 Freshness Ledger — stamp the refresh

**File**: `context/foundation/test-plan.md`

**Intent**: Record this refresh in the freshness ledger and update the header "Last updated" line.

**Contract**: Update the top-of-file "Last updated:" note to `2026-06-25 (PIT mutation gate folded
into Phase 2)`; add to §8 a line "PIT/mutation tooling grounded: 2026-06-25" (and keep the existing
2026-06-22 review lines intact).

### Success Criteria:

#### Automated Verification:

- The string `info.solidsoft.pitest` appears in `context/foundation/test-plan.md`: `rg -n "info.solidsoft.pitest" context/foundation/test-plan.md`
- The §6.5 TBD placeholder is gone: `rg -n "TBD — see §3 Phase 2" context/foundation/test-plan.md` returns zero hits (§6.6 references Phase 3, not Phase 2)
- The freshness stamp updated: `rg -n "2026-06-25" context/foundation/test-plan.md` matches header + §8
- The repo-wide-exclusion bullet exists in §7: `rg -n "Repo-wide mutation testing" context/foundation/test-plan.md`
- No build/CI files changed: `git status --porcelain build.gradle .github/workflows/ci.yml` is empty

#### Manual Verification:

- All six sections (§3, §4, §5, §6.5, §7, §8) read coherently and preserve existing table shapes and the status vocabulary.
- §4 row says Gradle 9.4.1 (not 8.4) and carries both verify-at-wiring caveats.
- The amendment consistently frames PIT wiring as deferred-until-S-05; nowhere implies PIT runs today.
- §6.5 carries the oracle guard (expected values from user input, never the generator).

**Implementation Note**: After the edit and automated checks pass, pause for human confirmation
that the six sections read correctly before considering the change complete.

---

## Testing Strategy

This is a documentation amendment; there is no executable test surface. "Testing" is the automated
`rg`/`git status` checks above plus a human read-through of the six sections.

## Migration Notes

None — additive prose edits to one guide file. No applied artifacts, schema, or config change.

## References

- Related research: `context/changes/test-plan-refresh-2026-06-25/research.md`
- Change spec: `context/changes/test-plan-refresh-2026-06-25/change.md`
- Target file: `context/foundation/test-plan.md` (§3 lines 77–98, §4 105–117, §5 119–131, §6.5 192–194, §7 200–208, §8 210–221)
- gradle-pitest-plugin 1.19.0 release notes: https://github.com/szpak/gradle-pitest-plugin/releases/tag/release%2F1.19.0
- pitest-junit5-plugin README: https://github.com/pitest/pitest-junit5-plugin/blob/master/README.md

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Amend test-plan.md with the scoped PIT gate

#### Automated

- [x] 1.1 `info.solidsoft.pitest` appears in `context/foundation/test-plan.md`
- [x] 1.2 §6.5 TBD placeholder removed
- [x] 1.3 Freshness stamp `2026-06-25` matches header + §8
- [x] 1.4 §7 "Repo-wide mutation testing" exclusion bullet exists
- [x] 1.5 No build/CI files changed (`git status --porcelain build.gradle .github/workflows/ci.yml` empty)

#### Manual

- [x] 1.6 Six sections read coherently, preserve table shapes + status vocabulary
- [x] 1.7 §4 says Gradle 9.4.1 with both verify-at-wiring caveats
- [x] 1.8 Amendment consistently frames PIT wiring as deferred-until-S-05
- [x] 1.9 §6.5 carries the oracle guard
