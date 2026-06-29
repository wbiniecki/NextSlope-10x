<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Three-Resort Recommendation (S-05)

- **Plan**: context/changes/three-resort-recommendation/plan.md
- **Mode**: Deep
- **Date**: 2026-06-28
- **Verdict**: REVISE → SOUND (all 6 findings fixed in plan, 2026-06-28)
- **Findings**: 1 critical, 3 warnings, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | WARNING |
| Plan Completeness | WARNING |

## Grounding

Grounding: 5/5 paths ✓, 3/3 symbols ✓, brief↔plan ✓

## Findings

### F1 — Progress Phase 2 title mismatch

- **Severity**: ❌ CRITICAL
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: ## Progress ↔ ## Phase 2
- **Detail**: Body heading is `## Phase 2: Recommendation engine (filters + pluggable scorer + truthful rationale)` but Progress has `### Phase 2: Recommendation engine`. The Progress↔Phase mechanical contract requires matching titles; `/10x-implement` parses Progress by phase name.
- **Fix**: Rename the Progress subsection to the full Phase 2 title (or shorten the body heading to match Progress — either way, make them identical).
- **Decision**: FIXED — renamed Progress subsection to full Phase 2 title (plan.md:341).

### F2 — Resync upsert contract omits repository seam + active preservation

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Plan Completeness
- **Location**: Phase 1 — Opt-in resync mode; Critical Implementation Details
- **Detail**: Critical Implementation Details promise resync won't clobber admin `active` edits, but Phase 1's loader contract only says "upsert by external_id." Code today has no `findByExternalId` on `ResortRepository`, and `toResort()` always sets `.active(true)` — a naïve upsert would reset deactivated rows on every resync.
- **Fix A ⭐ Recommended**: Extend Phase 1 contract with two explicit sub-bullets: add `Optional<Resort> findByExternalId(Long)` to `ResortRepository`; on resync update, copy CSV fact fields onto the existing row and preserve `existing.getActive()`. Add a resync-mode test that sets `active=false` on a seeded row, reruns resync, and asserts `active` stays false.
  - Strength: Matches the plan's stated intent and is testable now (pattern already used in `ResortRepositoryTests`).
  - Tradeoff: Slightly more loader logic than blind `save(toResort())`.
  - Confidence: HIGH — verified in code at `ResortSeedLoader.java:103-104` and `ResortRepository.java:8-13`.
  - Blind spot: None significant.
- **Fix B**: Document that resync overwrites all CSV-mapped columns including `active`, and drop the "honors active flag" claim
  - Strength: Simpler loader — reuse `toResort()` verbatim.
  - Tradeoff: Clashes with "What We're NOT Doing" (admin edits) once S-06 lands; contradicts Critical Details line 54.
  - Confidence: LOW — misaligns with plan intent.
  - Blind spot: S-06 admin behavior not built yet.
- **Decision**: FIXED via Fix A — added `findByExternalId` repo seam + active-preservation rule to Phase 1 §2 contract and an active-preservation test to §3.

### F3 — Privacy test wording implies 403 IDOR; codebase uses isolation

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 3 — Success Criteria; Testing Strategy
- **Detail**: Phase 3 says "admin / other user cannot obtain user A's recommendations." `/recommend` is principal-scoped (no user id in path), same as profile and visited. Existing privacy tests (`PreferenceProfileOwnershipIntegrationTests`, `VisitedResortOwnershipIntegrationTests`) assert isolation — user B's POST reflects B's profile, not A's — not HTTP 403. `AccessControlAssertions.assertWrongOwnerDenied` is explicitly a placeholder for a different route shape.
- **Fix**: Reword Phase 3 automated criterion and Testing Strategy to mirror the isolation pattern: seed distinct profiles for A and B; as B (and admin), POST `/recommend` and assert the response reflects that principal's profile (names/scores), never A's. Reference `PreferenceProfileOwnershipIntegrationTests` as the template. Drop "cannot obtain" / forbidden semantics.
- **Decision**: FIXED — reworded Phase 3 success criterion + Testing Strategy to principal-isolation (not 403/IDOR), referenced the existing ownership tests, and aligned Progress 3.3 title.

### F4 — PermitListLockTests needs new mocks once RecommendController exists

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 3 — Success Criteria (3.2)
- **Detail**: Phase 3 requires `PermitListLockTests` stay green after adding `RecommendController`, but doesn't note that `@WebMvcTest` will load the new controller and need `@MockitoBean` stubs for `RecommendationService` (and any other injected deps), same as existing mocks for `VisitedResortService`, etc.
- **Fix**: Add a Phase 3 bullet under RecommendController or success criterion 3.2: extend `PermitListLockTests` with `@MockitoBean RecommendationService` (minimal stub) so the gated-route lock keeps compiling once the controller lands.
- **Decision**: FIXED — annotated success criterion 3.2 with the `@MockitoBean RecommendationService` requirement for the `@WebMvcTest` context.

### F5 — Curated CSV acquisition not spelled out in Phase 1

- **Severity**: 💡 OBSERVATION
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 1 — Curated expanded seed CSV; Prerequisites
- **Detail**: Prerequisites list "External source for the expanded curated European resort facts" but Phase 1 doesn't describe how rows are chosen, validated against the ≥3-per-country / difficulty-band guarantees, or how country spellings stay aligned with the profile selector (`"Czech Republic"`, not `"Czechia"`). Phase 1 blocks on manual human confirmation — implementer may stall without a curation checklist.
- **Fix A ⭐ Recommended**: Add a Phase 1 sub-bullet with a curation checklist: start from existing CSV schema; add rows until every active country in `availableCountries()` has ≥3 resorts; verify at least one resort per difficulty band (`MOSTLY_EASY`, `BALANCED`, `MOSTLY_HARD`) via `getDifficultyMix()` characterization; preserve exact country strings; record final count as the new test constant.
  - Strength: Makes the distribution test and manual gate actionable without prescribing a data vendor.
  - Tradeoff: Manual curation effort still falls on the implementer.
  - Confidence: HIGH — aligns with plan-brief risk on line 58.
  - Blind spot: Whether existing public datasets cover all 14 current countries at ≥3 each.
- **Fix B**: Defer expansion to a separate data-ingest change
  - Strength: Smaller Phase 1 diff if a dataset is pre-built offline.
  - Tradeoff: Engine tested against 40-row scarcity until ingest lands.
  - Confidence: MED — contradicts "data first" build order.
  - Blind spot: Timeline for external dataset delivery.
- **Decision**: FIXED via Fix A — added a curation checklist (≥3-per-country, per-band coverage, exact country strings, record new count) to Phase 1 §1.

### F6 — Phase 4 PIT wiring should cite test-plan smoke-verify caveats

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 4 — PIT mutation gate
- **Detail**: `test-plan.md` §4/§6.5 documents pinned versions (`info.solidsoft.pitest` 1.19.0 + `junit5PluginVersion` 1.2.3) and two verify-at-wiring caveats (Gradle 9.4.1 config-cache smoke run; JUnit Platform version vs pitest-junit5-plugin). Phase 4 says "add gradle-pitest plugin" generically without referencing those grounded defaults — implementer may pick wrong versions or miss the smoke step.
- **Fix**: Cross-reference `test-plan.md` §4 Stack row and §6.5 in Phase 4 contract; add success criterion "smoke-verify `./gradlew pitest` locally on Gradle 9.4.1 before CI wiring"; note threshold is calibrated empirically (no numeric % preset).
- **Decision**: FIXED — cited test-plan §4 pinned versions + §6.5 caveats in Phase 4 contract, added the smoke-verify criterion (4.1) and renumbered Progress.
