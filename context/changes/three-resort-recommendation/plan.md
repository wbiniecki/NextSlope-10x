# Three-Resort Recommendation (S-05) Implementation Plan

## Overview

S-05 is the roadmap's north star: a signed-in user clicks "Recommend resorts" and sees exactly three ranked picks (key facts + a one-line truthful rationale) — or an explicit explanation when fewer than three viable matches exist — honoring hard filters (region, new-only) then weighted soft scoring, deterministically and privately.

This plan ships S-05 as **one data-first, phased slice**:

1. Expand the seed dataset to a curated larger European set so region-sparsity and the structurally-unsatisfiable difficulty bands stop being the common case, behind an opt-in resync property that refreshes already-populated environments.
2. Build the recommendation engine with a **pluggable scorer carrying a defensible default** — the exact rules (weights, experience↔mix mapping, alignment threshold) are deliberately **deferred to a separate refinement session**, so everything tunable lives in one place.
3. Wire the HTMX result flow and the missing navigation entry point.
4. Lock the engine with a mutation gate and hand the open rules off to the refinement session via a written brief.

## Current State Analysis

- **All scoring inputs already exist.** `PreferenceProfile` (`profile/PreferenceProfile.java:53-83`) supplies experience level, difficulty band (→ `getPreferredMix()` triple), novelty preference, and `regionCountries` (empty set = "any region"). `Resort.getDifficultyMix()` (`resort/Resort.java:121-162`) derives a largest-remainder easy/medium/hard triple from slope counts; `VisitedResortService.visitedResortIds(userId)` returns the unordered visited `Set<Long>`.
- **The recommendation package does not exist** and `/recommend` is unbuilt but **already locked as a gated route** in `PermitListLockTests.java:85`.
- **The HTMX in-place pattern is fully established** — `VisitedController.java:30-45` returns a thin wrapper template that `th:replace`s a named fragment; `resorts/list.html:53-69` shows the hidden-block named-fragment idiom; `fragments/layout.html:38-64` wires CSRF for HTMX (reused verbatim by any HTMX page).
- **No "Recommend resorts" entry point exists.** `index.html` still says "coming soon"; the lessons.md "navigation-to-every-new-screen" rule requires this plan to add it.
- **The seed loader is a single empty-table-guarded path** (`ResortSeedLoader.java:40-55`) keyed to `data/resorts-Europe-subset.csv`. It seeds only when the table is empty. `ResortSeedLoaderTests` hard-code "exactly 40".
- **Local dev uses file-backed H2** (`build.gradle:58-60` runs the `local` profile) — data survives restarts, so the empty-table guard skips on local dev after first boot, exactly like prod. Both need an explicit resync to pick up an expanded CSV.
- **No new schema migration is needed.** The recommender reads existing tables; dataset expansion is data-only (CSV + loader), not DDL. Next Flyway version would be V5 only if something new is persisted — nothing is.
- **`PreferenceProfileService` exposes only a form-level read** (`loadFormForUser`, `profile/PreferenceProfileService.java:29-34`). The recommender needs the raw profile axes, so a profile-snapshot read must be added.
- **PIT is not yet on the build** (`build.gradle` has no pitest plugin). The roadmap defers a mutation gate scoped to `com.nextslope.recommendation.*` to S-05; wiring it is a real tooling task.

## Desired End State

A signed-in user with a saved profile clicks "Recommend resorts" on `/resorts` and, within ~2s (progress shown), sees three ranked resort cards — each with key facts and a truthful one-line rationale naming a preference axis they actually set — or a single explicit explanation when the dataset can't supply three under their filters. Results are deterministic, private (no admin or other user can obtain them), and computed against the expanded curated dataset. All scoring tunables sit in one config object, guarded by a PIT mutation gate, with a refinement brief enumerating the open rules.

Verify: full `./gradlew test` green (including new recommendation unit tests, privacy/IDOR integration, and seed-resync tests); `./gradlew pitest` (or the wired task) passes the recommendation-package mutation threshold; manual click-through on `/resorts` yields three ranked, truthfully-rationalized cards and an explicit sparse message for a deliberately narrow filter.

### Key Discoveries:

- Hard filters then weighted score is settled by PRD Business Logic (`prd.md:154`) and prior research; the only invented parts are the scorer's numbers and the rationale truthfulness gate.
- Determinism is achievable by construction: sort on content-derived totals `(-score, country, name, id)`, never on `HashSet` iteration order (`research.md:148-152`).
- The candidate pool must be **active-only** (`findByActiveTrueOrderByCountryAscNameAsc`) while the visited set may reference inactive resorts (`research.md`, archive mark-visited plan).
- `regionCountries.isEmpty()` means "any region — no filter"; the region match is plain string equality against `resort.getCountry()` (same validated vocabulary).

## What We're NOT Doing

- **Not locking the final algorithm rules.** Weights, experience↔mix targets, and the alignment threshold ship as defensible defaults; the refinement session owns the final values (Phase 4 hands off).
- **Not adding search / filter / pagination to the browse list.** Even at ~100–150 resorts the flat list stays; browse-scaling remains parked (roadmap §Parked).
- **Not persisting recommendation history.** No new entity, no V5 migration. The result is computed on each request.
- **Not introducing the worldwide (~500) dataset or non-European resorts.** Expansion is European-only, curated.
- **Not changing the seed's default behavior.** The empty-table guard remains the default; resync is strictly opt-in so admin (S-06) edits are never clobbered unless explicitly requested.
- **Not building a dedicated `/recommend` full page.** Results render as an HTMX partial on `/resorts`.

## Implementation Approach

Data first: expand and verify the dataset so the engine is designed and tested against a realistic distribution rather than scarcity artifacts. Then build a domain engine in `com.nextslope.recommendation.*` that mirrors the established owner-scoped service conventions: a single entry method taking `userId`, hard filters → pluggable `Scorer` (Approach A default) → deterministic ordering → sparse branch, returning a result DTO. A separate rationale builder applies a threshold-gated dominant-axis rule for truthfulness. The web layer reuses the S-04 HTMX pattern verbatim. Finally, a PIT gate and a refinement brief close the slice and define the contained follow-up.

## Critical Implementation Details

- **Seed loader resync seam.** The loader currently takes only `ResortRepository` and is constructed directly in `ResortSeedLoaderTests`. Adding a resync property means the loader gains a configurable flag; the resync path must upsert by `external_id` (the existing `UNIQUE(external_id)` backstop) so it reconciles in place without violating the unique constraint and without touching the `active` flag of admin-edited rows beyond the seeded fact set. Default (flag off) must keep the exact current empty-table behavior so existing tests' intent holds (their hard-coded `40` becomes the new curated count).
- **Determinism.** The scorer and rationale builder must not iterate `HashSet`/`HashMap` or use `parallelStream()`; ranking sorts on `(-score, country, name, id)`. The visited `Set<Long>` and `regionCountries` are membership-tested only, never iterated for ordering.
- **Truthfulness gate.** A rationale clause for an axis is emitted only when (a) the user actually set that axis and (b) its alignment clears the configured threshold; the genuinely strongest qualifying axis is chosen. If none qualify, a truthful generic fallback is used (e.g. "best available match in your selected region"). This is what prevents an untruthful "matches your mostly-hard preference" line given the data reality.
- **No-profile edge.** A user with no saved profile cannot be scored; the flow must surface an explicit prompt to set up a profile rather than erroring or returning an empty/padded result.

---

## Phase 1: Expand the European dataset + opt-in resync seed

### Overview

Replace the 40-row subset with a curated larger European set (~100–150 resorts) chosen so every selectable country has ≥3 resorts and each difficulty band has genuine matches, and add an opt-in property that reconciles already-populated environments to the expanded CSV via upsert-by-`external_id`.

### Changes Required:

#### 1. Curated expanded seed CSV

**File**: `src/main/resources/data/resorts-Europe-subset.csv` (replace contents; keep the filename/loader path stable)

**Intent**: Broaden coverage to a curated larger European set so region hard-filtering and difficulty-band matching are no longer dominated by scarcity. Selection rule: every country offered as a region option carries ≥3 active resorts, and at least a handful of genuine `BALANCED` and `MOSTLY_HARD` matches exist.

**Contract**: Identical CSV header/column order to today (the loader binds by header name); all rows `Continent=Europe`; unique `ID` per row (becomes `external_id`); difficulty-relevant slope counts present so `getDifficultyMix()` yields realistic triples.

**Curation checklist** (so the manual gate is actionable without prescribing a data vendor):

- Start from the existing CSV schema/columns; only append/replace data rows.
- Add rows until **every country offered by `availableCountries()` carries ≥3 active resorts** (this is the distribution the expansion exists to guarantee).
- Verify at least one resort lands in each difficulty band (`MOSTLY_EASY`, `BALANCED`, `MOSTLY_HARD`) via `getDifficultyMix()` so the engine has genuine matches per band.
- Preserve the **exact** country strings used by the profile region selector (e.g. `"Czech Republic"`, not `"Czechia"`) — a spelling drift silently drops a country from the region filter.
- Record the final row count as the new shared test constant (replaces the hard-coded `40`).

#### 2. Opt-in resync mode on the seed loader

**File**: `src/main/java/com/nextslope/resort/ResortSeedLoader.java`

**Intent**: Add a configuration-driven resync path so operators can refresh local and prod to the expanded CSV without wiping data, while the default stays the safe empty-table guard. When resync is enabled, reconcile the table to the CSV by upserting each row keyed on `external_id`.

**Contract**: A boolean property (e.g. `nextslope.resort-seed.resync`, default `false`) injected into the loader. `false` → current empty-table behavior unchanged. `true` → for each CSV row, update the existing resort with matching `external_id` in place or insert if absent; never delete rows; idempotent across reruns; honors the `UNIQUE(external_id)` backstop.

  - **Repository seam**: add `Optional<Resort> findByExternalId(Long externalId)` to `ResortRepository` (the upsert needs to look up the existing row; no such finder exists today).
  - **Active preservation**: `toResort()` always sets `.active(true)`, so a naïve `save(toResort())` would silently re-activate admin-deactivated rows on every resync. On the update branch, copy only the CSV-sourced fact columns onto the existing entity and preserve `existing.getActive()` (insert branch keeps the CSV default of active). This is what makes the "never touches the `active` flag of admin-edited rows" promise in Critical Implementation Details real.

#### 3. Update seed tests for the new count + resync behavior

**File**: `src/test/java/com/nextslope/resort/ResortSeedLoaderTests.java`

**Intent**: Reflect the new curated count and prove both loader modes, including the distribution guarantees the expansion exists to provide.

**Contract**: Replace hard-coded `40` with the new curated count; default-mode empty-table + second-run-inserts-nothing tests retained; new resync-mode tests (resync updates a changed row in place, inserts a new row, is idempotent, leaves count correct); an active-preservation test (seed a row, set `active=false`, rerun resync, assert `active` stays `false` while fact columns update); a distribution assertion that every distinct country has ≥3 resorts.

### Success Criteria:

#### Automated Verification:

- Seed loader tests pass: `./gradlew test --tests com.nextslope.resort.ResortSeedLoaderTests`
- Postgres-engine seed test passes: `./gradlew test --tests com.nextslope.resort.ResortRepositoryPostgresTests`
- Full suite still green: `./gradlew test`

#### Manual Verification:

- Fresh local DB boots and seeds the expanded set; resort list shows the larger catalog.
- With the resync property enabled against an already-populated DB, the catalog updates to the expanded set without losing or duplicating rows.
- Every country shown in the profile region selector has ≥3 resorts.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Recommendation engine (filters + pluggable scorer + truthful rationale)

### Overview

Create `com.nextslope.recommendation.*`: an owner-scoped service that applies hard filters, scores survivors via a pluggable `Scorer` (Approach A default), orders deterministically, handles the sparse branch explicitly, and builds a threshold-gated truthful rationale — with every tunable centralized in one config object.

### Changes Required:

#### 1. Profile snapshot read for the recommender

**File**: `src/main/java/com/nextslope/profile/PreferenceProfileService.java`

**Intent**: Expose the raw profile axes (experience, difficulty band/mix, novelty, region set) to the recommendation domain without leaking the JPA entity or the web form.

**Contract**: A `@Transactional(readOnly = true)` method resolving by `userId` and returning an immutable snapshot (record) of the four axes, or an empty/optional result when no profile exists.

#### 2. Scorer SPI + default implementation + tunables config

**File**: `src/main/java/com/nextslope/recommendation/` (new package: `Scorer` interface, default `WeightedDistanceScorer`, a `ScoringConfig` record/object)

**Intent**: Compute a soft-alignment score per surviving candidate behind an interface so the refinement session can swap or retune without touching call sites. Ship Approach A: `align_diff = 1 − L1(prefMix, resortMix)/200`; experience via a hardness index `H = (0·easy + 0.5·medium + 1·hard)/100` with per-level targets, `align_exp = 1 − |H − target|`; `score = w_diff·align_diff + w_exp·align_exp`.

**Contract**: `Scorer` takes a candidate resort's mix + profile axes and returns per-axis alignments and a combined score. **All** tunables — axis weights, hardness-index per-level targets, and the rationale alignment threshold — live in `ScoringConfig` with defaults in one place; no magic numbers scattered across the engine.

#### 3. Recommendation service (hard filters → score → order → sparse branch)

**File**: `src/main/java/com/nextslope/recommendation/RecommendationService.java`

**Intent**: The single entry point: take `userId`, load the profile snapshot, build the active candidate pool, apply region + novelty hard filters, score survivors, order them, and return either the top three or an explicit sparse explanation.

**Contract**: `recommend(Long userId)` → result DTO. Pool = `findByActiveTrueOrderByCountryAscNameAsc()`. Region keep iff `regionCountries.isEmpty() || regionCountries.contains(resort.country)`. Novelty: if `NEW_ONLY`, drop ids in `visitedResortIds(userId)`. If survivors < 3 → sparse result (explanation, no scoring/padding). Else rank by `(-score, country, name, id)`, take 3. No-profile → a distinct "set up your profile" result state. `@Transactional(readOnly = true)`.

#### 4. Truthful rationale builder

**File**: `src/main/java/com/nextslope/recommendation/RationaleBuilder.java`

**Intent**: Produce the one-line "why this matched you" string per recommended resort, truthfully — naming only axes the user set and whose alignment clears the threshold, choosing the strongest qualifying one, with a truthful generic fallback.

**Contract**: Given a resort's per-axis alignments + which axes the user set + `ScoringConfig` threshold, return a clause referencing the strongest qualifying set axis (e.g. region / difficulty mix / experience), or a truthful fallback when none qualify. Never asserts a difficulty-mix match when alignment is below threshold.

#### 5. Result DTOs

**File**: `src/main/java/com/nextslope/recommendation/` (result + per-card DTOs)

**Intent**: Carry exactly what the view needs (key facts + rationale per card, or the sparse/no-profile state) — never entities.

**Contract**: A result type discriminating the three outcomes (three cards / sparse explanation / no-profile prompt); each card exposes name, country, top lift height, total slopes, total lifts, difficulty mix, and rationale.

### Success Criteria:

#### Automated Verification:

- Recommendation unit tests pass: `./gradlew test --tests "com.nextslope.recommendation.*"`
- Determinism test passes (same inputs → same three in same order across repeated runs).
- Sparse-path test passes (<3 survivors → explanation, never padded/fewer-silent).
- Truthfulness tests pass (no difficulty clause emitted below threshold; chosen axis is genuinely strongest qualifying set axis).
- Full suite green: `./gradlew test`

#### Manual Verification:

- A narrow single-small-country region selection yields the explicit sparse explanation.
- A broad "any region" profile yields three ranked, sensibly-ordered cards.
- A `NEW_ONLY` user never sees a visited resort in the three.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: Web layer + navigation entry point

### Overview

Expose the engine via `POST /recommend` returning an HTMX partial swapped into a results container on `/resorts`, add the "Recommend resorts" entry point, and prove gating + privacy.

### Changes Required:

#### 1. RecommendController

**File**: `src/main/java/com/nextslope/web/RecommendController.java`

**Intent**: Principal-scoped HTMX endpoint that resolves the current user, calls the recommendation service, and returns the result fragment for in-place swap — mirroring `VisitedController`.

**Contract**: `@PostMapping("/recommend")`, resolves `userId` via `currentUserService.requireUserId(principal)` (no id in path → no IDOR surface), puts the result DTO on the model under names matching the fragment params, returns a thin wrapper template that `th:replace`s the results fragment.

#### 2. Results fragment + trigger + container on the browse page

**File**: `src/main/resources/templates/resorts/list.html` (+ a thin wrapper response template under `resorts/`)

**Intent**: Add the "Recommend resorts" button (`hx-post="/recommend"`, `hx-target="#recommend-results"`, `hx-swap="innerHTML"`, `hx-indicator`), an empty `#recommend-results` container, and the named results fragment rendering three cards / sparse explanation / no-profile prompt. Reuse the hidden-`th:block` named-fragment idiom already in this file.

**Contract**: Fragment renders the three discriminated states; button satisfies the 2s-progress NFR via `hx-indicator`; page already uses `layout :: head` / `layout :: scripts` so HTMX CSRF wiring applies unchanged.

#### 3. Navigation entry point

**File**: `src/main/resources/templates/resorts/list.html` (and/or `fragments/layout.html` navbar)

**Intent**: Make the recommendation reachable — surface the trigger prominently on `/resorts` (where profile-save already redirects). Update `index.html` "coming soon" copy if it implies the feature is unavailable.

**Contract**: A visible, labeled control on the authenticated browse surface; satisfies the lessons.md navigation rule.

### Success Criteria:

#### Automated Verification:

- Controller/security slice tests pass: `./gradlew test --tests com.nextslope.web.RecommendControllerTests` (`@WebMvcTest` + `@Import(SecurityConfig)`).
- Permit-list lock still green: `./gradlew test --tests com.nextslope.PermitListLockTests` — its `@WebMvcTest` context will now load `RecommendController`, so add a `@MockitoBean RecommendationService` (and any other deps the controller injects) minimal stub, mirroring the existing `VisitedResortService` mock, or the slice fails to start.
- Privacy (principal-isolation) integration test passes using `TwoUserIntegrationTestBase`: `/recommend` is principal-scoped (no user id in the path), so this is an isolation assertion, not a 403/IDOR check. Seed distinct profiles for A and B; as B (and as admin) POST `/recommend` and assert the response reflects that principal's own profile (resort names/scores), never A's. Mirror `PreferenceProfileOwnershipIntegrationTests` / `VisitedResortOwnershipIntegrationTests`; do not use `assertWrongOwnerDenied` (placeholder for an id-in-path route shape that doesn't apply here).
- Full suite green: `./gradlew test`

#### Manual Verification:

- Clicking "Recommend resorts" on `/resorts` swaps in the result with a visible progress indicator, no full reload.
- Unauthenticated `POST /recommend` redirects to login.
- Tweaking a preference and re-running updates the result (Secondary success outcome).

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 4: Mutation gate + refinement handoff

### Overview

Wire the deferred PIT mutation gate scoped to the recommendation package and write the refinement brief that defines the contained follow-up session.

### Changes Required:

#### 1. PIT mutation gate

**File**: `build.gradle`

**Intent**: Stand up mutation testing scoped to the branch-heavy scoring/filter/rationale logic, per test-plan §6.5, so the engine's behavior is genuinely pinned.

**Contract**: Add the gradle-pitest plugin using the versions pinned in `test-plan.md` §4 (Stack row) — `info.solidsoft.pitest` 1.19.0 with `junit5PluginVersion` 1.2.3 — not arbitrary latest. Target classes `com.nextslope.recommendation.*` with the matching test classes; expose a task runnable locally and in CI. Calibrate the mutation-coverage threshold empirically from the first green run (no numeric % preset). Java 21 toolchain unchanged. Heed the two `test-plan.md` §6.5 verify-at-wiring caveats: (a) smoke-run on Gradle 9.4.1 to confirm config-cache compatibility, and (b) check the JUnit Platform version against the pitest-junit5-plugin's expectations.

#### 2. CI wiring

**File**: `.github/workflows/ci.yml`

**Intent**: Run the mutation gate (or fold it into the gated build) so regressions in the recommendation logic fail the PR.

**Contract**: A CI step invoking the PIT task; build fails below threshold.

#### 3. Refinement handoff brief

**File**: `context/changes/three-resort-recommendation/refinement-brief.md`

**Intent**: Give the separate algorithm-refinement session a written contract: every open knob, its shipped default, and the characterization tests guarding current behavior — so refinement is a values-and-tests edit, not reverse engineering.

**Contract**: Enumerate each `ScoringConfig` knob (axis weights, hardness-index targets per experience level, rationale alignment threshold), its default and rationale, the experience↔mix mapping decision still open, and the test names that lock behavior. Name the deferred decisions from `research.md` Open Questions 1–2.

### Success Criteria:

#### Automated Verification:

- Smoke-verify `./gradlew pitest` locally on Gradle 9.4.1 (config-cache compatible, JUnit Platform vs pitest-junit5-plugin OK) before wiring CI.
- Mutation gate runs and passes its threshold: `./gradlew pitest` (or the wired task name).
- CI green on the PR (including the mutation step).
- Full suite green: `./gradlew test`

#### Manual Verification:

- `refinement-brief.md` lists every tunable with default + guarding test and is understandable to someone who wasn't in this planning session.
- The mutation report shows surviving mutants are understood/acceptable, not a coverage hole in the truthfulness/determinism logic.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful.

---

## Testing Strategy

### Unit Tests:

- Hard filters: region (empty = any; membership), novelty (`NEW_ONLY` excludes visited; `REVISIT_OKAY` doesn't), active-only pool.
- Scorer: `align_diff` / `align_exp` math at boundaries; combined score monotonicity.
- Ordering/determinism: stable `(-score, country, name, id)`; repeated runs identical; no reliance on set iteration order.
- Sparse branch: 0/1/2 survivors → explanation; exactly 3 and >3 → three cards.
- Rationale truthfulness: below-threshold axis never named; strongest qualifying axis chosen; fallback when none qualify.
- No-profile state.
- Seed loader: default empty-table path, resync upsert/insert/idempotency, country-distribution guarantee.

### Integration Tests:

- Privacy (principal-isolation) via `TwoUserIntegrationTestBase`: `/recommend` has no user id in the path, so prove isolation, not denial — as user B and as admin, POST `/recommend` and assert the result reflects that principal's own profile, never A's. Follow the existing `PreferenceProfileOwnershipIntegrationTests` template; not a 403/`assertWrongOwnerDenied` check.
- `@WebMvcTest` controller + `SecurityConfig`: gating, model/fragment binding.
- Permit-list lock unchanged (`/recommend` stays gated).

### Manual Testing Steps:

1. Sign in, set a broad profile, click "Recommend resorts" → three ranked cards with progress shown.
2. Set region to a single small country → explicit sparse explanation, no padding.
3. Set `NEW_ONLY`, mark a would-be match visited → it never appears in the three.
4. Enable the resync property against a populated DB → catalog updates to the expanded set with no loss/dupes.

## Performance Considerations

The candidate pool is ~100–150 rows; in-memory region filtering and scoring are trivial. The `hx-indicator` satisfies the 2s-progress NFR. No new query is required (region filtering stays in-memory over the active pool).

## Migration Notes

No schema migration. Dataset expansion is data-only (CSV + loader). Already-populated environments (prod and file-backed local H2) refresh via the opt-in resync property; fresh databases seed via the unchanged empty-table path.

## References

- Related research: `context/changes/three-resort-recommendation/research.md`
- HTMX endpoint pattern: `src/main/java/com/nextslope/web/VisitedController.java:30-45`
- Named-fragment idiom: `src/main/resources/templates/resorts/list.html:53-69`
- CSRF/HTMX wiring: `src/main/resources/templates/fragments/layout.html:38-64`
- Seed loader: `src/main/java/com/nextslope/resort/ResortSeedLoader.java:40-55`
- Scoring inputs: `src/main/java/com/nextslope/profile/PreferenceProfile.java:53-83`, `src/main/java/com/nextslope/resort/Resort.java:121-162`
- Privacy test base: `src/test/java/com/nextslope/support/TwoUserIntegrationTestBase.java`
- Gated-route lock: `src/test/java/com/nextslope/PermitListLockTests.java:85`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Expand the European dataset + opt-in resync seed

#### Automated

- [x] 1.1 Seed loader tests pass (`./gradlew test --tests com.nextslope.resort.ResortSeedLoaderTests`)
- [x] 1.2 Postgres-engine seed test passes (`ResortRepositoryPostgresTests`)
- [x] 1.3 Full suite green (`./gradlew test`)

#### Manual

- [x] 1.4 Fresh local DB seeds the expanded set; list shows the larger catalog
- [x] 1.5 Resync property updates a populated DB with no loss/dupes
- [x] 1.6 Every region-selector country has ≥3 resorts

### Phase 2: Recommendation engine (filters + pluggable scorer + truthful rationale)

#### Automated

- [ ] 2.1 Recommendation unit tests pass (`com.nextslope.recommendation.*`)
- [ ] 2.2 Determinism test passes
- [ ] 2.3 Sparse-path test passes
- [ ] 2.4 Truthfulness tests pass
- [ ] 2.5 Full suite green (`./gradlew test`)

#### Manual

- [ ] 2.6 Narrow region selection → explicit sparse explanation
- [ ] 2.7 Broad profile → three sensibly-ordered cards
- [ ] 2.8 `NEW_ONLY` user never sees a visited resort in the three

### Phase 3: Web layer + navigation entry point

#### Automated

- [ ] 3.1 Controller/security slice tests pass (`RecommendControllerTests`)
- [ ] 3.2 Permit-list lock still green (`PermitListLockTests`)
- [ ] 3.3 Privacy (principal-isolation) integration test passes
- [ ] 3.4 Full suite green (`./gradlew test`)

#### Manual

- [ ] 3.5 Click "Recommend resorts" swaps in result with progress, no reload
- [ ] 3.6 Unauthenticated `POST /recommend` redirects to login
- [ ] 3.7 Tweak a preference and re-run updates the result

### Phase 4: Mutation gate + refinement handoff

#### Automated

- [ ] 4.1 Smoke-verify `./gradlew pitest` locally on Gradle 9.4.1 before CI wiring
- [ ] 4.2 Mutation gate runs and passes its threshold
- [ ] 4.3 CI green including the mutation step
- [ ] 4.4 Full suite green (`./gradlew test`)

#### Manual

- [ ] 4.5 `refinement-brief.md` lists every tunable with default + guarding test
- [ ] 4.6 Surviving mutants understood/acceptable, no truthfulness/determinism coverage hole
