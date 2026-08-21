---
title: NextSlope — The Leaking Resort-Dataset Contract: an Anti-Corruption Layer Plan
created: 2026-08-04
type: refactor-plan
---

# Leaking dependency #1 and the ACL design — refactor plan

> PLAN, not implementation. Every `file:line` citation was verified by reading the file directly
> on the day this document was created. Sibling documents: `context/domain/01-domain-distillation.md`
> (domain map) and `context/domain/02-invariant-aggregate-refactor.md` (guardian aggregate for the
> difficulty facts). This document discovers the leaking external dependencies, picks the worst
> one, and designs an anti-corruption layer (ACL) for it: value object + narrow port + adapter.

## STEP 0 — Context

**Source documents.** `context/foundation/prd.md` (vision, guardrails, FRs, Business Logic),
`context/foundation/roadmap.md` (slices, parking lot, open questions), `context/foundation/tech-stack.md`
(single-tier stack), `context/foundation/shape-notes.md` (shaping decisions), `AGENTS.md`
(hard rules: single-tier, Flyway forward-only, portable DDL).

**Replaceability declarations found in the documents** (key input to STEP 2c):

- `context/foundation/shape-notes.md:51-52` — *"data source: static CSV/JSON seed file delivered
  with v1; … whether the seed was hand-typed or one-off scraped is a dev-time choice, not a
  product FR"* — **the format and origin of the data source is a swappable dev-time detail**, not
  a product trait.
- `context/foundation/roadmap.md:216` — *"The loader mechanism and the CSV's shipped (classpath)
  location are S-03 plan-level decisions."* — the loading mechanism is meant to be a local,
  plan-level decision, i.e. isolated.
- `context/foundation/roadmap.md:224` — *"The full worldwide dataset is ready behind the subset;
  **expanding is a loader-filter change** plus a new filtering/pagination slice."* — the roadmap
  explicitly promises that swapping/expanding the dataset touches **only the loader**.
- `context/foundation/roadmap.md:186` — S-09 (engine tuning) is to happen *"against the real
  expanded dataset"* — the dataset swap is a planned, real event, not a hypothesis.
- `context/foundation/prd.md:188` — the source of truth for resort facts is an open question
  resolved *"dev-time, before deployment"* (hand-typed / scrape / compiled) — another signal the
  upstream is meant to be replaceable.

**Stack and external dependencies** (manifest: `build.gradle:22-51`):

| Dependency | Scope | Layers that "know" it today |
|---|---|---|
| Spring Boot starters (webmvc, data-jpa, security, thymeleaf, validation, flyway, actuator) | `implementation` | framework — all layers by definition |
| `org.apache.commons:commons-csv:1.14.1` (`build.gradle:31`) | `implementation` | imported in 1 file, but **the dataset contract it parses lives in 4 layers** (STEP 1) |
| `org.thymeleaf.extras:thymeleaf-extras-springsecurity6` (`build.gradle:30`) | `implementation` | UI templates |
| Lombok (`build.gradle:32,38,48,50`) | compile-only | everywhere; no runtime trace (no `implementation`/`runtimeOnly` declaration) |
| H2 / Postgres / flyway-database-postgresql (`build.gradle:35-37`) | runtime-only | behind JPA/Flyway; code never imports the drivers |
| Playwright 1.61.0 (`build.gradle:78`) | `e2eTestImplementation` only | isolated source set; the manifest comment says it outright: *"never leaks onto the unit-test classpath"* (`build.gradle:77`) |
| Bootstrap 5 + HTMX (CDN) | templates | deliberate architectural decision (`tech-stack.md:49-57`) |

**Code layers:** Thymeleaf templates (`src/main/resources/templates/`) → controllers
(`com.nextslope.web`) → domain services (`com.nextslope.{resort,profile,visited,user,recommendation}`)
→ persistence (JPA entities + Flyway `src/main/resources/db/migration/`) → data resources
(`src/main/resources/data/`).

## STEP 1 — IDENTIFY the leaking dependencies

Candidate inventory with the full list of files that "know" each dependency today.

### L1. The resort-dataset contract (wire: CSV, parser: commons-csv) — **leaks through 4 layers**

The external dependency here is not commons-csv itself (one file imports it) but **the upstream
resort dataset** — a foreign data model with its own vocabulary (headers `ID`, `Resort`, `Price`,
`Season`, `Child friendly`…), its own semantics (`Yes`/`No` as booleans, blank cells, the numeric
`ID` key) and its own format (CSV, `src/main/resources/data/resorts-Europe-subset.csv`,
151 lines = header + 150 rows; header verified:
`ID,Resort,Latitude,Longitude,Country,Continent,Price,Season,…`). That foreign model has leaked into:

**Persistence layer — the schema mirrors the CSV:**
- `src/main/resources/db/migration/V2__create_resorts.sql:3` — `external_id BIGINT` — the
  assumption that the upstream key is numeric (because the CSV's `ID` column parses to a long);
- `V2__create_resorts.sql:6-27` — 22 fact columns, sixteen of which exist only because they exist
  in the CSV: `continent`, `latitude`, `longitude`, `price`, `season`, `lowest_point`,
  `longest_run`, `snow_cannons`, `surface_lifts`, `chair_lifts`, `gondola_lifts`, `lift_capacity`,
  `child_friendly`, `snowparks`, `nightskiing`, `summer_skiing` (the remaining six in that range —
  `highest_point`, the three slope counts, `total_slopes`, `total_lifts` — are the ones business
  rules actually consume; the PRD requires six facts: `prd.md:150`);
- `V2__create_resorts.sql:31` — `UNIQUE (external_id)` — the uniqueness of a foreign system's key
  burned into the schema.

**Domain layer (entity + service + repository + exception):**
- `src/main/java/com/nextslope/resort/Resort.java:36-37` (`externalId`) and `:39-109` — the entity
  reproduces all 24 CSV fact columns 1:1, including 16 fields no business rule consumes (the
  engine reads only `country`, the three slope counts and the card fields —
  `RecommendationService.java:60-90`);
- `src/main/java/com/nextslope/resort/ResortSeedLoader.java:13-15` (commons-csv imports), `:31`
  (classpath location), `:162-186` (column names), `:195-197` (`Yes`/`No` semantics), `:199-233`
  (blank cell → `null`), `:235-240` (fail-fast on a malformed cell), `:77-107` (resync policy
  keyed on `external_id`), `:114-139` (`copyFacts` — a hand-written, 24-line mirror of the CSV
  columns);
- `src/main/java/com/nextslope/resort/ResortService.java:76-85` (uniqueness of the upstream key),
  `:98-110` (matching the `UQ_RESORTS_EXTERNAL_ID` constraint name inside DB-engine messages),
  `:70` and `:122` (copying the key from/to the form);
- `src/main/java/com/nextslope/resort/ResortRepository.java:16` (`findByExternalId`);
- `src/main/java/com/nextslope/resort/DuplicateExternalIdException.java:8-10` — a domain exception
  named after a foreign dataset's key.

**Web layer (the admin form's wire contract):**
- `src/main/java/com/nextslope/resort/ResortForm.java:48` — `externalId` is a field of the form
  contract; the javadoc at `:12-13` sanctions it as the exception to the "PRD six facts";
- `src/main/java/com/nextslope/web/AdminResortController.java:54-57` and `:89-92` — mapping the
  upstream-key duplicate error onto a form field.

**UI layer (templates):**
- `src/main/resources/templates/admin/resorts/form.html:84-91` — the "External ID (optional)"
  input: the upstream dataset's private key is editable in the browser;
- `src/main/resources/templates/resorts/detail.html:14` (continent), `:23-26` (Season, Price),
  `:29-34` (Lowest point, Longest run, Snow cannons), `:68-73` and `:76-77` (lift types, Lift
  capacity), `:88-95` (amenities) — the view renders raw dataset columns straight off the entity
  (`ResortController.java:37-39` puts the entity into the model), and units are **guessed inside
  the template**: "Longest run (km)", "Lift capacity (per hour)", "Price" with no currency —
  because the dataset doesn't define them and no domain layer has translated them.

### L2. Spring Security — wide imports, but the domain boundary HOLDS

`org.springframework.security` is imported by 13 files: controllers (`AuthController.java:4-9`,
`AccountController.java:3-6`, `ProfileController.java:4,11`, `VisitedController.java:4-5`,
`ResortController.java:4-5`, `RecommendController.java:3-4`), the user package
(`AppUserDetailsService.java:3-6`, `CurrentUserService.java:4`, `UserRegistrationService.java:3`),
config (`SecurityConfig.java:7-14`, `StaleAuthenticatedSessionFilter.java:5-8`,
`AdminBootstrap.java:9`, `DevAdminBootstrap.java:10`) plus templates via
`thymeleaf-extras-springsecurity6` (`layout.html:19-24`, `index.html:25-37`). It looks alarming by
file count, but almost all of that knowledge sits in layers that ARE framework adapters
(web + config), and there is a single crossing into the domain:
`CurrentUserService.requireUserId(UserDetails)` (`CurrentUserService.java:22`) converts the
principal into a `Long userId`, and **domain services know only `userId`**
(`RecommendationService.java:48`, profile/visited likewise — confirmed in
`01-domain-distillation.md`, privacy section). This is a de facto existing mini-ACL.

### L3. `jakarta.persistence` — in the entities of every domain

`User.java:5-12`, `Resort.java:5-12`, `PreferenceProfile.java:7-19`, `VisitedResort.java:5-10`.
A standard, deliberate trade-off of the "JPA entity = domain model" style; no document declares
the ORM replaceable.

### L4–L6. Lombok / DB drivers / Playwright / Bootstrap+HTMX

Lombok is compile-only (`build.gradle:32,38`); the H2/Postgres drivers are runtime-only behind JPA
(`build.gradle:35-37`); Playwright is locked in a dedicated source set with the no-leak intent
written down explicitly (`build.gradle:60-79`); Bootstrap/HTMX is a declared architectural
decision (`tech-stack.md:49-57`). No leak to investigate.

## STEP 2 — CLASSIFY and pick #1

| Candidate | (a) layers / files affected | (b) risk/cost of a swap today | (c) do the documents declare replaceability? | Verdict |
|---|---|---|---|---|
| **L1 dataset contract** | **4 layers, 10 `src/main` files + schema + data file** (exactly the files enumerated in STEP 1 — each one would have to change on a dataset swap; the two catalog list templates are excluded because they render only the PRD six facts and survive any upstream unchanged) | High — swapping the source (CSV→JSON, subset→worldwide, another provider) touches the entity, migration, service, form and templates; and the swap is **planned** (`roadmap.md:186,224`) | **YES — three times over** (`shape-notes.md:52`, `roadmap.md:216`, `roadmap.md:224`); the code does not keep the promise | **CHOSEN #1** |
| L2 Spring Security | 13 files + 2 templates, but only adapter layers (web/config) + 1 conversion point | Replacing the security framework is unrealistic and unintended — a deliberate stack choice (`tech-stack.md:33`) | NO — the opposite: the documents pin it down | boundary holds; nothing to do beyond guarding the `CurrentUserService` pattern |
| L3 JPA in entities | 4 domain packages | ORM swap unintended; DB portability is handled by migration discipline (AGENTS.md "Portable DDL only") | NO | accepted trade-off |
| L4–L6 | — | — | declarations exist and the code KEEPS them (Playwright: `build.gradle:77`) | positive patterns |

**Why L1.** It is the only candidate with an intent-vs-code mismatch: the documents declare three
times that the resort data source is a swappable detail (*"dev-time choice"*, *"plan-level
decision"*, *"expanding is a loader-filter change"*), while the code has smeared its contract
across all four layers — from `BIGINT external_id` in the schema (`V2:3`) to a currency-less
"Price" in a template (`detail.html:25-26`). The measure of the leak is not the number of
`org.apache.commons.csv` imports (one file) but the number of places you must touch to swap the
upstream — and that number is the highest in the repo. The risk has a due date, it is not
theoretical: S-09 (engine tuning) waits for the "real expanded dataset" (`roadmap.md:186`), so the
unguarded seed/resync path will be exercised again — the same risk that
`02-invariant-aggregate-refactor.md` identified from the invariants side. Spring Security loses
despite the higher file count because its leak stops at the adapter layers; an ACL around the
framework would be a fight against the Spring MVC idiom with no product payoff.

## STEP 3 — DIAGNOSIS

### 3.1 Duplicated reconstruction of the dataset shape (4 hand-maintained mirrors)

The same set of 24 columns is maintained by hand in four places:

**(1) CSV header → entity builder mapping** (`ResortSeedLoader.java:160-189`):

```161:167:src/main/java/com/nextslope/resort/ResortSeedLoader.java
		return Resort.builder()
				.externalId(parseLong(record, "ID"))
				.name(trim(record.get("Resort")))
				.country(trim(record.get("Country")))
				.continent(trim(record.get("Continent")))
				.latitude(parseDouble(record, "Latitude"))
				.longitude(parseDouble(record, "Longitude"))
```

**(2) Field copying on resync — a second mirror of the same 24 columns**
(`ResortSeedLoader.java:114-139`):

```114:121:src/main/java/com/nextslope/resort/ResortSeedLoader.java
	private static void copyFacts(Resort incoming, Resort existing) {
		existing.setName(incoming.getName());
		existing.setCountry(incoming.getCountry());
		existing.setContinent(incoming.getContinent());
		existing.setLatitude(incoming.getLatitude());
		existing.setLongitude(incoming.getLongitude());
		existing.setPrice(incoming.getPrice());
		existing.setSeason(incoming.getSeason());
```

**(3) The `Resort` entity — a third mirror** (`Resort.java:30-124`; 16 of these fields are consumed
by no business rule — the engine reads only `country` and the slope counts:
`RecommendationService.java:60-71`).

**(4) The `V2` migration — a fourth mirror** (`V2__create_resorts.sql:2-32`), made permanent by the
forward-only policy.

A new upstream column = 4 coordinated edits. A key-type change (e.g. a dataset with string keys)
= migration + entity + form + service + template.

### 3.2 A foreign system's key inside wire contracts and the UI

`external_id` — i.e. the dataset's `ID` column — is today: a column with a UNIQUE constraint
(`V2:3,31`), an entity field (`Resort.java:36-37`), a repository method (`ResortRepository.java:16`),
a service rule that pattern-matches the constraint name inside H2/Postgres messages
(`ResortService.java:76-110`), a named exception (`DuplicateExternalIdException.java:8-10`),
a form-contract field (`ResortForm.java:48`), controller error handling
(`AdminResortController.java:54-57,89-92`) and **an editable input in the browser**
(`form.html:84-91`). Eight places know the upstream's private identifier; none of them knows what
it actually is — that knowledge ("it's the CSV's `ID` column parsed as a long") lives only in
`ResortSeedLoader.java:162`. Counted as files rather than places, the token appears in nine
`src/main` files: those eight plus the loader that defines it.

### 3.3 The UI receives the raw dataset object, not domain data

`ResortController.detail` puts the entity straight into the model (`ResortController.java:37-39`),
and `detail.html` renders 22 of the entity's 24 dataset columns (everything except `latitude` and
`longitude`) — 14 of them facts no business rule consumes — inventing units on faith:
"Longest run (km)" (`detail.html:31-32`), "Lift capacity (per hour)" (`:76-77`), "Price" with no
unit at all (`:25-26`), "Season" as a bare string (`:23-24`). Contrast: the recommendation layer
already has the right pattern — `ResortCard` is an explicit, documented projection (*"view-ready
facts … never the JPA entity"*, `ResortCard.java:5-27`), consumed by the results fragment
(`resorts/list.html:115-117`). The leak therefore concerns the catalog (list/detail), not the
recommendation cards.

### 3.4 The intent-vs-code mismatch (quotes)

- Declaration: *"expanding is a loader-filter change"* (`roadmap.md:224`). Code: an expansion with
  rows containing blank cells or different vocabulary flows through `copyFacts`/`toResort` and
  lands in the entity + schema with no boundary; and a format swap (CSV→JSON, allowed by
  `shape-notes.md:52`) requires rewriting a class in which parsing is fused with the **business
  policy of resync** (seed only into an empty table `ResortSeedLoader.java:61-64`, upsert by key
  with no deletes and no touching of `active` `:35-40,109-113`) — i.e. swapping the mechanism
  forces touching rules that should not be swapped.
- Declaration: the loader mechanism is an S-03 "plan-level" decision (`roadmap.md:216`). Code:
  dataset knowledge has escaped the loader into the entity, schema, service, form and templates
  (3.1–3.3).

## STEP 4 — ACL DESIGN

Principle: **the only place in `src/main` that knows the shape of the upstream dataset (headers,
`Yes`/`No`, blank cells, the numeric key, file format, file path) is the package
`com.nextslope.resort.dataset`**. The rest of the code knows only the port and the domain VOs.

### 4.1 Domain value object: `ResortFacts` + `DatasetKey`

`ResortFacts` is the domain snapshot of one upstream row — the single place that knows how the
dataset's facts map onto the `Resort` aggregate (today that knowledge is duplicated between
`toResort` and `copyFacts`). It composes with the `SlopeCounts` designed in
`02-invariant-aggregate-refactor.md` (STEP 4 of that plan) — the `Total slopes` cross-check
becomes part of the ACL mapping.

```java
// com.nextslope.resort.dataset — the ACL package
/** Typed, opaque upstream key. Today: a long from the "ID" column. */
public record DatasetKey(long value) {
    public static DatasetKey of(long value) { return new DatasetKey(value); }
}

/** Domain snapshot of one dataset row. Construction = validation (fail-fast). */
public record ResortFacts(
        DatasetKey key,                    // required: resync upserts by it
        String name, String country, String continent,
        Double latitude, Double longitude,
        Integer price, String season,
        Integer highestPoint, Integer lowestPoint,
        SlopeCounts slopeCounts,           // VO from plan 02: counts + derived total
        Integer longestRun, Integer snowCannons,
        Integer surfaceLifts, Integer chairLifts, Integer gondolaLifts,
        Integer totalLifts, Integer liftCapacity,
        boolean childFriendly, boolean snowparks,
        boolean nightskiing, boolean summerSkiing) {

    /** The ONLY facts→entity mapping: replaces both the builder chain
     *  in toResort (ResortSeedLoader.java:160-189) and copyFacts (:114-139).
     *  Leaves identity (id, the existing row's externalId), audit columns and active
     *  untouched — preserving the copyFacts contract (ResortSeedLoader.java:109-113). */
    public void applyTo(Resort resort) {
        resort.setName(name);
        resort.setCountry(country);
        // ... remaining descriptive fields ...
        resort.applySlopeCounts(slopeCounts);   // from plan 02; until it lands: setters
    }

    /** Factory for a new entity (first seed): sets externalId from the key and active=true. */
    public Resort toNewResort() { ... }
}
```

### 4.2 Narrow port + adapter

```java
// com.nextslope.resort.dataset — the PORT (domain interface)
public interface ResortDatasetSource {
    /**
     * A full, immutable snapshot of the upstream catalog in domain shape.
     * Contract: fail-fast — any unparsable/incoherent row throws
     * MalformedDatasetException; never returns a partial snapshot.
     */
    List<ResortFacts> fetchCatalog();
}
```

```java
// com.nextslope.resort.dataset.csv — the ADAPTER (sole owner of commons-csv)
@Component
class CsvResortDatasetAdapter implements ResortDatasetSource {

    // ALL wire-format knowledge lives here:
    private static final String CSV_PATH = "data/resorts-Europe-subset.csv"; // from ResortSeedLoader.java:31
    private static final String COL_KEY = "ID";                              // from :162
    private static final String COL_NAME = "Resort";                         // from :163
    // ... remaining header constants from :163-186 ...

    @Override
    public List<ResortFacts> fetchCatalog() {
        // commons-csv: CSVFormat.builder().setHeader().setSkipHeaderRecord(true)
        // (moved from ResortSeedLoader.java:143-158) + the policies from 4.3
        // CSVRecord -> ResortFacts: headers, parseYesNo (:195-197),
        // blank cell -> null (:199-233), malformedCell -> MalformedDatasetException (:235-240),
        // SlopeCounts.fromDataset(beginner, intermediate, difficult, declaredTotal)  // plan 02
    }
}
```

The port's consumer — a slimmed-down `ResortSeedLoader` (remains an `ApplicationRunner`; loses all
format knowledge, keeps the business policy):

```java
// com.nextslope.resort — application service; knows ONLY the port and the repository
public class ResortSeedLoader implements ApplicationRunner {
    private final ResortRepository resortRepository;
    private final ResortDatasetSource datasetSource;     // port instead of CSV
    private final boolean resync;

    void seed()   { ... datasetSource.fetchCatalog() ... facts.toNewResort() ... }
    void resync() { ... index by DatasetKey ... facts.applyTo(existing) ... }  // copyFacts disappears
}
```

The resync policy (seed only into an empty table, upsert by key, never delete, never touch
`active` — `ResortSeedLoader.java:35-40,61-64,109-113`) stays in the service: it is a domain rule,
not a property of the format. Transaction boundaries unchanged (`:49-53`).

### 4.3 Contract decisions resolved via the library's documentation — encoded in the ACL

Open questions that depend on the commons-csv/dataset contract, resolved against the Apache
Commons CSV documentation (User Guide / package javadoc, verified 2026-08-04) and encoded **in the
adapter, not in the web layer**:

| Question | Resolution (source: commons-csv documentation) | Where to encode it |
|---|---|---|
| A BOM at the start of the file (a future dataset exported from Excel) — with `setHeader()` the BOM glues onto the `ID` header and breaks the lookup | The docs recommend wrapping the stream in `BOMInputStream` (Commons IO) before the parser; the current file has no BOM (verified: the first bytes are `ID,Resort,…`) | `CsvResortDatasetAdapter` — stream opening |
| A missing header column | `CSVRecord.get(String)` requires a mapped header; the docs recommend checking `isMapped(...)` before reading | the adapter validates the full header set once, before iterating; a missing one ⇒ `MalformedDatasetException` naming the column |
| Blank cell ≠ zero | the parser returns the cell value as-is; interpretation belongs to the consumer | the adapter maps `""`→absent fact; the policy "no counts + no total = 0/0/0, no counts + total>0 = coherence error" moves into `SlopeCounts.fromDataset` (plan 02) — a domain decision in the ACL, not scattered across `nullToZero` (`Resort.java:169-171`) |
| A duplicate `ID` key inside the file | the library does not deduplicate records — that is the consumer's responsibility | the adapter rejects a batch with a repeated `DatasetKey` (fail-fast); `uq_resorts_external_id` (`V2:31`) stays as the persistence backstop |
| The upstream key's type | today's dataset: numeric (`ID` parsed as a long — `ResortSeedLoader.java:162,199-209`) | the knowledge "key = long from the ID column" only in the adapter; the domain sees `DatasetKey`. The `external_id BIGINT` column (`V2:3`) stays (forward-only); a future string key = 1 migration + 1 adapter, zero changes in services/UI |

### 4.4 What the ACL deliberately leaves unchanged

- **The descriptive columns in the entity and schema** (price, season, amenities…): they are
  already product surface (`detail.html` renders them), and migrations are forward-only — the ACL
  does not remove data, it takes ownership of the knowledge of their origin and typing.
- **The "External ID" field on the admin form** (`ResortForm.java:48`, `form.html:84-91`): it stays
  functionally (it lets an admin link a manual entry to a dataset row so resync recognizes it);
  after the refactor its semantics ("the external dataset's key, swappable together with it") are
  documented in one place — on `DatasetKey`.
- **`ResortService`/`DuplicateExternalIdException`**: key uniqueness is a catalog persistence rule,
  not format knowledge — they stay.

## STEP 5 — Isolation proof + before/after

### 5.1 Swap scenarios — what they touch before and after

| Scenario (all declared in the documents) | Touches today | Touches after the ACL |
|---|---|---|
| Expansion 40 → ~500 worldwide resorts (`roadmap.md:224`, precondition of S-09 `roadmap.md:186`) | `data/*.csv` + the risk of silent incoherence via `copyFacts`/`toResort` across the entity's full width | the data file + possibly a filter in the adapter — **literally the "loader-filter change" the roadmap promises** |
| Format change CSV→JSON (`shape-notes.md:52` allows both) | a full rewrite of `ResortSeedLoader` — parsing is fused with the resync policy (`ResortSeedLoader.java:55-107` vs `:141-240` in one class) | a new `JsonResortDatasetAdapter implements ResortDatasetSource`; seed/resync service, entity, schema, form, templates — **zero changes** |
| Parser swap (commons-csv → another library) | only `ResortSeedLoader` (honestly: this leak is already contained today) | only `CsvResortDatasetAdapter` |
| A dataset with different column vocabulary / a string key | entity + the `V2` mirror + form + service + templates + loader | the adapter (header mapping) + possibly 1 key migration; port and domain unchanged |

### 5.2 Before/after for the duplicated sites

| Site (today) | Before | After |
|---|---|---|
| `ResortSeedLoader.java:160-189` (`toResort`) | mirror #1: 25 CSV header literals (the 24 facts plus the `ID` key) → entity builder, plus a 26th non-CSV value `.active(true)` (`:187`). Today the literals match the shipped header row with zero drift in either direction — an accident to be preserved deliberately | the adapter builds `ResortFacts`; the entity is created by `facts.toNewResort()` — headers exist in ONE file |
| `ResortSeedLoader.java:114-139` (`copyFacts`) | mirror #2: 24 setters copying entity→entity fields | `facts.applyTo(existing)` — the same mapping as on insert; the mirror disappears |
| `ResortSeedLoader.java:195-197` (`parseYesNo`) | `Yes`/`No` semantics inside a class holding business policy | a private detail of the adapter |
| `ResortSeedLoader.java:31` + `:143-158` | file path and parser configuration next to resync rules | parser construction only in the adapter; the service knows the port |
| `ResortSeedLoader.java:162` + `V2:3` + `ResortForm.java:48` + 6 other sites knowing `externalId` (nine `src/main` files in total) | each site separately "knows" the key is numeric and where it comes from | the key's origin and parsing — adapter only; its type — `DatasetKey` only; the remaining sites operate on the domain name |
| `Resort.java:169-171` (`nullToZero`) + `ResortSeedLoader.java:199-233` | the "missing data = 0" policy scattered between the entity and the loader | the "absent fact" decision made once, in the adapter's mapping (`SlopeCounts.fromDataset` — consistent with plan 02) |

### 5.3 The UI receives ready domain data

The target pattern already exists in the repo: the recommendation fragment renders `ResortCard` —
an explicit, documented projection (*"never the JPA entity"*, `ResortCard.java:5-27`, consumed at
`resorts/list.html:115-117`). After the refactor, every value that `detail.html`/`list.html` render off the
entity has passed through the validating `ResortFacts` mapping (types, count coherence, an explicit
decision about absences) — the UI can no longer receive an untranslated upstream value, because
nothing feeds the entity except the ACL and the admin form. An optional follow-up outside this
refactor's scope: a `ResortDetailView` projection for the catalog, closing the units question
("Price" with a currency, or dropped) in the style of `ResortCard`.

## STEP 6 — Verification and plan

### 6.1 Success criterion (grep)

**Wire-format containment** — after the refactor all three queries below return only files under
`src/main/java/com/nextslope/resort/dataset/**` (plus the data file itself and the adapter tests):

```
rg -l "org\.apache\.commons\.csv" src/main
rg -l "resorts-Europe-subset" src/main
rg -l '"(Beginner|Intermediate|Difficult|Total) slopes"|"Child friendly"|"Snowparks"|parseYesNo' src/main/java
```

Caveat, stated honestly: **each of these already returns exactly one file today**
(`ResortSeedLoader.java`), because parser and path knowledge is the one part of the leak that is
already contained (see 5.1). They are necessary but not sufficient — they prove the refactor did
not scatter format knowledge, and nothing more. The criteria below are the ones that can actually
fail before the refactor and pass after it, so they are the real gate:

```
# the entity's difficulty facts are no longer mutated field-by-field from the loader
rg -c 'existing\.set' src/main/java/com/nextslope/resort/ResortSeedLoader.java   # today 24 → target 0

# entity construction happens only inside the ACL (+ the admin create path in ResortService).
# The \b anchors matter: without them the pattern also matches VisitedResort.builder()
# (VisitedResortService.java:42) — a false positive in a different aggregate.
rg -l '\bResort\.builder\(\)|\bnew Resort\(\)' src/main/java   # today ResortSeedLoader:161 +
                                                               # ResortService:34 → target
                                                               # dataset/** + ResortService

# the upstream key is parsed/typed in one place
rg -l 'getExternalId|setExternalId' src/main/java   # today 2 files (loader + service)
                                                    # → target dataset/** + ResortService only
```

Current vs target state:

| File | Knows the dataset today | After the refactor |
|---|---|---|
| `resort/ResortSeedLoader.java` | format + columns + key + policy | only the seed/resync policy, via the port |
| `resort/Resort.java` | column mirror + javadoc admitting drift | columns stay (product surface); fed exclusively through `ResortFacts`/the form |
| `db/migration/V2__create_resorts.sql` | column mirror + numeric key | unchanged (forward-only); no new knowledge added |
| `resort/ResortForm.java`, `web/AdminResortController.java`, `templates/admin/resorts/form.html` | the upstream key as a wire/UI field | functionally unchanged; the key's semantics documented on `DatasetKey` |
| `resort/ResortService.java`, `resort/ResortRepository.java`, `resort/DuplicateExternalIdException.java` | key uniqueness | unchanged (persistence rule) |
| `templates/resorts/detail.html` | renders 22 raw dataset columns | no code changes; values guaranteed by the ACL at the source |
| **NEW:** `resort/dataset/{ResortFacts,DatasetKey,ResortDatasetSource,MalformedDatasetException}.java`, `resort/dataset/csv/CsvResortDatasetAdapter.java` | — | **the sole owners of dataset knowledge** |
| Tests: `ResortSeedLoaderTests` (+ new adapter tests) | seed/resync scenarios | split: adapter contract vs service policy (on a stubbed port) |

Note on counting: this table groups files by treatment and includes the schema row, which STEP 2
counts separately from its 10 `src/main` files. Two files render dataset-sourced values but are
deliberately absent from both counts — `resorts/list.html` and `admin/resorts/list.html` show only
the PRD six facts (`name`, `country`, top lift height, total slopes, total lifts, difficulty mix),
so no dataset swap can force a change in them.

### 6.2 Phase plan (project convention: branch `refactor/<issue-id>-resort-dataset-acl`, commit per phase, SHAs into the change plan's `## Progress` rows)

| Phase | Scope | Test-first? | Verification point |
|---|---|---|---|
| 1 | `DatasetKey`, `ResortFacts` (+`applyTo`/`toNewResort`), the `ResortDatasetSource` port, `MalformedDatasetException` — pure domain, no Spring | **YES** — new `ResortFactsTests` (mapping, absence decisions) | `./gradlew test --tests "com.nextslope.resort.*"` |
| 2 | `CsvResortDatasetAdapter`: parsing moved from `ResortSeedLoader.java:141-240` + the 4.3 policies (BOM, full header set, duplicate keys) | **YES** — golden test against the real classpath CSV (150 rows), asserting the zero header-drift baseline, + fail-fast tests on crafted files | same as above |
| 3 | Slimming `ResortSeedLoader` down to the port; `copyFacts`/`toResort` disappear; resync policy untouched | **YES** — existing `ResortSeedLoaderTests` stay green as the regression harness, plus new variants on a stubbed port | full `./gradlew test` |
| 4 | Sweep + the 6.1 grep criterion + full regression | — | `./gradlew test` + `./gradlew pitest` + `./gradlew e2eTest` (full CI gate) |

Dependencies and ordering relative to plan 02: phases 1–2 of this plan can consume `SlopeCounts`
from plan 02 if that one lands first; otherwise `ResortFacts` temporarily carries the three counts
as plain fields and delegates to setters — both plans are designed to compose (the `Total slopes`
cross-check is one place: the ACL mapping). PIT stays scoped to
`com.nextslope.recommendation.*` — the refactor does not change engine behavior, so the gate acts
as a regression sensor.

## Summary

NextSlope's worst-leaking external dependency is not any library found in the imports but **the
upstream resort dataset's contract**, of which commons-csv is merely the mechanical carrier: its
24 columns are hand-mirrored in four places (the parser `ResortSeedLoader.java:160-189`, the
copier `:114-139`, the entity `Resort.java:30-124`, the schema `V2__create_resorts.sql:2-32`), its
private `ID` key lives in nine `src/main` files from a DB constraint to an editable browser field
(`form.html:84-91`), and the UI renders 22 of its 24 raw columns while guessing the units
(`detail.html:25-32,76-77`). The documents declare this source replaceable three times over —
*"dev-time choice"* (`shape-notes.md:52`), *"plan-level decision"* (`roadmap.md:216`), *"expanding
is a loader-filter change"* (`roadmap.md:224`) — and the code does not keep that promise, even
though the dataset swap is a planned precondition of engine tuning (S-09, `roadmap.md:186`). The
plan builds an ACL in the `com.nextslope.resort.dataset` package: the `ResortFacts` +
`DatasetKey` value objects as the single place that knows the dataset's shape and mapping, the
narrow `ResortDatasetSource` port, and the `CsvResortDatasetAdapter`, which alone knows
commons-csv, the headers, the `Yes`/`No` semantics, blank cells and the BOM (decisions resolved
via the library's documentation and encoded in the adapter, not in the web layer). After the
refactor, swapping the format or the dataset touches only the adapter — proven by the scenario
table in 5.1 and the grep criterion in 6.1 — while the seed/resync business policy, the schema,
the admin form and the templates remain untouched. Four test-first phases on the existing runner
close it out under the full CI gate; the plan composes with sibling plan 02 (`SlopeCounts` becomes
part of the ACL mapping).
