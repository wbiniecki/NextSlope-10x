<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Three-Resort Recommendation (S-05)

- **Plan**: context/changes/three-resort-recommendation/plan.md
- **Scope**: Phase 1 of 4 (dataset expansion + opt-in resync seed)
- **Date**: 2026-06-28
- **Commit**: 0042b90
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 1 warning, 4 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Findings

### F1 — Resync upsert runs without a transaction boundary

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality (Reliability)
- **Location**: src/main/java/com/nextslope/resort/ResortSeedLoader.java:73-89
- **Detail**: `resync()` loops `save()` once per CSV row with no `@Transactional` on the proxied entry. Each save commits in its own transaction, so a DB failure mid-loop (constraint, dropped connection) leaves the table half-reconciled. The default seed path is atomic — `saveAll()` is wrapped by Spring Data's own `@Transactional` — so resync is inconsistent with it and with the `@Transactional` service siblings (`PreferenceProfileService`, `VisitedResortService`). A rerun heals it (idempotent), but the operator observes a partial prod state on first failure. The plan promised "idempotent across reruns" but said nothing about atomicity within a run.
- **Fix**: Annotate the proxied `ApplicationRunner` entry `run()` with `@Transactional` so the whole resync commits atomically.
  - Strength: Brings resync in line with the atomic default-seed path and the `@Transactional` convention used across services.
  - Tradeoff: None meaningful — 150 rows in one transaction is trivial.
  - Confidence: HIGH — but `seed()`/`resync()` are private self-invoked calls, so the annotation MUST sit on `run()` (Spring invokes that through the proxy); on a private method it's a no-op.
  - Blind spot: None significant.
- **Decision**: FIXED — `@Transactional` added to `run()` (ResortSeedLoader.java).

### F2 — Rows with a blank ID would break resync idempotency

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/nextslope/resort/ResortSeedLoader.java:78
- **Detail**: A blank ID cell maps to `externalId=null` (`parseLong` returns null). `findByExternalId(null)` returns empty → the insert branch fires, and Postgres treats each NULL as distinct under `UNIQUE(external_id)`, so every resync rerun would insert another null-id row (count grows, not idempotent). Latent only — the curated CSV has unique non-blank IDs — but it's a hole in exactly the path this phase added.
- **Fix**: In resync, skip or throw on a null `externalId` rather than insert.
- **Decision**: FIXED — resync loop now throws on a null `externalId`.

### F3 — CSV silently switched LF → CRLF line endings

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/main/resources/data/resorts-Europe-subset.csv
- **Detail**: Beyond "append/replace data rows," the file's byte-level line endings flipped LF→CRLF. Harmless to commons-csv parsing and the green suite, but a real, unnoted deviation that muddies the diff.
- **Fix**: Normalize to LF to match the rest of the repo (or add a `.gitattributes` rule) — cosmetic, optional.
- **Decision**: FIXED — CSV normalized to LF and `*.csv text eol=lf` pinned in new `.gitattributes`.

### F4 — Resync does O(n) per-row SELECT + save

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (Performance)
- **Location**: src/main/java/com/nextslope/resort/ResortSeedLoader.java:77-87
- **Detail**: One `findByExternalId` per CSV row. Fine at ~150 rows (the plan caps the dataset here and resync is a rare operator action), but it scales linearly with the catalog. Noting, not asking to fix — the plan explicitly parks dataset growth.
- **Fix**: None needed at current scale; revisit only if the catalog grows.
- **Decision**: FIXED — resync now does a single `findAll` indexed by `external_id` + one batched `saveAll`.

### F5 — MOSTLY_HARD difficulty coverage is thin

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Plan Adherence
- **Location**: src/main/resources/data/resorts-Europe-subset.csv
- **Detail**: The CSV intent asked for "a handful of genuine BALANCED and MOSTLY_HARD matches." The `everyDifficultyBandHasAtLeastOneNearestMatch` test passes (≥1 per band, which satisfies the curation-checklist bar), but the MOSTLY_HARD band has roughly one genuine nearest-band match — thin relative to "a handful." This matters for Phase 2: a MOSTLY_HARD profile may hit the sparse branch readily, which the engine handles truthfully but is worth knowing before tuning the scorer.
- **Fix**: Optionally add a couple more hard-skewed resorts; otherwise carry this awareness into Phase 2 scorer/rationale tuning.
- **Decision**: SKIPPED — data left as-is; awareness carried into Phase 2 scorer/rationale tuning.

## Success Criteria (Phase 1)

- 1.1 `ResortSeedLoaderTests` — PASS (re-run green)
- 1.2 `ResortRepositoryPostgresTests` — PASS (re-run green)
- 1.3 Full suite — plan marks `[x]`; targeted suites re-verified green
- Active-preservation, resync upsert/insert/idempotency, and ≥3-per-country distribution all have dedicated passing tests
- Manual (1.4–1.6) marked complete in Progress with observable test-backed evidence (count=150, distribution assertion)
