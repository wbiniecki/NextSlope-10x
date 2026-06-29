# Three-Resort Recommendation (S-05) — Plan Brief

> Full plan: `context/changes/three-resort-recommendation/plan.md`
> Research: `context/changes/three-resort-recommendation/research.md`

## What & Why

S-05 is the product's north star: a signed-in user clicks "Recommend resorts" and gets exactly three ranked picks (key facts + a truthful one-line rationale) or an explicit explanation when fewer than three viable matches exist. It's the smallest end-to-end flow that proves NextSlope's core idea — preferences + facts → trustworthy, explainable picks. This plan also expands the seed dataset (the scarce 40-resort subset makes sparse results and unsatisfiable difficulty bands the common case) and deliberately defers locking the exact scoring rules to a separate refinement session.

## Starting Point

All scoring inputs already exist (profile axes, derived resort difficulty mix, visited set); the `com.nextslope.recommendation.*` package and `/recommend` route do not (the route is already locked as gated in tests). The HTMX button→partial→indicator pattern is fully established by S-04 and reused verbatim. The seed loader only inserts into an empty table, and both prod and file-backed local H2 are already populated — so an expanded CSV needs an explicit resync. There is no "Recommend resorts" entry point yet.

## Desired End State

On `/resorts`, a user with a saved profile clicks "Recommend resorts" and within ~2s (progress shown) sees three ranked cards with truthful rationales, or an explicit sparse/no-profile message — computed against an expanded curated European dataset, deterministically and privately. All scoring tunables live in one config object, guarded by a mutation gate, with a written brief handing the open rules to the refinement session.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Work partition | One plan, phased (expansion → machinery → refinement folds back) | Keeps a coherent slice; refinement tunes an already-built scorer, not a new build | Plan |
| Build order | Data first | Design/test the engine against realistic distribution, not scarcity artifacts | Plan |
| Dataset scope | Curated larger EU subset (~100–150) | Engineers away sparsity/unsatisfiable bands with the smallest dataset | Plan |
| Re-seed mechanism | Opt-in property → upsert-by-`external_id`; default = empty-table guard | Refreshes prod + local without clobbering admin edits | Plan |
| Browse scaling | Keep flat list; defer search/filter | Keeps S-05 on the north star; browse-scaling stays parked | Plan |
| Result route | HTMX `POST /recommend` partial on `/resorts` | Reuses S-04 pattern + satisfies the 2s-progress NFR | Research/Plan |
| Algorithm placeholder | Approach A behind a pluggable `Scorer`, tunables centralized | Working recommender now; refinement is a values-only edit | Research/Plan |
| Rationale truthfulness | Name only axes clearing an alignment threshold; truthful fallback | Structurally prevents over-claiming given the data reality | Plan |
| Refinement handoff | Centralized tunables + a refinement brief listing knobs/defaults/tests | Makes the follow-up a contained, contract-driven edit | Plan |
| Testing depth | Full unit + privacy/IDOR + wire the PIT gate now | Mutation testing is most valuable on this branch-heavy logic | Plan |

## Scope

**In scope:** curated EU dataset expansion + opt-in resync; `recommendation.*` engine (hard filters, pluggable defaulted scorer, deterministic ordering, sparse + no-profile branches); truthful rationale; HTMX result flow + entry point; PIT gate; refinement brief.

**Out of scope:** final algorithm rules; browse search/filter/pagination; persisting recommendation history (no V5 migration); worldwide/non-EU resorts; changing the seed default behavior; a dedicated `/recommend` page.

## Architecture / Approach

Data-first. `RecommendationService.recommend(userId)` loads a profile snapshot, builds the active-only candidate pool, applies region + novelty hard filters, scores survivors via a `Scorer` SPI (Approach A default, all knobs in `ScoringConfig`), orders by `(-score, country, name, id)`, and returns a discriminated result DTO (three cards / sparse / no-profile). A `RationaleBuilder` applies a threshold-gated dominant-axis rule. `RecommendController` returns an HTMX fragment swapped into a `#recommend-results` container on `/resorts`. Determinism is by construction: no `HashSet`/`HashMap` iteration in ranking, no `parallelStream()`.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Dataset + resync seed | Curated ~100–150 EU CSV + opt-in upsert-by-`external_id` resync | Resync clobbering admin rows; existing tests hard-code `40` |
| 2. Recommendation engine | Filters + pluggable scorer + truthful rationale, all tunables centralized | Truthfulness gate + determinism are the load-bearing guardrails |
| 3. Web layer + entry point | HTMX `POST /recommend` partial + "Recommend resorts" button + privacy tests | Privacy/IDOR; navigation reachability |
| 4. Mutation gate + handoff | PIT scoped to `recommendation.*` + refinement brief | PIT setup/threshold tuning against placeholder weights |

**Prerequisites:** S-02, S-03, S-04 (all done). External source for the expanded curated European resort facts (CSV).
**Estimated effort:** ~4 sessions across 4 phases.

## Open Risks & Assumptions

- The exact scoring rules are placeholders by design; the refinement session must follow to lock them (research Open Questions 1–2).
- Curated selection must genuinely deliver ≥3 per offered region and real matches per difficulty band — verified by a distribution test, not by eye.
- Resync correctness against an admin-edited table is the trickiest seam; tests must prove no row loss and no unintended `active`/edit clobbering.
- PIT thresholds are tuned against placeholder weights; the refinement session may need to revisit them.

## Success Criteria (Summary)

- A user gets exactly three ranked, truthfully-rationalized picks (or an explicit sparse/no-profile message), within ~2s, deterministically and privately.
- The expanded dataset removes routine sparsity: every selectable region has ≥3 resorts and each difficulty band has genuine matches.
- The engine is pinned by unit + privacy tests and a mutation gate, with a written refinement brief defining the remaining rule-locking work.
