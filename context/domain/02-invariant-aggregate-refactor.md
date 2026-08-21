---
title: NextSlope — Resort difficulty-facts coherence invariant: guardian-aggregate refactor plan
created: 2026-08-04
type: refactor-plan
---

# Invariant #1 and its guardian aggregate — refactor plan

> A PLAN, not an implementation. Every `file:line` citation was verified by directly reading the
> file on the day this document was created. Sibling document:
> `context/domain/01-domain-distillation.md` (the domain map); this document selects ONE invariant
> and designs its guardian.

## STEP 0 — Context

**Source documents** (in order of authority): `context/foundation/prd.md` (vision, guardrails,
US/FR, Business Logic), `context/foundation/roadmap.md` (north star S-05, slices, parked items),
`context/foundation/test-plan.md` (test strategy, tiers, gates), `AGENTS.md` (hard persistence
rules: Flyway forward-only, portable DDL, `ddl-auto=validate`).

**Stack and the layers where business logic lives:**

| Layer | Where | Role for the logic |
|---|---|---|
| UI (Thymeleaf + HTMX + JS) | `src/main/resources/templates/` | presentation + live preview (the admin form has its own JS computing the mix — `templates/admin/resorts/form.html:119-182`) |
| Web (`@Controller`) | `src/main/java/com/nextslope/web/` | thin controllers, mapping errors to views |
| Application services | `com.nextslope.{resort,profile,visited,user,recommendation}` | where most rules live today |
| Entities / VOs | `Resort`, `DifficultyMix`, `ProfileSnapshot`, … | partially anemic (`Resort` has wide-open `@Setter`/`@Builder` — `Resort.java:25-29`) |
| Persistence (Flyway) | `src/main/resources/db/migration/V1–V5` | schema; the `resorts` table has no CHECK constraints |
| Seed (CSV) | `ResortSeedLoader` + `data/resorts-Europe-subset.csv` (150 rows, verified by script) | a second, parallel write path into the resort catalog |

The PRD's **Business Logic section** explicitly defines the engine's inputs: *"Resort facts — for
each resort: name, location, top lift height, number of slopes, number of lifts, and the
easy/medium/hard slope difficulty mix expressed as percentages summing to 100"*
(`context/foundation/prd.md:150`), and the two-stage matching, hard filters → weighted alignment
(`prd.md:154`).

## STEP 1 — Identifying the business invariants

Status: **E** = enforced (code/schema makes violation impossible or rejected), **E-app** = enforced
in the application only (no schema backstop), **Partial** = enforced on some paths only,
**Declared** = written down in a document/comment, code relies on convention.

| # | Invariant (must always hold) | Source (document) | Source (code) | Status |
|---|---|---|---|---|
| N1 | The easy/medium/hard difficulty mix always sums to 100 | `prd.md:93` — "must sum to 100; submissions where they don't are rejected" | largest-remainder derivation: `Resort.java:132-167`; VO contract: `DifficultyMix.java:3-8` | **E by construction** (percentages are never an input — they derive from counts) |
| N2 | **Resort difficulty facts are coherent: `total_slopes` = the sum of the three counts, and the mix that is displayed and scored derives from those same counts** | `prd.md:150` (one coherent set of facts); `prd.md:131` (FR-011: "number of slopes" as a fact) | admin path derives it: `ResortService.java:71-73`; seed path trusts the CSV: `ResortSeedLoader.java:175` and `:127`; the entity admits divergence: `Resort.java:126-131` ("not totalSlopes, which can differ") | **Partial** — enforced on 1 of 2 write paths, zero schema support |
| N3 | Numeric resort facts are non-negative integers | `prd.md:94` | `@PositiveOrZero` on the form only: `ResortForm.java:28-46`; no CHECK in the schema: `V2__create_resorts.sql:13-16` | **E-app** (the form boundary only; seed and SQL are unguarded) |
| N4 | Exactly three results or an explicit explanation — never padded, never silently short | `prd.md:53` | sparse branch before scoring: `RecommendationService.java:66-68`; `limit(3)`: `:73`; discriminated type: `RecommendationResult.java:14-32` | **E** |
| N5 | A NEW_ONLY user never sees a visited resort in the top three | `prd.md:52` | hard filter: `RecommendationService.java:56-63` | **E** |
| N6 | Same inputs ⇒ same three results in the same order (determinism) | `prd.md:143` | total-order comparator with tie-break: `RecommendationService.java:39-45` | **E** |
| N7 | The rationale is truthful — it reflects the actual matching | `prd.md:54` | a clause only when the axis was set and clears the threshold: `RationaleBuilder.java:23-56`; threshold: `ScoringConfig.java:27` | **E** (+ PIT mutation gate on `com.nextslope.recommendation.*`) |
| N8 | A deactivated resort disappears from browsing and new recommendations; existing visited references keep working | `prd.md:135` (FR-013) | user-facing reads filter on `active`: `ResortController.java:30,37`, `RecommendationService.java:60`; unmark works despite deactivation: `VisitedResortService.java:32-39` | **E-app** (`findByActiveTrue…` query discipline, nothing at the type level) |
| N9 | Exactly one preference profile per user | `prd.md:158` | `UNIQUE (user_id)`: `V3__create_preference_profiles.sql:9` + upsert: `PreferenceProfileService.java:59-71` | **E** |
| N10 | At most one visited mark per (user, resort) pair; marking is reversible | `prd.md:81` | `UNIQUE (user_id, resort_id)`: `V4__create_visited_resorts.sql:6`; toggle: `VisitedResortService.java:32-48` | **E** |
| N11 | The profile and visited list are visible only to their owner (not even to admins) | `prd.md:55` | structurally: services take only the authenticated `userId`, no addressable route to another user's data — `PreferenceProfileService.java:17-21`, `VisitedResortService.java:13-17` | **E** (convention + IDOR tests per test-plan §6.4) |
| N12 | Account deletion removes the profile and visited list everywhere, immediately, atomically | `prd.md:144` | single transaction, children before parent: `AccountService.java:30-38` | **E-app** (no DB cascade; one method is the only path) |
| N13 | Selected regions come from the live catalog vocabulary | supports the region hard filter `prd.md:154` | validated at save time: `PreferenceProfileService.java:83-96` (`UnknownRegionCountryException`) | **E-app** (at save time only) |

Generic-subdomain invariants (email uniqueness, role assignment) are omitted — they are not core
and have schema backing; the full map lives in `01-domain-distillation.md`.

## STEP 2 — Classification and picking #1

Three axes: (a) how core to the product's meaning, (b) how smeared across layers, (c) how genuinely
enforced. Core-ness is anchored in the PRD's insight — *"no existing tool fuses objective resort
facts with the user's own preferences into a single short ranked answer"* (`prd.md:24`) — and in
the roadmap's north star: S-05, three real recommendations with a truthful rationale
(`roadmap.md:24`).

| # | (a) Core-ness | (b) Spread | (c) Enforcement | Verdict |
|---|---|---|---|---|
| N2 difficulty-facts coherence | **High** — the mix is the only scoring input (`WeightedDistanceScorer.java:25-38` consumes nothing but `DifficultyMix`); "garbage in" invalidates N6 and N7 despite their formal correctness | **Highest** — 4 layers, 8+ places (diagnosed in STEP 3) | **Weakest** — enforced on one of two write paths, entity open to mutation, schema without CHECKs, seed swallows incoherence | **SELECTED #1** |
| N4/N5/N6/N7 (the engine) | Maximal | Low — concentrated in `recommendation/` | **Strongest in the repo** — discriminated type, comparator, threshold, tests + PIT gate | nothing to do |
| N3 non-negativity | Medium | Medium | E-app (the form), seed/SQL unguarded | absorbed by #1 (same aggregate) |
| N8 deactivation | Medium | Medium (query discipline) | E-app + integration tests | observe |
| N12 account deletion | Medium | Low (one method) | E-app, a documented MVP tradeoff (`AccountService.java:18-20`) | add FKs at the next migration touching the table |
| N13 region vocabulary | Low | Low | E-app at save; staleness degrades into the guardrail-compliant sparse explanation | observe |

**Why N2.** It is the only candidate sitting high on axis (a) and low on axis (c) at the same
time. The product's entire promise — truthful, deterministic, explainable three picks — consumes
`DifficultyMix` as ground truth: scoring (`RecommendationService.java:71` →
`WeightedDistanceScorer.java:25-38`), the rationale (`RationaleBuilder.java:39-42` — "its run
difficulty matches your preference"), the result cards (`RecommendationService.java:87`). Yet the
facts the mix derives from are the least-guarded data in the system: two write paths with different
rules, an entity with open setters, zero schema constraints, and an outright admission in the
javadoc that the stored `totalSlopes` "can differ" from the sum of the counts. The engine can be
provably correct (PIT ≥ 90) and still rank on garbage. The risk is not theoretical: the roadmap
parks a dataset swap to ~500 worldwide resorts (`roadmap.md:224`) — it would go through exactly the
unguarded seed/resync path. Today the data is clean by luck, not by mechanism (verified: 150/150
CSV rows coherent), and S-06 itself called these validation rules "the load-bearing details"
(`roadmap.md:155`).

## STEP 3 — Diagnosis: where the rule lives today

### 3.1 Occurrences (all layers)

**(1) Admin path — enforces by derivation** (`ResortService.java:62-74`):

```java
resort.setTotalSlopes(form.getBeginnerSlopes()
        + form.getIntermediateSlopes()
        + form.getDifficultSlopes());   // ResortService.java:71-73
```

**(2) Seed path (initial load) — does NOT enforce** (`ResortSeedLoader.java:160-189`):

```java
.difficultSlopes(parseInt(record, "Difficult slopes"))
.totalSlopes(parseInt(record, "Total slopes"))          // ResortSeedLoader.java:175 — CSV value taken as-is
```

**(3) Resync path (upsert by `external_id`) — does NOT enforce** (`ResortSeedLoader.java:114-139`):

```java
existing.setDifficultSlopes(incoming.getDifficultSlopes());
existing.setTotalSlopes(incoming.getTotalSlopes());     // ResortSeedLoader.java:126-127 — copies the CSV verbatim
```

**(4) The entity — documents the violability instead of forbidding it** (`Resort.java:126-131`):

> "Percentages are taken over the sum of those three counts (**not `totalSlopes`, which can
> differ**) and rounded by the largest-remainder method…"

Additionally, class-level `@Setter`/`@Builder` (`Resort.java:25-29`) leave the counts and
`totalSlopes` open to arbitrary mutation from anywhere — the invariant has no owner at the type
level.

**(5) The schema — zero support** (`V2__create_resorts.sql:13-16`): four nullable `INTEGER`
columns, no non-negativity `CHECK` and no sum-coherence `CHECK`. The only constraint-shaped guard
in the table is `UNIQUE (external_id)` (`V2:31`).

**(6) The form boundary — UX-level non-negativity only** (`ResortForm.java:36-46`):
`@PositiveOrZero` on the counts; the form deliberately does not collect `totalSlopes`
(`ResortForm.java:11-14`).

**(7) The UI — a duplicated algorithm on the client** (`templates/admin/resorts/form.html:119-182`):
the mix preview in JS, with the comment *"Mirrors Resort.getDifficultyMix() largest-remainder
rounding"* (`form.html:120`). The client is not the sole guardian here (the server derives on the
admin path anyway), but the copied algorithm can drift from the server's and show the admin a
different mix than what gets stored.

**(8) Read surfaces — the user can see the contradiction**: the list shows the stored `totalSlopes`
next to a mix derived from the counts (`templates/resorts/list.html:58` and `:62-67`), the detail
view does the same (`templates/resorts/detail.html:51-52` and `:54-58`), and the recommendation
card inherits the stored value (`RecommendationService.java:87`, rendered at
`list.html:115-127`). On an incoherent row, "Slopes: 56" can sit right next to mix badges computed
from entirely different counts.

### 3.2 Which layers fail to enforce / where the error is swallowed

- **Seed/resync swallows domain incoherence** — the loader *does* have a fail-fast culture, but only
  for format errors: a malformed numeric cell aborts startup (`ResortSeedLoader.java:235-240`), a
  missing `external_id` during resync aborts startup (`:91-94`). A row whose `Total slopes`
  disagrees with the sum of the counts — or with empty counts and a filled total — is **persisted
  without a word**.
- **`nullToZero` masks missing data** (`Resort.java:169-171`, used at `:134-136`): empty count cells
  in the CSV (`parseInt` returns `null` — `ResortSeedLoader.java:211-221`) silently yield a 0/0/0
  mix, which the scorer turns into a hardness index of 0.0 (`WeightedDistanceScorer.java:32`) — the
  resort gets a decent-looking score for beginners based on the *absence of data*, and the rationale
  can "truthfully" justify it. Fail-silent in its purest form.
- **The schema enforces nothing** — a raw `INSERT`/`UPDATE` (H2 console, psql against Neon, future
  code) can write anything.
- **The entity does not enforce** — setters/builder allow changing a count without recomputing the
  total (exactly what `copyFacts` does).
- The client (JS) is **not** the sole guardian — on the admin path the server derives; this
  invariant's problem is the seed/resync path and the rule having no owner, not client-side-only
  validation.

## STEP 4 — Designing the guardian aggregate

### 4.1 Shape: `Resort` as the root + a `SlopeCounts` value object

Principle: **difficulty facts enter the aggregate exclusively as `SlopeCounts`; `totalSlopes` and
`DifficultyMix` are always derived — on every path (admin, seed, resync)**. An illegal operation
throws a named domain error and stops the operation (fail-fast); it never logs-and-continues.

```java
// com.nextslope.resort.SlopeCounts — new VO (resort package, next to DifficultyMix)
public record SlopeCounts(int beginner, int intermediate, int difficult) {

    public SlopeCounts {                                  // constructor precondition
        if (beginner < 0 || intermediate < 0 || difficult < 0) {
            throw new IllegalSlopeCountsException(beginner, intermediate, difficult);
        }
    }

    /** The only source of totalSlopes. */
    public int total() { return beginner + intermediate + difficult; }

    /** Largest-remainder — moved 1:1 from Resort.getDifficultyMix() (Resort.java:132-167). */
    public DifficultyMix toMix() { /* the existing algorithm, numerically unchanged */ }

    /**
     * Factory for dataset rows: treats null as 0 ONLY when the declared total is also null/0;
     * a declared total that differs from the sum => IncoherentResortFactsException (fail-fast).
     */
    public static SlopeCounts fromDataset(Integer beginner, Integer intermediate,
                                          Integer difficult, Integer declaredTotal) {
        SlopeCounts counts = new SlopeCounts(nz(beginner), nz(intermediate), nz(difficult));
        if (declaredTotal != null && declaredTotal != counts.total()) {
            throw new IncoherentResortFactsException(declaredTotal, counts);
        }
        return counts;
    }
}
```

```java
// com.nextslope.resort.Resort — the root; difficulty-fact setters SEALED
@Entity
public class Resort {

    // @Setter(AccessLevel.NONE) on: beginnerSlopes, intermediateSlopes,
    // difficultSlopes, totalSlopes (or drop the class-level @Setter/@Builder
    // and keep setters only for non-fact fields). JPA uses reflection — fields stay.

    /** The ONLY mutation of difficulty facts. All three counts + total in one step. */
    public void applySlopeCounts(SlopeCounts counts) {
        this.beginnerSlopes = counts.beginner();
        this.intermediateSlopes = counts.intermediate();
        this.difficultSlopes = counts.difficult();
        this.totalSlopes = counts.total();      // derived — never an input
    }

    public SlopeCounts slopeCounts() {
        return new SlopeCounts(nz(beginnerSlopes), nz(intermediateSlopes), nz(difficultSlopes));
    }

    @Transient
    public DifficultyMix getDifficultyMix() {
        return slopeCounts().toMix();           // delegation; signature and results unchanged
    }
}
```

```java
// named domain errors (resort package) — the pattern already exists:
// DuplicateExternalIdException, ConcurrentResortUpdateException
public class IllegalSlopeCountsException extends RuntimeException { ... }
public class IncoherentResortFactsException extends RuntimeException {
    // carries: externalId/row name, declaredTotal, the computed sum — the message
    // names the violated constraint (the pattern from prd.md:93 "naming the constraint")
}
```

Sealing the setters turns the invariant into a property of the **type**: the code that today writes
`existing.setTotalSlopes(incoming.getTotalSlopes())` (`ResortSeedLoader.java:127`) stops compiling.
That is a mechanism, not a convention.

### 4.2 Repository and atomicity

`ResortRepository` (`ResortRepository.java:8-17`) remains the aggregate's repository — there are no
scattered queries to fix today; the problem was scattered *field writes*. After the refactor, every
write goes through `applySlopeCounts` inside the already-existing transaction boundaries:

- admin create/update: one transaction per operation (`ResortService.java:31-38`, `:40-47`) —
  counts + total commit together or not at all;
- seed/resync: the **whole** operation in one transaction (`ResortSeedLoader.java:49-53`,
  `@Transactional` on `run`) — an `IncoherentResortFactsException` on any row rolls back everything
  and stops application startup. A partially seeded catalog is unrepresentable.

**Schema backstop** (not a second guardian — a seatbelt for paths that bypass the JVM), following
the migration rules in `AGENTS.md` (forward-only, portable DDL, `ddl-auto=validate`):

```sql
-- V6__resort_fact_constraints.sql (portable: H2 pg-mode + Postgres)
-- 1) idempotent repair BEFORE the constraints (Neon: forward-only, no rollback)
UPDATE resorts SET beginner_slopes     = 0 WHERE beginner_slopes     IS NULL;
UPDATE resorts SET intermediate_slopes = 0 WHERE intermediate_slopes IS NULL;
UPDATE resorts SET difficult_slopes    = 0 WHERE difficult_slopes    IS NULL;
UPDATE resorts SET total_slopes = beginner_slopes + intermediate_slopes + difficult_slopes
 WHERE total_slopes IS NULL
    OR total_slopes <> beginner_slopes + intermediate_slopes + difficult_slopes;

-- 2) only now the invariant as constraints
ALTER TABLE resorts ALTER COLUMN beginner_slopes     SET NOT NULL;
ALTER TABLE resorts ALTER COLUMN intermediate_slopes SET NOT NULL;
ALTER TABLE resorts ALTER COLUMN difficult_slopes    SET NOT NULL;
ALTER TABLE resorts ALTER COLUMN total_slopes        SET NOT NULL;
ALTER TABLE resorts ADD CONSTRAINT chk_resorts_slope_counts_nonnegative
    CHECK (beginner_slopes >= 0 AND intermediate_slopes >= 0 AND difficult_slopes >= 0);
ALTER TABLE resorts ADD CONSTRAINT chk_resorts_total_slopes_coherent
    CHECK (total_slopes = beginner_slopes + intermediate_slopes + difficult_slopes);
```

The `NULL→0` backfill pins down exactly today's scoring semantics (`nullToZero`,
`Resort.java:169-171`) — ranking behavior does not change by a bit; what changes is that missing
data becomes an explicit zero instead of a silent mask.

### 4.3 Thin API / routes: parse → aggregate method → error mapping

The admin path (`AdminResortController`) is already thin; the change is limited to delegation and
mapping the new error following the existing `DuplicateExternalIdException` pattern
(`AdminResortController.java:54-59`):

```java
// ResortService.applyManagedFields — after the refactor
private void applyManagedFields(Resort resort, ResortForm form) {
    resort.setName(form.getName());
    resort.setCountry(form.getCountry());
    resort.setHighestPoint(form.getHighestPoint());
    resort.setTotalLifts(form.getTotalLifts());
    resort.setExternalId(form.getExternalId());
    resort.applySlopeCounts(new SlopeCounts(          // the guardian; the manual sum disappears
            form.getBeginnerSlopes(), form.getIntermediateSlopes(), form.getDifficultSlopes()));
}

// AdminResortController.create/update — an extra catch next to DuplicateExternalIdException
} catch (IllegalSlopeCountsException ex) {
    bindingResult.rejectValue("beginnerSlopes", "slopeCounts.illegal", ex.getMessage());
    model.addAttribute("formAction", ...);
    return "admin/resorts/form";                      // 200-with-field-error, operation stopped
}
```

Bean Validation on the form (`ResortForm.java:36-46`) stays as first-line UX; the difference is
that after the refactor it is no longer the **only** guardian — the aggregate rejects bad counts
regardless of which route they arrived by. The JS preview (`form.html:119-182`) stays as cosmetics;
its comment should note that the truth belongs to `SlopeCounts.toMix()` (optional follow-up:
preview via an HTMX fragment instead of a copied algorithm — out of scope for this refactor).

The seed/resync path:

```java
// ResortSeedLoader.toResort — after the refactor
SlopeCounts counts = SlopeCounts.fromDataset(
        parseInt(record, "Beginner slopes"),
        parseInt(record, "Intermediate slopes"),
        parseInt(record, "Difficult slopes"),
        parseInt(record, "Total slopes"));     // the CSV total = a cross-check, not data
Resort resort = /* builder without the difficulty-fact fields */;
resort.applySlopeCounts(counts);

// ResortSeedLoader.copyFacts — after the refactor
existing.applySlopeCounts(incoming.slopeCounts());   // 4 setters disappear, total always derived
```

An incoherent CSV row = exception = rollback of the whole seed = the application does not start
with a broken catalog. Exactly the same policy the loader already applies to format errors
(`ResortSeedLoader.java:235-240`).

## STEP 5 — Before/after, phase plan, tests, names

### 5.1 Before → after for every current home of the rule

| Place (today) | Before | After |
|---|---|---|
| `ResortService.java:71-73` | manual sum of three form getters | `resort.applySlopeCounts(new SlopeCounts(...))` — only the VO knows the sum |
| `ResortSeedLoader.java:175` | CSV `totalSlopes` stored as-is | the CSV total is only a cross-check in `SlopeCounts.fromDataset`; divergence ⇒ `IncoherentResortFactsException`, seed rollback |
| `ResortSeedLoader.java:126-127` (`copyFacts`) | 4 setters copy counts and total verbatim | `existing.applySlopeCounts(incoming.slopeCounts())` — copying incoherence no longer compiles |
| `Resort.java:126-167` (`getDifficultyMix`) | algorithm in the entity, javadoc admits divergence from `totalSlopes` | delegates to `SlopeCounts.toMix()`; the javadoc stops describing violability, because divergence is unrepresentable |
| `Resort.java:25-29` (class-level `@Setter`/`@Builder`) | anyone can mutate difficulty facts individually | fact setters sealed (`AccessLevel.NONE`); the only mutation: `applySlopeCounts` |
| `Resort.java:169-171` (`nullToZero`) | null silently masked on every read | after V6 the columns are `NOT NULL`; the mask is needed only in `slopeCounts()` until the backfill, then dead |
| `V2__create_resorts.sql:13-16` | nullable INTEGER without CHECKs | `V6__resort_fact_constraints.sql`: backfill → `NOT NULL` → `chk_resorts_slope_counts_nonnegative` + `chk_resorts_total_slopes_coherent` |
| `ResortForm.java:36-46` | the only non-negativity validation in the system | stays as UX; the guardian is the `SlopeCounts` constructor |
| `AdminResortController.java:42-63` | maps only `DuplicateExternalIdException` | additionally `IllegalSlopeCountsException` → field error on the form |
| `form.html:119-182` (JS preview) | a copy of the algorithm "mirrors Resort.getDifficultyMix()" | functionally unchanged; the comment points to `SlopeCounts.toMix()` as the source of truth |
| `list.html:58` / `detail.html:51-52` / `RecommendationService.java:87` | the displayed total can contradict the mix badges next to it | no code change — coherence guaranteed at the source |

### 5.2 Phase plan (branch `refactor/<issue-id>-resort-fact-coherence`, one change per the git-workflow rules)

The project has a test-first discipline and a working runner (JUnit 5, tiers in `test-plan.md`
§4–§6, the PIT gate); phases 1–4 go **test-first** (red → green → refactor). Phase 5 is
wiring/regression.

| Phase | Scope | Test-first? | Verification point |
|---|---|---|---|
| 1 | `SlopeCounts` + `IllegalSlopeCountsException` + `IncoherentResortFactsException`; move largest-remainder out of the entity; `getDifficultyMix()` delegates | **YES** — new `SlopeCountsTests` (recipe §6.1); the existing `ResortDifficultyMixTests` stay green as the regression harness | `./gradlew test --tests "com.nextslope.resort.*"` |
| 2 | `Resort.applySlopeCounts` + sealing the fact setters/builder; `ResortService.applyManagedFields` delegates | **YES** — extended `ResortServiceTests`: create/update always coherent; compilation guards the rest | as above + full `./gradlew test` |
| 3 | Seed/resync via `SlopeCounts.fromDataset` / `applySlopeCounts`; fail-fast on an incoherent row | **YES** — extended `ResortSeedLoaderTests`: incoherent row ⇒ exception and **nothing** persisted (atomicity); empty counts + filled total ⇒ exception | as above |
| 4 | Migration `V6__resort_fact_constraints.sql` (backfill → NOT NULL → CHECK) | **YES** — a dual-engine test per §6.3: a native INSERT violating the CHECK is rejected on H2 **and** Postgres (Testcontainers, `ResortRepositoryPostgresTests` pattern) | `./gradlew test` (H2 context + Testcontainers) |
| 5 | Map `IllegalSlopeCountsException` in `AdminResortController`; comment at the JS preview; full regression | partially — a controller test in `AdminResortControllerTests` | `./gradlew test` + `./gradlew pitest` + `./gradlew e2eTest` (the full CI gate) |

Notes: PIT is scoped to `com.nextslope.recommendation.*` — the refactor does not touch those
packages beyond the unchanged `getDifficultyMix()` contract, so the gate serves as a regression
sensor and needs no recalibration. Phase order is dependency-driven: V6 (phase 4) can land only
once the seed path (phase 3) has stopped producing rows that break the CHECK — otherwise a fresh
H2 database will not boot.

### 5.3 Test cases for the invariant

Legal operations/transitions:

1. `new SlopeCounts(60, 30, 10)` → `total()==100`, `toMix()==60/30/10`.
2. `new SlopeCounts(3, 3, 3)` → the mix sums to 100 (34/33/33 — largest remainder).
3. `new SlopeCounts(0, 0, 0)` → `total()==0`, mix 0/0/0 (a resort with no categorized slopes is legal).
4. An admin update changing the counts → `totalSlopes` recomputed in the same transaction; reading the list/detail/card shows a coherent (total, mix) pair.
5. A CSV row with `Total slopes == sum` → loaded; `fromDataset` passes.
6. A resync of a coherent row → facts overwritten, `active` untouched (the existing `copyFacts` contract, `ResortSeedLoader.java:109-113`), total derived.

Illegal operations (each one THROWS a named error and STOPS the operation):

7. `new SlopeCounts(-1, 5, 5)` → `IllegalSlopeCountsException`.
8. A CSV row with `Total slopes != sum` → `IncoherentResortFactsException`; the seed rolled back in full — assertion: the table is empty after the attempt (atomicity).
9. A CSV row with empty count cells and `Total slopes > 0` → `IncoherentResortFactsException` (today: a silent 0/0/0 mix in scoring).
10. An admin-form POST with a negative count bypassing Bean Validation → `IllegalSlopeCountsException` mapped to a field error; the entity unchanged.
11. A native `INSERT`/`UPDATE` with `total_slopes != sum` → violation of `chk_resorts_total_slopes_coherent` on both H2 and Postgres.
12. Contract regression: `getDifficultyMix()` on the aggregate after `applySlopeCounts` always sums to 100 (the existing `ResortDifficultyMixTests` — unchanged).

### 5.4 New "load-bearing" names to register

The project keeps no separate contract registry; the convention is the "Key Discoveries /
load-bearing" sections in a change's `plan.md` plus the rules register
`context/foundation/lessons.md`. To register when the change is planned:

- `SlopeCounts` — the VO, the only source of `total()` and `toMix()`; its constructor = the
  non-negativity precondition.
- `Resort.applySlopeCounts(SlopeCounts)` — the only legal mutation of the difficulty facts.
- `SlopeCounts.fromDataset(...)` — the dataset gateway; the CSV's "Total slopes" is a cross-check,
  not data.
- `IllegalSlopeCountsException`, `IncoherentResortFactsException` — named domain errors (fail-fast).
- `V6__resort_fact_constraints.sql` with the constraints `chk_resorts_slope_counts_nonnegative`,
  `chk_resorts_total_slopes_coherent` — the schema backstop.
- Proposed `lessons.md` entry: *"A derived fact must be derived on every write path — never
  accepted from input on one of them"* (exactly the pattern that failed between
  `ResortService.java:71-73` and `ResortSeedLoader.java:175`).

## Summary

NextSlope's most core and simultaneously weakest-enforced invariant is the coherence of a resort's
difficulty facts: `total_slopes` must equal the sum of the three slope counts from which
`DifficultyMix` is derived — the sole scoring input and the foundation of the truthful rationale.
Today only the admin path enforces the rule (`ResortService.java:71-73`), while seed and resync
write the CSV value with no check at all (`ResortSeedLoader.java:175`, `:127`), the entity has open
setters, the schema has zero CHECKs, and the entity's javadoc openly admits the values "can
differ". The plan makes `Resort` a true aggregate with the `SlopeCounts` value object as the single
guardian: all three write paths go through `applySlopeCounts`, an incoherent dataset row throws
`IncoherentResortFactsException` and atomically rolls back the entire seed, and migration V6 closes
the JVM-bypassing paths with NOT NULL + CHECK constraints. Product behavior (ranking, mix,
determinism) does not change by a bit — what changes is that incoherence stops being representable.
The refactor proceeds in five test-first phases on the existing runner, with a dual-engine
migration proof and the full CI gate (`test` + `pitest` + `e2eTest`).
