---
title: NextSlope — Domain Distillation
created: 2026-08-04
type: domain-distillation
---

# NextSlope — Domain Distillation

A map of the business domain distilled from the source documents and verified against the code.
This is a MAP, not a redesign: every claim cites a file and line that was actually read.

## Step 0 — Project context

**Source documents found and used** (in order of authority):

| Document | Role |
|---|---|
| `context/foundation/prd.md` | Product requirements — vision, success criteria, guardrails, US-01…US-03, FR-001…FR-013, NFRs, business logic, access control, non-goals |
| `context/foundation/roadmap.md` | Slice narrative F-01, S-01…S-09 with per-slice risks and resolved open questions |
| `context/foundation/tech-stack.md` | Stack rationale (Spring Boot + Thymeleaf, single tier) |
| `project-idea.md` | Original raw idea (pre-PRD) |
| `context/archive/*` | Change history of all shipped slices (extended narrative) |
| `AGENTS.md` | Repository conventions, hard rules, guardrail restatement |

No missing-requirements limitation applies: the PRD is complete and versioned, so the code is
analyzed *against* it rather than reverse-engineered.

**Stack and code layout.** Spring Boot 4 + Thymeleaf server-rendered UI (Bootstrap 5 + HTMX via
CDN, no JS tier — `context/foundation/tech-stack.md:42-57`). Business logic lives in
`src/main/java/com/nextslope/` in one package per domain:

- `user/` — account, registration, roles, deletion
- `profile/` — preference profile (the four preference axes)
- `resort/` — resort catalog, difficulty mix derivation, admin curation, CSV seed
- `visited/` — per-user visited marks
- `recommendation/` — the recommendation engine (filters, scorer, rationale, result)
- `web/` — thin `@Controller` layer (principal-scoped, HTMX fragments)
- `config/` — Spring Security, admin bootstrap
- `src/main/resources/db/migration/` — Flyway schema (V1–V5), the persisted shape of the domain

## Step 1 — Ubiquitous Language

Terms extracted from the documents AND the code. "Code home" cites where the term lives; terms
that exist only on one side are flagged explicitly — those asymmetries feed Step 4.

### Core recommendation vocabulary

**Recommendation (three ranked picks)**
- Definition: the product's output — exactly three resorts ordered best-fit-first, computed from profile + visited list + resort facts.
- Source: `context/foundation/prd.md:122` — "FR-008: User can request a recommendation and receive exactly three ranked resort suggestions based on their profile and visited list."
- Code home: `src/main/java/com/nextslope/recommendation/RecommendationService.java:48-78` (`recommend`, `recommendFor`).

**Hard filter**
- Definition: a non-negotiable preference that drops a resort from consideration before scoring (region mismatch; visited when new-only).
- Source: `context/foundation/prd.md:154` — "Hard filters first: resorts that violate a non-negotiable preference … are dropped from consideration."
- Code home: `src/main/java/com/nextslope/recommendation/RecommendationService.java:56-64` (visited-set + region/visited stream filters).

**Weighted alignment (soft scoring)**
- Definition: scoring surviving candidates on how well soft preference axes align, blended by weights.
- Source: `context/foundation/prd.md:154` — "The remaining candidates are then scored on a weighted alignment across the soft preference axes."
- Code home: `src/main/java/com/nextslope/recommendation/WeightedDistanceScorer.java:25-38`; weights in `src/main/java/com/nextslope/recommendation/ScoringConfig.java:17-28`.

**Rationale ("why this matched you")**
- Definition: the one-line, truthful explanation attached to each recommended resort, tracing back to the user's stated preferences.
- Source: `context/foundation/prd.md:54` — "The rationale must be truthful. The one-line 'why this matched you' line reflects the actual matching logic — not generic flavor text."
- Code home: `src/main/java/com/nextslope/recommendation/RationaleBuilder.java:23-56`; carried on `ResortCard.rationale` (`src/main/java/com/nextslope/recommendation/ResortCard.java:18-27`).

**Sparse result (explicit explanation)**
- Definition: when fewer than three viable candidates survive the hard filters, the product explains rather than padding or silently returning fewer.
- Source: `context/foundation/prd.md:53` — "Always three results, or an explicit explanation."
- Code home: `src/main/java/com/nextslope/recommendation/RecommendationResult.java:16-20` (`Kind.SPARSE`, `Kind.NO_PROFILE`); explanation text in `RecommendationService.java:93-101`.

**Determinism**
- Definition: same profile + visited list + resorts ⇒ same three resorts in the same order.
- Source: `context/foundation/prd.md:143` — "the recommendation result is deterministic — the same inputs produce the same three resorts in the same order."
- Code home: `src/main/java/com/nextslope/recommendation/RecommendationService.java:39-45` (total-order comparator: score desc, then country/name/id tie-break).

**Hardness index / hardness target** — CODE-ONLY TERM
- Definition: a scalar `(0.5·medium + hard)/100` derived from a resort's mix, compared against a per-experience-level target (0.20 / 0.45 / 0.70).
- Source (doc): ABSENT from `prd.md`; appears only in the roadmap's tuning follow-up, `context/foundation/roadmap.md:194` — "whether the shipped hardness-index scalar with targets 0.20 / 0.45 / 0.70 is the right model."
- Code home: `src/main/java/com/nextslope/recommendation/WeightedDistanceScorer.java:32-34`; `ScoringConfig.java:31-37`.

### Preference vocabulary

**Preference profile**
- Definition: the per-user record of the four preference axes; one per user; editable so re-runs need no re-typing.
- Source: `context/foundation/prd.md:108` — "FR-004: User can create and edit their preference profile (experience level, difficulty mix preference, location/region preference, novelty preference)."
- Code home: `src/main/java/com/nextslope/profile/PreferenceProfile.java:44-84` (entity); `ProfileSnapshot.java:17-32` (engine-facing view).

**Experience level**
- Definition: self-rated skiing experience (beginner / intermediate / advanced); soft-scored against resort hardness.
- Source: `context/foundation/prd.md:39` — "experience level, difficulty preference … location/region preference, and novelty preference."
- Code home: `src/main/java/com/nextslope/profile/ExperienceLevel.java:6-10`.

**Difficulty preference / difficulty band**
- Definition (doc): "difficulty preference (preferred easy/medium/hard slope mix)" — `context/foundation/prd.md:39`; US-01 exemplifies free percentages: "matches your preferred 60/30/10 difficulty mix" (`prd.md:70`).
- Definition (code): one of three preset bands, each a fixed triple — MOSTLY_EASY (60/30/10), BALANCED (34/33/33), MOSTLY_HARD (10/30/60).
- Code home: `src/main/java/com/nextslope/profile/DifficultyBand.java:9-13`. The preset-vs-percentages choice was an explicitly tracked unknown: `context/foundation/roadmap.md:105`.

**Region preference (doc: "location/region preference")**
- Definition: the geographic constraint on candidates; in code, a set of country names; empty set = "any region" (no filter).
- Source: `context/foundation/prd.md:39`; hard-filter role at `prd.md:154` ("sitting outside the chosen region").
- Code home: `src/main/java/com/nextslope/profile/PreferenceProfile.java:65-69` (`regionCountries` element collection); filter semantics `ProfileSnapshot.java:29-31`; vocabulary validation `PreferenceProfileService.java:83-96`.

**Novelty preference**
- Definition: whether already-visited resorts are eligible at all (`new-only`) or allowed (`revisit-okay`).
- Source: `context/foundation/prd.md:52` — "A user with novelty preference set to 'new-only' never sees a resort they have marked visited in their top three."
- Code home: `src/main/java/com/nextslope/profile/NoveltyPreference.java:7-10`; enforced in `RecommendationService.java:56-63`.

### Resort vocabulary

**Resort / resort facts**
- Definition: the catalog entry with objective facts — "name, location, top lift height, number of slopes, number of lifts, and the easy/medium/hard slope difficulty mix expressed as percentages summing to 100" (`context/foundation/prd.md:150`).
- Code home: `src/main/java/com/nextslope/resort/Resort.java:30-124` (entity; note the code stores far more columns — price, season, lift types, lat/long — than the PRD's six facts, seeded from CSV but unused by the engine).

**Difficulty mix**
- Definition (doc): percentages of easy/medium/hard slopes summing to 100, supplied for each resort (`prd.md:150`).
- Definition (code): a **derived, display-only** value object computed from three slope *counts* via largest-remainder rounding, guaranteed to sum to 100.
- Code home: `src/main/java/com/nextslope/resort/DifficultyMix.java:7`; derivation `Resort.java:126-167`.

**Deactivation (active flag)**
- Definition: an admin removes a resort from the product surface without deleting it; visited references keep working; reactivation is the reverse toggle.
- Source: `context/foundation/prd.md:135` — "FR-013: Admin can deactivate a resort entry — a deactivated resort no longer appears to users in browsing or in any new recommendation, while users' existing visited-list references to it continue to work."
- Code home: `Resort.java:111-112` (`active`); toggle `src/main/java/com/nextslope/resort/ResortService.java:50-60`; user-facing reads filter on it (`ResortController.java:30,37`; `RecommendationService.java:60`).

**External ID** — CODE-ONLY TERM
- Definition: the CSV dataset's stable key, unique when present; used to reconcile seed re-syncs and dedupe admin entries.
- Source (doc): ABSENT from `prd.md`; the seed mechanism is a roadmap-level decision (`context/foundation/roadmap.md:216`).
- Code home: `Resort.java:36-37`; uniqueness `src/main/resources/db/migration/V2__create_resorts.sql:31` and `ResortService.java:76-85`; resync keying `ResortSeedLoader.java:77-107`.

### Visited vocabulary

**Visited list / visited mark**
- Definition: the per-user, private, reversible record of resorts already visited; input to the novelty hard filter.
- Source: `context/foundation/prd.md:73-83` (US-02); privacy at `prd.md:83` — "The visited list is per-user; no other user (including admins) can see what resorts a given user has visited."
- Code home: `src/main/java/com/nextslope/visited/VisitedResort.java:32-47` (one row per user/resort pair); toggle semantics `VisitedResortService.java:32-48`.

### Account vocabulary

**User / Admin (roles)**
- Definition: two roles; Admin is a superset of User adding resort curation; admin assignment is out-of-band, not self-service.
- Source: `context/foundation/prd.md:160-163` (capability matrix); `prd.md:163` — "the admin role is assigned at user setup and is not self-service."
- Code home: `src/main/java/com/nextslope/user/User.java:54-57` (`Role.USER/ADMIN`); route gate `config/SecurityConfig.java:57` (`/admin/**` requires `ROLE_ADMIN`); out-of-band assignment `config/AdminBootstrap.java:42-67` (env-var bootstrap).

**Account deletion**
- Definition: permanent, immediate removal of the user plus their profile and visited list from every product surface.
- Source: `context/foundation/prd.md:144` — "once deletion is confirmed, their profile data and visited list are removed from the product and do not reappear on any product surface"; immediacy resolved in `context/foundation/roadmap.md:218`.
- Code home: `src/main/java/com/nextslope/user/AccountService.java:31-38` (children-before-parent app-level cascade).

**Profile/visited privacy (owner-scoping)**
- Definition: preferences and visited lists are visible only to their owner — including from admins.
- Source: `context/foundation/prd.md:55` — "Profile and visited-list privacy. A user's preferences and visited resorts are visible only to that user. Admins cannot see them; other users cannot see them."
- Code home: enforced structurally — services take only the authenticated `userId`, and no route addresses another user's data (`PreferenceProfileService.java:17-21` javadoc + signatures; `VisitedResortService.java:13-17`; `RecommendController.java:16-18`).

**Signup confirmation email** — DOC-ONLY TERM, ABSENT in code
- Source: `context/foundation/prd.md:183` — "No email notifications beyond signup confirmation" (Non-Goals; implies a signup confirmation email exists).
- Code home: ABSENT in code — no mail dependency in `build.gradle` and no mail/sender/confirmation reference anywhere under `src/main/` (verified by search). Registration persists the user and does nothing else (`UserRegistrationService.java:15-29`).

## Step 2 — Subdomain classification

Core = what makes the product worth existing, per the PRD's insight (`prd.md:24`: "no existing
tool fuses objective resort facts with the user's own preferences into a single short ranked
answer") and the roadmap's north star (`roadmap.md:24`: S-05 "is the smallest end-to-end flow
whose successful delivery proves the product's core idea").

| Area / concept | Classification | Justification (tied to product goals) |
|---|---|---|
| Recommendation engine (hard filters → weighted scoring → deterministic ranking → truthful rationale → sparse explanation) | **Core** | This is the product's entire differentiating claim (`prd.md:24`) and the roadmap's north star (`roadmap.md:24`). All four PRD guardrails (`prd.md:52-55`) converge on it. The only "invest deeply" slice (`roadmap.md:143`). |
| Rationale construction (truthfulness gate) | **Core** | "The rationale is the trust signal that distinguishes NextSlope from a generic stats comparator" (`prd.md:125`, FR-009 Socrates note). Regression here is defined as product failure (`prd.md:54`). |
| Preference profile (four axes) | **Supporting** | Exists to feed the engine; "four axes is the minimum that makes the recommender meaningfully personalized" (`prd.md:109`). No competitive value alone. |
| Visited list | **Supporting** | Makes the novelty axis data-driven and the rationale verifiable (`prd.md:111`, FR-005 Socrates note); feeds the core hard filter. |
| Resort catalog + difficulty-mix derivation | **Supporting** | The facts the engine consumes (`prd.md:150`); browsing exists so users can mark visited and sanity-check coverage (`prd.md:116`). |
| Admin resort curation (create/edit/deactivate) | **Supporting** | Needed because "resort facts evolve" (`prd.md:130`); deliberately minimal (`prd.md:169`); off the north-star path (`roadmap.md:155`). |
| Account deletion + privacy scoping | **Supporting** | Fulfils the privacy guardrail and NFR (`prd.md:55,142,144`); "privacy-completing slice" (`roadmap.md:167`). |
| Authentication (email/password, sessions, roles) | **Generic** | Commodity capability; PRD explicitly picked email/password because it is "well-understood, easy to ship" (`prd.md:103`); implemented with stock Spring Security (`config/SecurityConfig.java`). |
| CSV seed loading / dataset reconciliation | **Generic** | Data plumbing; the loader mechanism is a plan-level decision, not product behavior (`roadmap.md:216`). |
| Persistence & migrations (Flyway, dual-engine H2/Postgres) | **Generic** | Foundation slice F-01 (`roadmap.md:68-79`); no domain meaning of its own. |

## Step 3 — Aggregate candidates and their invariants

Status vocabulary: **enforced** (code and/or schema makes violation impossible or rejected),
**enforced (app-only)** (no schema backstop), **declared** (stated in doc/comments, code relies on
convention), **ignored** (no code counterpart).

### A1. `Resort` (aggregate root: catalog entry + derived difficulty mix)

| Invariant | Source | Status |
|---|---|---|
| Easy/medium/hard mix always sums to 100 | `prd.md:93` — "The percentages for easy/medium/hard slopes must sum to 100" | **Enforced — but by construction, not validation.** Percentages are never stored; `Resort.getDifficultyMix()` derives them from slope counts with largest-remainder rounding, so they always sum to 100 (`Resort.java:126-167`). No submission can violate it because submissions carry counts, not percentages (`ResortForm.java:36-46`). See divergence D1. |
| Numeric facts are non-negative integers | `prd.md:94` — "accept only non-negative integers" | **Enforced (app-only)** — `@PositiveOrZero` on all five numeric form fields (`ResortForm.java:28-46`); no schema CHECK constraints (`V2__create_resorts.sql`). |
| `total_slopes` equals the sum of the three difficulty counts | Implied by `prd.md:150` (one coherent set of facts) | **Partially enforced.** Admin writes derive it (`ResortService.java:71-73`); the CSV seed stores the CSV value as-is (`ResortSeedLoader.java:175`), and the entity javadoc concedes the two "can differ" (`Resort.java:128-130`). See divergence D2. |
| `external_id` unique when present | (code-introduced) | **Enforced** — schema `V2__create_resorts.sql:31` + pre-check and constraint-relabel in `ResortService.java:76-110`. |
| Deactivated resort invisible in browse/detail/recommendations | `prd.md:135` (FR-013) | **Enforced (app-only, query discipline)** — every user-facing read goes through `findByActiveTrue…` (`ResortController.java:30,37`; `RecommendationService.java:60`). Nothing *type-level* prevents a future query from forgetting the filter; guarded by tests (`src/test/java/com/nextslope/resort/ResortDeactivationIntegrationTests.java`). |
| No lost updates on concurrent admin edits | (code-introduced hardening) | **Enforced** — `@Version` optimistic lock (`Resort.java:114-116`, `V5__add_resort_version_lock.sql`), surfaced as `ConcurrentResortUpdateException` (`ResortService.java:54-58`). |

### A2. `PreferenceProfile` (aggregate root: four axes + region set)

| Invariant | Source | Status |
|---|---|---|
| Exactly one profile per user | `prd.md:158` — "a profile … is saved to the account" | **Enforced** — schema `UNIQUE (user_id)` (`V3__create_preference_profiles.sql:9`) + upsert-by-userId (`PreferenceProfileService.java:59-71`). |
| The three enum axes are always set | `prd.md:39` (all four axes listed in the success flow) | **Enforced** — `NOT NULL` columns (`V3:4-6`) + `@NotNull` form validation (`PreferenceProfileForm.java:22-29`). |
| Preferred mix sums to 100 | `prd.md:70` ("your preferred 60/30/10 difficulty mix") | **Enforced by construction** — bands are fixed triples that sum to 100 (`DifficultyBand.java:11-13`). |
| Region choices come from the live catalog vocabulary | (code-introduced; supports `prd.md:154` region hard filter) | **Enforced (app-only, at save time only)** — `UnknownRegionCountryException` (`PreferenceProfileService.java:88-95`). Not re-validated later: if every resort of a saved country is deactivated afterwards, the stored preference silently over-filters. Degrades gracefully into the sparse explanation (`RecommendationService.java:66-67`) rather than violating a guardrail. |

### A3. Per-user visited list (`VisitedResort` rows for one user)

| Invariant | Source | Status |
|---|---|---|
| At most one mark per (user, resort); marking is reversible | `prd.md:81` — "The mark-visited action is reversible — the same control unmarks the resort." | **Enforced** — schema `UNIQUE (user_id, resort_id)` (`V4__create_visited_resorts.sql:6`) + toggle with concurrent-insert absorption (`VisitedResortService.java:32-48`). |
| Only active resorts can be newly marked; unmark always succeeds (protects FR-013 references) | `prd.md:135` — "existing visited-list references to it continue to work" | **Enforced (app-only)** — active check on insert, none on delete (`VisitedResortService.java:33-39`). |
| Marks reference real users/resorts | Implied by `prd.md:150` (visited history as engine input) | **Ignored at schema level** — `visited_resorts` has NO foreign keys (`V4__create_visited_resorts.sql:1-7`). A documented, accepted MVP tradeoff: a racing write can orphan a row after account deletion, "orphans are unreachable — every read is principal-scoped" (`AccountService.java:18-20`). |

### A4. `User` (account)

| Invariant | Source | Status |
|---|---|---|
| Unique, normalized email identity | `prd.md:102` (FR-001) | **Enforced** — schema `uq_users_email` (`V1__create_users.sql:8`) + normalize-then-check (`UserRegistrationService.java:15-19`, `EmailNormalizer.java:8-13`) + race backstop in `AdminBootstrap.java:59-66`. |
| Deletion removes profile + visited everywhere, immediately | `prd.md:144` | **Enforced (app-only)** — single transaction, children before parent (`AccountService.java:31-38`). No DB cascade exists to back it up; correctness depends on this one method staying the only deletion path. |
| Role is USER unless assigned out-of-band | `prd.md:163` | **Enforced** — self-service signup hardcodes `Role.USER` (`UserRegistrationService.java:22-26`); ADMIN only via env-var bootstrap (`AdminBootstrap.java:54-58`). |

### A5. Recommendation (not a persisted aggregate — a core domain computation with invariants)

The engine owns the product's tightest invariants even though it stores nothing. It behaves as a
domain service producing an immutable value (`RecommendationResult`).

| Invariant | Source | Status |
|---|---|---|
| Exactly three results, or an explicit explanation — never padded, never silently short | `prd.md:53` | **Enforced** — sparse branch before scoring (`RecommendationService.java:66-68`), `limit(3)` (`:73`), discriminated result type makes the states unrepresentable otherwise (`RecommendationResult.java:14-32`). |
| NEW_ONLY users never see a visited resort | `prd.md:52` | **Enforced** — visited ids loaded only for NEW_ONLY and applied as a hard filter (`RecommendationService.java:56-63`). |
| Same inputs ⇒ same three, same order | `prd.md:143` | **Enforced** — total-order comparator with content-derived tie-break, "never the iteration order of any HashSet" (`RecommendationService.java:39-45`). |
| Rationale is truthful (reflects actual matching) | `prd.md:54` | **Enforced** — a clause is emitted only when the axis was set and its alignment clears `rationaleAlignmentThreshold` (`RationaleBuilder.java:24-47`; threshold `ScoringConfig.java:27`). Guarded by dedicated tests + the PIT mutation gate on `com.nextslope.recommendation.*` (`build.gradle`, per `AGENTS.md`). |
| Rationale names ≥1 axis the user actually set | `prd.md:70` | **Declared / partially enforced** — when no axis clears the threshold, the fallback ("one of the closest matches to your overall preferences", `RationaleBuilder.java:53-55`) names no axis. Truthfulness deliberately outranked axis-naming; see divergence D3. |

## Step 4 — MODEL vs CODE divergences

The most valuable table: where domain knowledge exists in the documents but the code does
something different (or vice versa). Ordered by domain significance.

| # | Document says | Code does | Evidence |
|---|---|---|---|
| D1 | Admins enter easy/medium/hard **percentages**, and "submissions where they don't [sum to 100] are rejected with a clear message naming the constraint" (`prd.md:88,93`; FR-011 `prd.md:131`) | Admins enter three slope **counts**; percentages are never input, never stored, and never validated — they are derived with largest-remainder rounding so they always sum to 100 | Form fields are counts: `ResortForm.java:36-46`; derivation: `Resort.java:126-167`; no sum-to-100 validation anywhere in `ResortService`/`ResortForm`. The invariant survives, but the modeled workflow (percentage entry + rejection message) does not exist. |
| D2 | "number of slopes" is one of the six admin-entered resort facts (`prd.md:131`) | Admins cannot enter it — it is computed as the sum of the three counts on every admin write; seed rows keep the CSV's own `Total slopes` value, which the entity admits "can differ" from that sum | Derived on write: `ResortService.java:71-73`; CSV taken as-is: `ResortSeedLoader.java:175`; acknowledged mismatch: `Resort.java:128-130`. Displayed "Total slopes" (`templates/resorts/list.html:44`) and the mix denominator can disagree for seeded rows. |
| D3 | "The 'why this matched you' rationale references at least one preference axis the user actually set" (US-01 AC, `prd.md:70`) | When no axis alignment clears the 0.6 threshold, a generic fallback is returned that names no specific axis ("one of the closest matches to your overall preferences") | `RationaleBuilder.java:49-55`; threshold default `ScoringConfig.java:27`. Deliberate: truthfulness guardrail (`prd.md:54`) beats the axis-naming AC; the tension is on the roadmap as S-09's open question (`roadmap.md:193` — "without emitting an untruthful rationale"). |
| D4 | Difficulty preference is a free "preferred easy/medium/hard slope mix", exemplified as "60/30/10" (`prd.md:39,70`) | Users pick one of three fixed preset bands (MOSTLY_EASY / BALANCED / MOSTLY_HARD); arbitrary mixes are not expressible | `DifficultyBand.java:9-13`; `PreferenceProfileForm.java:25-26`. Tracked as a roadmap unknown, resolved toward presets: `roadmap.md:105`. |
| D5 | Business logic implies the ranking aligns across the resort facts broadly — "top lift height, slope count, lift count, difficulty mix percentages, location" (`prd.md:148`), "and so on" (`prd.md:154`) | Only two soft axes are scored: difficulty-mix distance and experience-vs-hardness. Top lift height, slope count, and lift count are display-only and never affect ranking | Scorer consumes only `DifficultyMix` + profile: `Scorer.java:14`, `WeightedDistanceScorer.java:25-38`. Height/slopes/lifts appear only in `ResortCard.java:18-27`. Roadmap confirms height is not consumed yet — S-08 proposes adding it (`roadmap.md:170-181`). |
| D6 | Resorts have a "location" (`prd.md:115,131`); profile has a "location/region preference" (`prd.md:39`) | "Location" is narrowed to **country name** everywhere the domain acts on it. Latitude/longitude/continent are stored from the CSV but unused by any business rule | Region filter compares country strings: `RecommendationService.java:61-62`; profile stores country names: `PreferenceProfile.java:65-69`; unused columns: `Resort.java:45-52`. |
| D7 | Non-Goals presuppose a signup confirmation email exists: "No email notifications **beyond signup confirmation**" (`prd.md:183`) | No email capability exists at all — registration saves the user and returns; no mail dependency, sender, or template anywhere | `UserRegistrationService.java:15-29`; no `mail` match in `build.gradle` or `src/main/` (searched). |
| D8 | "A recommendation request returns a result, or an explicit timeout message, within 60 seconds" (`prd.md:141`); input acknowledged within 200ms (`prd.md:140`) | No timeout logic or explicit timeout message exists; the 2s-progress half of the NFR is covered by an HTMX indicator only | Recommendation path has no time bound: `RecommendationService.java:47-78`, `RecommendController.java:28-37`; indicator wiring: `templates/resorts/list.html:22-34`. Practically moot at 40 in-memory resorts, but the stated bound is unenforced. |
| D9 | Visited history is an explicit engine input class: "User visited history — the explicit list of resort entries the user has marked" (`prd.md:150`) | The engine loads the visited set **only when novelty is NEW_ONLY**; for REVISIT_OKAY users the visited list plays no role in scoring or rationale (e.g. no "is unvisited" clause, though `prd.md:152` exemplifies one: "matches your experience level and is unvisited") | `RecommendationService.java:56-58` (visited = `Set.of()` unless NEW_ONLY); `RationaleBuilder.java:23-56` has no visited/novelty clause. |

## Step 5 — Refactoring ranking

Ranked by (value of the invariant to the core) × (how weakly the code enforces it today).
Engine invariants rank low despite maximal value because they are the *best*-enforced part of the
codebase (discriminated result type, deterministic comparator, threshold-gated rationale, dedicated
tests + PIT mutation gate).

| Rank | Candidate | Value (how core) | Risk (how weakly enforced) | Verdict |
|---|---|---|---|---|
| **1** | **`Resort` difficulty-facts coherence** (D1 + D2) | High — the difficulty mix is the #1 scoring input of the core engine (`WeightedDistanceScorer.java:25-38`) and the #1 fact on every card | Highest — the counts→mix→totalSlopes relationship is enforced on only one of two write paths (`ResortService.java:71-73` vs `ResortSeedLoader.java:175`), the entity itself admits stored `totalSlopes` "can differ" (`Resort.java:128-130`), and no schema constraint guards non-negativity or coherence | **#1 to refactor** |
| 2 | Recommendation rationale axis coverage (D3, D9) | High — the rationale is the product's trust signal (`prd.md:125`) | Medium — truthfulness is enforced, but the axis-naming AC and the visited/novelty rationale clause are unmet; already owned by proposed slice S-09 (`roadmap.md:184-196`) | Fold into S-09; don't refactor ad hoc |
| 3 | Visited-list referential integrity | Medium — supporting, feeds one hard filter | Medium — no FKs at all (`V4__create_visited_resorts.sql`), integrity rests on principal-scoping conventions and one deletion method (`AccountService.java:31-38`); tradeoff is documented and orphans are unreachable | Acceptable for MVP; add FKs in the next migration touching the table |
| 4 | `PreferenceProfile` region vocabulary staleness | Medium — feeds the region hard filter | Low-medium — validated at save (`PreferenceProfileService.java:88-95`), degrades into the guardrail-compliant sparse explanation when stale | Observe; fix only if sparse complaints appear |
| 5 | Recommendation engine core invariants (exactly-3, determinism, NEW_ONLY) | Maximal | Lowest — type-enforced, comparator-enforced, filter-enforced, mutation-tested | No action needed |

**Why #1 is the Resort difficulty facts.** Every guardrail the product stakes its identity on —
truthful rationale, deterministic ranking — consumes `DifficultyMix` as ground truth, yet the facts
it is derived from are the least-guarded data in the system: two write paths with different rules
(admin derives `totalSlopes`, seed trusts the CSV), zero schema-level checks (no non-negativity, no
counts↔total consistency), and a PRD-visible workflow (percentage entry with sum-to-100 rejection)
that the shipped model quietly replaced with derivation. The engine can be provably correct and
still rank on garbage if a seed row or a future write path slips incoherent counts in. The refactor
shape (map-level suggestion only): make the three counts a proper value object owned by the `Resort`
aggregate, derive both the mix *and* `totalSlopes` from it on every path (admin, seed, resync), and
back it with schema CHECK constraints in the next migration — turning a convention into an
invariant.
