---
date: 2026-06-25T11:12:35+0200
researcher: binieckw
git_commit: 0f67104212c6fd86667fa41390909c4ea0eb75ba
branch: main
repository: wbiniecki/NextSlope-10x
topic: "Fold a scoped PIT mutation-testing gate into the test plan's Phase 2 (recommender correctness) spec"
tags: [research, codebase, test-plan, pitest, mutation-testing, recommender, gradle]
status: complete
last_updated: 2026-06-25
last_updated_by: binieckw
---

# Research: Scoped PIT mutation-testing gate for test-plan Phase 2 (recommender)

**Date**: 2026-06-25T11:12:35+0200
**Researcher**: binieckw
**Git Commit**: 0f67104212c6fd86667fa41390909c4ea0eb75ba
**Branch**: main
**Repository**: wbiniecki/NextSlope-10x

## Research Question

Ground the `test-plan-refresh-2026-06-25` change: a **plan-amendment only** refresh of
`context/foundation/test-plan.md` that folds a **scoped** PIT mutation-testing gate into the
Phase 2 (recommender correctness) spec — confined to the future recommender
scorer/filter/rationale packages, never repo-wide. Specifically validate:

1. Is `info.solidsoft.pitest` 1.19.0 + `junit5PluginVersion` (pitest-junit5-plugin) actually
   compatible with this stack — **Gradle 9.4.1**, Java 21, JUnit 5, Spring Boot 4.0.6?
2. Where will the recommender code live, so the gate can scope `targetClasses`/`targetTests`
   to recommender packages only?
3. Does any PIT config exist today, and where would a scoped gate slot into CI?

## Summary

- **The amendment is sound, with one correction and two caveats to record.**
- **Correction:** `change.md` says "Gradle 8.4+ confirmed". The repo wrapper is **Gradle
  9.4.1** ([`gradle/wrapper/gradle-wrapper.properties`](https://github.com/wbiniecki/NextSlope-10x/blob/0f67104212c6fd86667fa41390909c4ea0eb75ba/gradle/wrapper/gradle-wrapper.properties)),
  not 8.4. 8.4 is the plugin's *minimum*; the stack is well above it. The plugin's
  Gradle-9 support is real but described as "initial" and was smoke-tested against
  Gradle 9.0(-m9) at the 1.19.0 release — **9.4.1 is newer than that tested ceiling**, so
  the gate must be smoke-run once at wiring time (Phase 2), not assumed.
- **Versions that fit:** `info.solidsoft.pitest` **1.19.0** (Java 17+, Gradle 8.4+, initial
  Gradle 9 support, default PIT engine ≈ 1.20.x) + `junit5PluginVersion = '1.2.3'`
  (pitest-junit5-plugin 1.2.3, requires pitest 1.19.4+). Both are compatible with Java 21.
- **JUnit Platform caveat:** pitest-junit5-plugin 1.2.x auto-resolves the platform launcher
  and is documented to work with JUnit Platform 1.5.0–1.10.0-M1 "and probably above". Spring
  Boot 4.0.6 ships a **newer** JUnit Platform/Jupiter (≈ 5.13/Platform 1.13), which falls in
  the "probably above" band — verify at wiring. The project already declares
  `testRuntimeOnly 'org.junit.platform:junit-platform-launcher'`
  ([`build.gradle:46`](https://github.com/wbiniecki/NextSlope-10x/blob/0f67104212c6fd86667fa41390909c4ea0eb75ba/build.gradle#L46)),
  which is exactly what the plugin's "provided-scope launcher" model expects.
- **No PIT config exists** anywhere (build, gradle, CI) — this is a greenfield add deferred
  until S-05 (`three-resort-recommendation`) ships. CI runs `./gradlew test --no-daemon` on
  push/PR to `main`.
- **Scope target:** recommender code will follow the `com.nextslope.<domain>` convention →
  best fit **`com.nextslope.recommendation.*`** (change-id `three-resort-recommendation`),
  ideally narrowed to `.filter` / `.scorer` / `.rationale` once the slice lands. PIT must
  **exclude** `user`, `config`, `web`, the root app, and `support` test scaffolding.

## Detailed Findings

### Area 1 — PIT plugin ↔ stack compatibility

- **gradle-pitest-plugin 1.19.0** (`info.solidsoft.pitest`, released 2026-03-29) compatibility
  contract: **Java 17+ minimum, Gradle 8.4+ minimum**, "initial support for Gradle 9 (no
  warnings with 9.0-m9)", build/run with up to JDK 24. It is a refreshed 1.15.0 for modern
  Gradle; default PIT engine bumped to ~1.20.3 in the rc.2/final line. Source:
  [release notes](https://github.com/szpak/gradle-pitest-plugin/releases/tag/release%2F1.19.0),
  [CHANGELOG](https://github.com/szpak/gradle-pitest-plugin/blob/master/CHANGELOG.md).
- **Our stack:** Java 21 (`build.gradle` toolchain line 11–13), Gradle **9.4.1** (wrapper),
  Spring Boot 4.0.6 (`build.gradle:3`). Java 21 ≥ 17 ✓. Gradle 9.4.1 ≥ 8.4 ✓ but **above the
  9.0 version the plugin was smoke-tested against at release** → low-risk, verify-once caveat.
- **JUnit 5 wiring:** the `pitest { junit5PluginVersion = '…' }` closure makes the gradle
  plugin add `org.pitest:pitest-junit5-plugin` and set `testPlugin = "junit5"`. Latest is
  **1.2.3** (2025-05-20, requires pitest 1.19.4+); 1.2.2 also valid. From the
  [plugin README](https://github.com/pitest/pitest-junit5-plugin/blob/master/README.md):
  used with the gradle plugin it "automatically work[s] with JUnit platform 1.5.0 to
  1.10.0-M1 (and probably above)". Spring Boot 4's JUnit Platform is newer than 1.10 → falls
  in "probably above"; confirm at wiring.
- The plugin sets `junit-platform-launcher` to *provided* scope to avoid a runtime version
  clash; our explicit `testRuntimeOnly` launcher dep (`build.gradle:46`) satisfies that.

### Area 2 — Where the recommender will live (scoping target)

- **Package convention** is "one package per domain (`com.nextslope.<domain>`)", stamped by
  the persistence baseline and AGENTS.md
  ([`AGENTS.md` Entity convention](https://github.com/wbiniecki/NextSlope-10x/blob/0f67104212c6fd86667fa41390909c4ea0eb75ba/AGENTS.md)).
  Existing domains: `com.nextslope.user`, `com.nextslope.config`, `com.nextslope.web`, root app.
- S-05's change-id is **`three-resort-recommendation`**
  ([`roadmap.md:131-136`](https://github.com/wbiniecki/NextSlope-10x/blob/0f67104212c6fd86667fa41390909c4ea0eb75ba/context/foundation/roadmap.md#L131-L136))
  → most-likely domain package **`com.nextslope.recommendation`** (alternate
  `com.nextslope.recommend`, aligned with the `/recommend` route asserted in
  [`PermitListLockTests`](https://github.com/wbiniecki/NextSlope-10x/blob/0f67104212c6fd86667fa41390909c4ea0eb75ba/src/test/java/com/nextslope/PermitListLockTests.java#L61-L62)).
- The PRD's **two-stage matching** (hard filters → weighted score) and the test-plan Risk
  Response Guidance name the three logical layers PIT should bite: **filter, scorer,
  rationale** (test-plan §2 rows #1–#3). These map to plausible subpackages
  `…recommendation.filter` / `.scorer` / `.rationale`.
- **No recommender Java code exists yet** — exact class/orchestrator names (e.g.
  `RecommendationService`) are TBD. The amendment must therefore phrase the scope as a
  *pattern to be finalized when S-05 lands*, not a hard package list.

### Area 3 — Existing test infra + CI slot

- **No PIT/mutation config anywhere.** `build.gradle` only has `useJUnitPlatform()`
  ([`build.gradle:50-52`](https://github.com/wbiniecki/NextSlope-10x/blob/0f67104212c6fd86667fa41390909c4ea0eb75ba/build.gradle#L50-L52)).
- **CI** = single `Test` job, JDK 21 Temurin, `./gradlew test --no-daemon`, on push/PR to
  `main`, including the Testcontainers Postgres migration check
  ([`.github/workflows/ci.yml`](https://github.com/wbiniecki/NextSlope-10x/blob/0f67104212c6fd86667fa41390909c4ea0eb75ba/.github/workflows/ci.yml)).
  A scoped `./gradlew pitest` would slot in as a sibling step/job **after** `test`, gated to
  run only once recommender packages exist (test-plan §5 already reserves a post-Phase-2
  "recommender correctness suite" gate row to sit beside).
- **Reference unit-test patterns** the recommender logic tests (PIT's `targetTests`) will
  follow: pure validation via plain JUnit 5 + AssertJ
  ([`RegistrationFormValidationTests`](https://github.com/wbiniecki/NextSlope-10x/blob/0f67104212c6fd86667fa41390909c4ea0eb75ba/src/test/java/com/nextslope/user/RegistrationFormValidationTests.java)),
  service logic via a light slice
  ([`UserRegistrationServiceTests`](https://github.com/wbiniecki/NextSlope-10x/blob/0f67104212c6fd86667fa41390909c4ea0eb75ba/src/test/java/com/nextslope/user/UserRegistrationServiceTests.java)).
  For PIT cost control, recommender unit tests should be plain JUnit + AssertJ (no full
  Spring context) so mutants are killed fast.
- **Oracle-problem guard** (the reason mutation testing earns its keep here): test-plan Risk
  Response Guidance #1/#2 already mandate that expected scoring/rationale values derive from
  the *user's input*, never from the generator's own output. The §6.5 cookbook note must
  carry this forward — PIT kills mutants only if the assertions encode an independent oracle,
  not a tautology.

## Code References

- `gradle/wrapper/gradle-wrapper.properties` — `distributionUrl … gradle-9.4.1-bin.zip` (the 9.4.1 fact correcting `change.md`'s "8.4+")
- `build.gradle:3` — `org.springframework.boot` version `4.0.6`
- `build.gradle:10-13` — Java 21 toolchain
- `build.gradle:46` — `testRuntimeOnly 'org.junit.platform:junit-platform-launcher'` (satisfies pitest-junit5-plugin provided-scope launcher model)
- `build.gradle:50-52` — `tasks.named('test') { useJUnitPlatform() }`; no pitest config
- `.github/workflows/ci.yml` — `./gradlew test --no-daemon`, JDK 21, push/PR to `main`
- `context/foundation/test-plan.md:84-98` — §3 Phase 2 row (recommender correctness, gated on S-05)
- `context/foundation/test-plan.md:71-73` — Risk Response Guidance #1–#3 (filter/scorer/rationale layers + oracle guard)
- `context/foundation/test-plan.md:105-117` — §4 Stack table (PIT row insertion point)
- `context/foundation/test-plan.md:125-131` — §5 Quality Gates (mutation-score gate insertion point)
- `context/foundation/test-plan.md:192-194` — §6.5 cookbook placeholder for recommender tests
- `context/foundation/roadmap.md:131-142` — S-05 `three-resort-recommendation` (status `proposed`)
- `src/test/java/com/nextslope/PermitListLockTests.java:61-62` — `/recommend` route hint

## Architecture Insights

- **Scope = cost × signal.** The whole point of confining PIT to the recommender is the
  test-plan's principle #1 (`test-plan.md:15-19`): mutation testing is expensive and only the
  recommender's branchy scoring/filter/rationale logic has the density of behavior where a
  surviving mutant is a real signal. Auth/web/config are guarded by cheaper slice tests; a
  repo-wide PIT run would burn time for near-zero marginal signal. This is exactly why §7
  should record the scope decision as a deliberate exclusion.
- **Defer wiring, amend spec now.** S-05 is `proposed` and unbuilt, so the package list cannot
  be finalized. The amendment encodes *intent + version contract + scoping rule*; the actual
  `pitest {}` block and `mutationThreshold` land inside the Phase 2 `/10x-implement` run, where
  the real package names are known and the version compat can be smoke-verified against Gradle
  9.4.1.
- **Threshold is package-scoped, not repo-wide.** The gate should set a mutation-score
  threshold over the recommender target packages only (e.g. `mutationThreshold` on a `pitest`
  config whose `targetClasses` = `com.nextslope.recommendation.*`), failing the build below it.

## Historical Context (from prior changes)

- `context/archive/2026-06-16-persistence-migration-baseline/plan.md` — established the
  `com.nextslope.<domain>` package convention every later slice (incl. the recommender) follows.
- `context/changes/testing-access-control-privacy-net/` — test-plan Phase 1 (the shipped
  security/IDOR cookbook in §6.4); the model for how a phase's tests extend shared scaffolding
  rather than re-derive. Phase 2's PIT note should mirror that "extend, don't re-derive" tone.

## Related Research

- None prior under `context/changes/**/research.md` for this topic; this is the first research
  artifact for `test-plan-refresh-2026-06-25`.

## Open Questions

1. **Final recommender package name** — `com.nextslope.recommendation` (favored) vs
   `com.nextslope.recommend`. Resolve when S-05's plan is written; the amendment should state
   the scope as "the recommender domain package + its scorer/filter/rationale subpackages
   (final names per S-05)".
2. **Gradle 9.4.1 smoke result** — plugin 1.19.0 only documents Gradle 9.0 testing. Must run
   `./gradlew pitest` once during Phase 2 wiring to confirm no config-cache / task-graph
   regression on 9.4.1 (Gradle 9 enables config cache more aggressively; rc.2 fixed
   `pitestReportAggregate` config-cache compat but the single-module `pitest` task path should
   be verified).
3. **JUnit Platform "probably above"** — confirm pitest-junit5-plugin 1.2.3 runs against the
   exact JUnit Platform shipped by the Spring Boot 4.0.6 BOM at wiring time; if not, pin
   `pitestVersion` / `junit5PluginVersion` to a matched pair.
4. **CI gating trigger** — should the scoped PIT gate run on every PR (slower) or only when
   recommender files change / on a schedule? Decide in the Phase 2 plan to keep PR latency sane.
