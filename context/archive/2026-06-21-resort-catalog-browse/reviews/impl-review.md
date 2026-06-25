<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Resort Catalog & Browsing (S-03)

- **Plan**: context/changes/resort-catalog-browse/plan.md
- **Scope**: All 3 phases (full plan)
- **Date**: 2026-06-25
- **Verdict**: APPROVED
- **Findings**: 0 critical · 2 warnings · 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | WARNING |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

Success Criteria evidence: `./gradlew cleanTest test` green; 21 test classes executed including the Testcontainers Postgres resort repository test. All 11 planned items semantically match their contracts. No injection (escaped `th:text`/`@{...}`, no `th:utext`), authz correct (`/resorts/**` gated + pinned in `PermitListLockTests`), 404 path correct, `external_id` never rendered, difficulty-mix largest-remainder math provably sums to 100 with zero-denominator guard.

## Findings

### F1 — Malformed numeric CSV cell hard-fails app startup

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/nextslope/resort/ResortSeedLoader.java:115-128
- **Detail**: parseInt/parseLong/parseDouble handle null/empty (→ null) but a non-empty non-numeric cell (e.g. "N/A", "1,234", a decimal in an INTEGER column) throws an uncaught NumberFormatException. parseResorts() only catches IOException, so it propagates through ApplicationRunner.run → the app fails to boot with no row/column context. Blast radius is bounded (CSV ships in-jar; seed test parses it so CI catches a bad cell pre-prod) and fail-fast is defensible, but the failure is undiagnosable as-is.
- **Fix**: Wrap row mapping so a bad cell is reported with location context — record.getRecordNumber() + header name + offending value — instead of a bare NumberFormatException. Keep fail-fast; just make it diagnosable.
  - Strength: Turns a cryptic boot failure into an actionable message; cheap, localized to the loader.
  - Tradeoff: A few extra lines wrapping the per-row map; no behavior change on good data.
  - Confidence: HIGH — bounded, single class, no external callers.
  - Blind spot: None significant.
- **Decision**: FIXED — location-aware numeric parsers throw a row/column/value-tagged IllegalStateException

### F2 — Changes beyond the plan's file list

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: templates/index.html, HomeControllerWebMvcTests.java, CsrfEnforcedTests.java, H2ConsoleProfileTests.java, support/RoleGatingPatternTests.java
- **Detail**: Four files changed that aren't in the plan's "Changes Required": (1) index.html "Browse resorts" link — already documented in change.md notes, sensible (otherwise /resorts is URL-only); (2) HomeControllerWebMvcTests covers that new link; (3) @MockitoBean ResortRepository added to three existing @WebMvcTest classes — mechanical, required so their slice context wires now that ResortController is a bean. All benign and in-scope; flagged only so the deviation from the written plan is recorded.
- **Fix**: No code change — acknowledge the extras (the index.html link is already in change.md; the test mocks are forced by the new bean).
- **Decision**: ACKNOWLEDGED — extras are benign and in-scope; no code change

### F3 — parseYesNo coerces null/blank/unknown to false

- **Severity**: 🟡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/nextslope/resort/ResortSeedLoader.java:111-113
- **Detail**: Returns primitive boolean, so a missing/blank amenity cell becomes a definite "No" rather than null/unknown. Correct for the current curated CSV (all Yes/No); a future data refresh with a blank would silently render "No". Display-only column, so no action needed now.
- **Fix**: Optional — note the assumption in the loader, or return Boolean treating only Yes/No as known if amenity fidelity matters later.
- **Decision**: SKIPPED — accept current behavior for the curated CSV

### F4 — layout fragment decomposed vs single page(title,content)

- **Severity**: 🟡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence / Pattern Consistency
- **Location**: src/main/resources/templates/fragments/layout.html
- **Detail**: Plan suggested a single th:fragment="page(title,content)" wrapper; implementation uses a decomposed head(title)/navbar/scripts trio. Plan phrased the signature as "e.g.", all Bootstrap/HTMX SRI pins are verified identical to index.html, and index/login/signup were left untouched per the plan. Functional contract holds. Also: migration uses VARCHAR(255) vs bare VARCHAR — portable on both engines, benign.
- **Fix**: None — accept as an equivalent implementation choice.
- **Decision**: ACCEPTED — equivalent implementation choice
