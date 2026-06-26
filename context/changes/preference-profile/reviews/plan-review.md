<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Preference Profile (S-02)

- **Plan**: context/changes/preference-profile/plan.md
- **Mode**: Deep
- **Date**: 2026-06-25
- **Verdict**: SOUND
- **Findings**: 0 critical, 2 warnings, 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | PASS |
| Plan Completeness | PASS (was WARNING; F1/F2/F3 fixed) |

## Grounding

12/12 paths ✓, 6/6 symbols ✓, brief↔plan ✓, contract-surfaces.md absent (skipped). Lesson check: "navigation to every new screen" — SATISFIED (navbar link + post-signup redirect). Progress↔Phase mapping mechanically consistent.

## Findings

### F1 — View needs enum option lists, but the model only carries profileForm + availableCountries

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 3 §1 (Controller) + §2 (Profile view)
- **Detail**: The view renders experience / novelty / difficulty as radio or select controls over each enum's values, but the controller contract only adds `profileForm` and `availableCountries` to the model. The template has no source for the three enums' option lists. An implementer will improvise (SpEL `T(...).values()` vs. model attributes), which the plan should pin down to keep the view convention consistent.
- **Fix**: State the enum-option source explicitly — either add the three `*.values()` lists as model attributes in the GET handler (and re-add them on the POST error re-render, like `availableCountries`), or mandate `T(com.nextslope.profile.X).values()` SpEL in the template.
- **Decision**: FIXED — mandated `T(...).values()` SpEL in the view contract (Phase 3 §2) + noted in controller contract (§1) that enum lists are not model attributes.

### F2 — Band→mix triples (the S-05 contract constant) have no dedicated verification checkbox

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 1 (enums) + Testing Strategy + Progress
- **Detail**: The canonical `DifficultyBand → DifficultyMix` triples (60/30/10, 34/33/33, 10/30/60) are the single most contract-critical constant in the plan — S-05 scores against them and rationale truthfulness echoes them. The Testing Strategy lists "DifficultyBand.toMix() sums to 100", but the enum ships in Phase 1 while no Progress checkbox gates it. It only incidentally runs under Phase 2's `com.nextslope.profile.*` glob, leaving the highest-value constant without an explicit gate.
- **Fix**: Add a Phase-1 success-criteria bullet + matching Progress checkbox (e.g. "1.x DifficultyBand.toMix() returns the canonical triple summing to 100 for each band") and put the test in Phase 1.
- **Decision**: FIXED — added a dedicated `DifficultyBandTests` (Phase 1 §1), a Phase-1 Automated success criterion, and Progress checkbox 1.2 (renumbered 1.3–1.6).

### F3 — loadFormForUser doesn't state deriving `anyRegion` from a stored empty region set

- **Severity**: 🟦 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 2 §2 (Service: loadFormForUser)
- **Detail**: Defaults set `anyRegion=true`/empty for new users, and save normalizes `anyRegion || empty → empty set`. But the contract for mapping an EXISTING profile back to a form never says to set `anyRegion = regionCountries.isEmpty()`. If left false on a saved-but-empty region set, the "Any region" checkbox renders unchecked, misrepresenting state.
- **Fix**: In loadFormForUser's existing-profile branch, set `anyRegion = regionCountries.isEmpty()` and note it in the contract.
- **Decision**: FIXED — added the `anyRegion = regionCountries.isEmpty()` derivation to the existing-profile mapping in `loadFormForUser`'s contract (Phase 2 §2).

### F4 — Difficulty stored as a band enum deviates from research's "persist explicit percentages"

- **Severity**: 🟦 OBSERVATION
- **Impact**: 🔎 MEDIUM — real tradeoff; worth a conscious confirmation
- **Dimension**: End-State Alignment
- **Location**: Critical Implementation Details + Phase 1 §1
- **Detail**: research.md (§B, §C) twice recommends storing three explicit percentage columns "regardless of the eventual UI affordance" as the lowest-risk S-05 contract. The plan deliberately reverses this: store the band enum, derive the mix in code. This is defensible — it satisfies both the determinism NFR (same band → same mix) and rationale truthfulness (the band maps to a printable triple), and the plan-brief flags it as a conscious deviation. The residual risk is granularity: 3 coarse bands, all scored against the same `Resort.getDifficultyMix()` that experience is also scored against, may give S-05 thin signal — an S-05 concern, not an S-02 blocker.
- **Fix**: None required — confirm the band approach is the intended S-05 contract (the brief already records it as deliberate).
- **Decision**: ACCEPTED — band-enum storage confirmed as the intended S-05 contract (deliberate deviation from research, recorded in the brief). No plan change.

### F5 — Form-login success still routes to `/`, while signup now routes to `/profile`

- **Severity**: 🟦 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 3 §4 vs. SecurityConfig.java:57 (defaultSuccessUrl("/", true))
- **Detail**: Phase 3 sends new signups to `/profile`, but returning users who sign in still land at `/`, then must use the new navbar link to reach their saved profile. Not a defect — reachability is covered by the navbar link — just a minor onboarding asymmetry. The plan explicitly scopes SecurityConfig as untouched, so this is intentional; noting it so the manual round-trip (3.8) expects the navbar hop after sign-in rather than an auto-land on /profile.
- **Fix**: None required — confirm the sign-in → `/` + navbar-hop flow is intended (changing it would touch SecurityConfig, which is out of scope).
- **Decision**: ACCEPTED — sign-in → `/` + navbar-hop confirmed intended; SecurityConfig stays out of S-02 scope. No plan change.
