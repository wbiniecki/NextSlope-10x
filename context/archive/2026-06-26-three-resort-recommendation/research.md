---
date: 2026-06-26T18:10:00+02:00
researcher: binieckw
git_commit: 7496ac148235bc219e8c850a2526c8bccbb79e52
branch: main
repository: NextSlope
topic: "S-05 three-resort-recommendation — data model, patterns, guardrails, scoring algorithm"
tags: [research, codebase, recommendation, scoring, determinism, privacy, htmx]
status: complete
last_updated: 2026-06-26
last_updated_by: binieckw
---

# Research: S-05 three-resort-recommendation

**Date**: 2026-06-26T18:10:00+02:00
**Researcher**: binieckw
**Git Commit**: 7496ac148235bc219e8c850a2526c8bccbb79e52
**Branch**: main
**Repository**: NextSlope

## Research Question

Ground the S-05 "three-resort-recommendation" slice in the existing codebase before planning: a signed-in
user clicks "Recommend resorts" and sees exactly three ranked resorts (key facts + one-line truthful
rationale), or an explicit explanation when fewer than three viable matches exist — honoring hard filters
(region, visited-when-new-only) then weighted soft scoring, deterministically and privately. Focus areas:
**data model**, **controller/service/view + HTMX patterns**, **guardrails (privacy/determinism/sparse)**,
and **the scoring algorithm**.

## Summary

The three prior slices (S-02 profile, S-03 catalog, S-04 visited) were deliberately shaped to feed S-05;
**all scoring inputs already exist** and the recommendation package does **not** yet exist. The build is
mostly assembly: a new `com.nextslope.recommendation.*` service + a `RecommendController` returning an HTMX
fragment, reusing established patterns. Three things make this the "invest deeply" slice:

1. **The scoring algorithm must be invented** — two pieces have no precedent in code: (a) the soft-axis
   **weights** (flagged as the slice's one open unknown in the roadmap), and (b) **how the ordered
   `ExperienceLevel` enum maps onto a resort's `(easy,medium,hard)` difficulty triple** (no experience
   scalar exists on `Resort`).
2. **The real 40-resort seed makes the sparse-result and truthful-rationale guardrails first-class, not
   edge cases.** Region hard-filtering goes sparse constantly (10 of 14 countries have <3 resorts; 9 have
   exactly 1), and the difficulty-mix axis is **structurally unsatisfiable for two of three bands**
   (`MOSTLY_HARD` (10,30,60) and `BALANCED` (34,33,33) have essentially no matching resorts — the catalog
   is easy/medium-skewed). A naive equal-weight difficulty axis would produce untruthful "matches your
   mostly-hard preference" rationales.
3. **Determinism + privacy** are already enforced by convention (owner-scoped services, ordered queries)
   and by a ready-made test scaffold; S-05 must not break the pattern (notably: do not iterate the
   unordered `Set<Long>` visited ids for ranking).

Next migration version is **V5** (V1–V4 confirmed live on disk) — though S-05 likely needs **no new
migration** (it reads existing tables).

## Detailed Findings

### Data model (all scoring inputs already exist)

Inputs the recommender consumes, with exact shapes:

| Input | Source | Shape |
|---|---|---|
| Experience level | `PreferenceProfile.experienceLevel` ([`PreferenceProfile.java:53-55`](src/main/java/com/nextslope/profile/PreferenceProfile.java)) | enum `BEGINNER, INTERMEDIATE, ADVANCED` ([`ExperienceLevel.java:6-10`](src/main/java/com/nextslope/profile/ExperienceLevel.java)) — **ordered, no scalar, no target mix** |
| Preferred difficulty mix | `profile.getPreferredMix()` ([`PreferenceProfile.java:79-83`](src/main/java/com/nextslope/profile/PreferenceProfile.java)) | derived `DifficultyMix(easy,medium,hard)` from a **preset band**, not stored percentages |
| Difficulty band presets | [`DifficultyBand.java:9-13`](src/main/java/com/nextslope/profile/DifficultyBand.java) | `MOSTLY_EASY=(60,30,10)`, `BALANCED=(34,33,33)`, `MOSTLY_HARD=(10,30,60)` |
| Region filter | `profile.regionCountries` ([`PreferenceProfile.java:65-69`](src/main/java/com/nextslope/profile/PreferenceProfile.java)) | `Set<String>` country names; **empty set = "any region", no filter** |
| Novelty filter | `profile.noveltyPreference` ([`NoveltyPreference.java:7-10`](src/main/java/com/nextslope/profile/NoveltyPreference.java)) | `NEW_ONLY` (hard-exclude visited) / `REVISIT_OKAY` (no filter) |
| Candidate pool | `ResortRepository.findByActiveTrueOrderByCountryAscNameAsc()` ([`ResortRepository.java:10`](src/main/java/com/nextslope/resort/ResortRepository.java)) | active-only, **already ordered country→name** |
| Resort difficulty | `resort.getDifficultyMix()` ([`Resort.java:121-162`](src/main/java/com/nextslope/resort/Resort.java)) | derived `DifficultyMix` from slope **counts** (largest-remainder, sums to 100; `(0,0,0)` if no slopes) |
| Resort region | `resort.getCountry()` (`String`, NOT NULL) | exact-string match key vs profile (same vocabulary, validated on save) |
| Visited set | `VisitedResortService.visitedResortIds(userId)` ([`VisitedResortService.java:50-54`](src/main/java/com/nextslope/visited/VisitedResortService.java)) → `findResortIdsByUserId` ([`VisitedResortRepository.java:19-21`](src/main/java/com/nextslope/visited/VisitedResortRepository.java)) | **unordered** `Set<Long>` resort ids |

Key model facts:
- **Region is plain strings on both sides** — there is no shared region enum. The hard filter is
  `regionCountries.isEmpty() || regionCountries.contains(resort.getCountry())`.
- **Difficulty is a band → fixed triple**, so the *stored* value (the band) is the truthful thing the user
  chose; the triple is derived deterministically.
- **`Resort` has no experience attribute** — S-05 must define the experience↔mix comparison itself.
- Repositories available: `PreferenceProfileRepository.findByUserId` (Optional, one row),
  `ResortRepository.findByActiveTrueOrderByCountryAscNameAsc` / `findByIdAndActiveTrue`,
  `VisitedResortRepository.findResortIdsByUserId`. **No `findByCountryIn` exists** — region filtering is
  in-memory over the active pool (fine at 40 rows) unless a new query is added.
- Flyway **V1–V4** create `users`, `resorts`, `preference_profiles` (+ `preference_profile_regions`),
  `visited_resorts`. **Next version is V5** (confirmed via live `ls`, per the lessons.md rule). S-05
  reads existing tables, so a new migration is likely unnecessary.

### Controller / service / view + HTMX patterns to follow

**Controllers** (all in `com.nextslope.web`, `@RequiredArgsConstructor`, constructor injection):
- Resolve the current user via `@AuthenticationPrincipal UserDetails principal` →
  `currentUserService.requireUserId(principal)` ([`CurrentUserService.java:22-26`](src/main/java/com/nextslope/user/CurrentUserService.java)) — **never a user id in the URL**.
- Full-page handlers return a view name (`"resorts/list"`); HTMX handlers return a **thin wrapper template**
  that `th:replace`s a named fragment.
- 404 via `throw new ResponseStatusException(HttpStatus.NOT_FOUND)`.

**Services** (`@Service`, domain packages): every method takes `Long userId` (owner-scoped, no cross-user
route); `@Transactional(readOnly=true)` on reads; **DTOs/forms for the web layer, never entities**. The
recommender belongs in `com.nextslope.recommendation.*` and should return a result DTO for the view.

**The S-04 HTMX in-place-update chain (the pattern S-05 reuses)** —
[`VisitedController.java:30-44`](src/main/java/com/nextslope/web/VisitedController.java),
[`resorts/list.html:53-68`](src/main/resources/templates/resorts/list.html),
[`resorts/visited-toggle-response.html`](src/main/resources/templates/resorts/visited-toggle-response.html):
1. A named `th:fragment` is defined inside a never-rendered `th:block th:if="${false}"` so it is addressable
   but not emitted on full-page render.
2. The control carries `hx-post`, `hx-swap`, `hx-disabled-elt`; the controller returns a thin wrapper
   template that `th:replace`s the fragment; **model attribute names must match fragment parameters**.
3. **CSRF for HTMX** is wired once in the layout: `<meta name="_csrf">` + an `htmx:configRequest` listener
   ([`fragments/layout.html:38-62`](src/main/resources/templates/fragments/layout.html)). Any page that
   does HTMX **must** use `layout :: head` + `layout :: scripts`.

For S-05: a `recommendResults(...)` fragment + a button with `hx-post="/recommend"`,
`hx-target="#recommend-results"`, `hx-swap="innerHTML"`, and **`hx-indicator`** (satisfies the 2s-progress
NFR). The tech-stack hand-off already names this exact usage
([`tech-stack.md:53-57`](context/foundation/tech-stack.md)).

**Layout & navigation** — shared fragments `head(title)`, `navbar`, `scripts` in
[`fragments/layout.html`](src/main/resources/templates/fragments/layout.html). **No "Recommend resorts"
entry point exists yet**; `index.html:25-27` still says "coming soon". Per the lessons.md
"navigation-to-every-new-screen" rule, the plan must add the entry point — best candidates: a navbar button
next to "Profile" ([`fragments/layout.html:19-20`](src/main/resources/templates/fragments/layout.html)) and/or
a button + `#recommend-results` container on `/resorts` (where the profile save already redirects,
[`ProfileController.java:73`](src/main/java/com/nextslope/web/ProfileController.java)).

**Routes today**: `/`, `/login`, `/signup`, `/profile`, `/resorts`, `/resorts/{id}`,
`/resorts/{id}/visited` (HTMX). `/recommend` is **planned and already locked as a must-stay-gated route**
in tests ([`PermitListLockTests.java:85`](src/test/java/com/nextslope/PermitListLockTests.java)).

### Guardrails: privacy, determinism, sparse-result, and test scaffolding

**Privacy** — enforced architecturally by owner-scoped services (no cross-user URL to forge). S-05 must read
profile via `findByUserId(userId)` and visited via `visitedResortIds(userId)` only. Ready-made scaffold in
`src/test/java/com/nextslope/support/`:
- `UserFixtures` — `userA`/`userB`/`admin` factories + email/password constants.
- `TwoUserIntegrationTestBase` (`@SpringBootTest` + `@AutoConfigureMockMvc`) — seeds the three users,
  `loginAsUserA/B/Admin()` helpers.
- `AccessControlAssertions` — `assertRedirectedToLogin`, `assertReachedPastSecurity`, `assertForbidden`
  (note `assertWrongOwnerDenied` is a placeholder; profile/visited chose **isolation** assertions instead
  because routes are principal-scoped).
- Pattern to mirror: [`VisitedResortOwnershipIntegrationTests.java:63-68`](src/test/java/com/nextslope/visited/VisitedResortOwnershipIntegrationTests.java)
  asserts admin/other-user see nothing — including `findResortIdsByUserId(adminId).isEmpty()`.

**Security** — [`SecurityConfig.java:51-65`](src/main/java/com/nextslope/config/SecurityConfig.java): only a
permit-list is public; everything else is authenticated. **No URL-level role gating** (USER vs ADMIN is on
the entity/authorities only). `/recommend` inherits "authenticated, any role". Admin must **not** gain
access to another user's profile/visited (PRD NFR [`prd.md:142`](context/foundation/prd.md)).

**Determinism** — rank by score desc, then a **total stable tie-break** mirroring the browse order:
`(-score, country, name, id)`. Reusable ordered building blocks: `findByActiveTrueOrderByCountryAscNameAsc`,
the `DifficultyBand` fixed-triple mapping. **Non-determinism risks to avoid**: the visited `Set<Long>` and
profile `regionCountries` `HashSet` are **unordered** — never drive ranking off their iteration order;
avoid `HashMap`/`HashSet` iteration in the scorer and `parallelStream()`.

**Sparse-result** — no implementation/tests yet; the seed data (below) makes this a common path, so it must
be a first-class, tested branch returning an explicit explanation with **no padding** (PRD
[`prd.md:53,71,152`](context/foundation/prd.md)).

**Test slicing** — `@DataJpaTest` for repo/mapping (H2); `@WebMvcTest` (+ `@Import(SecurityConfig)`) for
controller/security; `@SpringBootTest` for full wiring; `@SpringBootTest @Testcontainers` (Postgres 16) only
if a new migration/entity lands. Scorer/filter/rationale logic → **plain JUnit 5 + AssertJ unit tests**
(fast, PIT-friendly) per [`test-plan.md` §6.5](context/foundation/test-plan.md). The PIT mutation gate scoped
to `com.nextslope.recommendation.*` is **deferred to S-05** (wire it here).

### Scoring algorithm (the invent-it part)

Shared **Stage 1 (hard filters)** for every approach:
1. Pool = `findByActiveTrueOrderByCountryAscNameAsc()` (active-only; respects FR-013 deactivation — a
   deactivated resort vanishes from new recs even if it remains in someone's visited list).
2. Region: keep iff `regionCountries.isEmpty() || regionCountries.contains(resort.country)`.
3. Novelty: if `NEW_ONLY`, drop `resort.id ∈ visitedResortIds(userId)`; else no filter.
4. **If survivors < 3 → explicit explanation, do not score or pad.**

Shared **rationale**: compute alignment per *set* axis, pick the **highest-aligned axis the user actually
set**, template a truthful clause. Never emit a difficulty clause when the resort's mix is far from the
band target (would violate the truthfulness guardrail given the seed reality).

Three candidate Stage-2 approaches (see Open Questions for the crux):
- **A — Weighted normalized distance (recommended default):** `align_diff = 1 − L1(prefMix,resortMix)/200`;
  experience via a **hardness index** `H = (0·easy + 0.5·medium + 1·hard)/100` with targets
  `BEGINNER→0.20, INTERMEDIATE→0.45, ADVANCED→0.70`, `align_exp = 1 − |H − target|`; score =
  `0.5·align_diff + 0.5·align_exp`. Smooth, tunable; but `align_diff` under-discriminates for the hard bands.
- **B — Lexicographic / priority-ordered axes:** sort by bucketed `align_diff`, then `align_exp`, then
  tie-break. No weights to invent; very explainable; coarse.
- **C — Gated weighted distance:** A, but down-weight/drop an axis no surviving candidate can satisfy
  (directly defuses the unreachable-hard-band problem). Most complex; threshold is a new knob; needs tests
  to keep the determinism proof obvious.

## Code References

- `src/main/java/com/nextslope/profile/PreferenceProfile.java:53-83` — the four scoring axes + `getPreferredMix()`
- `src/main/java/com/nextslope/profile/DifficultyBand.java:9-13` — band → `DifficultyMix` presets
- `src/main/java/com/nextslope/profile/NoveltyPreference.java:7-10` — `NEW_ONLY` / `REVISIT_OKAY`
- `src/main/java/com/nextslope/resort/Resort.java:121-162` — derived `getDifficultyMix()`
- `src/main/java/com/nextslope/resort/ResortRepository.java:10` — ordered active candidate pool
- `src/main/java/com/nextslope/visited/VisitedResortService.java:50-54` — `visitedResortIds(userId)` (the S-05 read)
- `src/main/java/com/nextslope/visited/VisitedResortRepository.java:19-21` — `findResortIdsByUserId` (unordered Set)
- `src/main/java/com/nextslope/user/CurrentUserService.java:22-26` — principal → userId
- `src/main/java/com/nextslope/web/VisitedController.java:30-44` — HTMX endpoint pattern to mirror
- `src/main/resources/templates/resorts/list.html:53-68` — named fragment + hidden-block pattern
- `src/main/resources/templates/resorts/visited-toggle-response.html` — thin HTMX wrapper template
- `src/main/resources/templates/fragments/layout.html:38-62` — CSRF + HTMX wiring (reuse verbatim)
- `src/main/java/com/nextslope/config/SecurityConfig.java:51-65` — public permit-list vs authenticated
- `src/test/java/com/nextslope/support/TwoUserIntegrationTestBase.java` — privacy/IDOR test base
- `src/test/java/com/nextslope/visited/VisitedResortOwnershipIntegrationTests.java:63-68` — admin/other-user isolation pattern
- `src/test/java/com/nextslope/PermitListLockTests.java:85` — `/recommend` locked as gated
- `src/main/resources/data/resorts-Europe-subset.csv` — the 40-resort seed (distribution below)

## Architecture Insights

- **S-05 is assembly, not new infrastructure.** Profile/catalog/visited were each built with an explicit
  "S-05 reuses this" read. The only genuinely new design is the scoring math + the result view/route.
- **Owner-scoped, principal-resolved everywhere.** No user id in URLs; `CurrentUserService.requireUserId`
  is the single bridge. Privacy is a property of the architecture, tested by isolation assertions.
- **Determinism is achievable by construction** if ranking sorts on content-derived totals and never on
  `HashSet` iteration order; the browse query already supplies a stable base order.
- **The seed dataset is the real constraint.** Region filtering and difficulty matching are both
  data-limited, so the sparse-result and truthful-rationale guardrails — not the weight values — are the
  riskiest parts and the ones most worth pinning with tests.

## Historical Context (from prior changes)

- `context/archive/2026-06-21-preference-profile/research.md:188-202` — the canonical profile-axis → S-05
  stage mapping (region/novelty = hard filters; experience/difficulty = soft scores), plus the determinism
  and rationale-truthfulness constraints. `research.md:269-279` explicitly punts **experience↔mix mapping**
  and **soft-axis weights** to S-05.
- `context/archive/2026-06-21-resort-catalog-browse/plan.md:112-127` — difficulty stored as raw slope
  counts, exposed as a derived sum-to-100 percentage for display; "heavy scoring stays in S-05"; zero-denom
  guard; `external_id` must never surface.
- `context/archive/2026-06-26-mark-visited/plan.md:117-118,214-216` — visited slice intentionally added only
  the minimal `findResortIdsByUserId` read S-05 reuses; visited ids may reference now-inactive resorts, so
  the candidate pool must be active-only while the visited set is not.

## Seed-data reality (load-bearing for the plan)

`src/main/resources/data/resorts-Europe-subset.csv` — 40 resorts, all `Continent=Europe` (non-discriminating).
- **Country counts:** Austria 8, France 8, Switzerland 7, Italy 6, Germany 2; **Andorra, Slovenia, Bulgaria,
  Spain, Sweden, Norway, Slovakia, Poland, Czech Republic = 1 each.** Only 4 countries individually yield ≥3
  → single small-country region selection deterministically hits the **<3 sparse path** before scoring.
- **Difficulty skew:** catalog is easy/medium-heavy. Hard-share max is Grands Montets ~48% (a 29-slope
  outlier), next ~25–28%; flagships ~12% hard. `MOSTLY_HARD (10,30,60)` has **no real match**;
  `BALANCED (34,33,33)` barely any; **only `MOSTLY_EASY (60,30,10)` has genuine matches** (Kitzbühel,
  Saalbach, La Plagne, Szczyrk, Alta Badia).
- **Numeric ranges (if ever used in scoring):** top lift height 1100–3899; total slopes 20–600; total lifts
  9–165. PRD names only experience + difficulty-mix as soft axes; size/height are display facts.

## Open Questions

1. **Soft-axis weights** (roadmap's stated unknown, [`roadmap.md:139-140`](context/foundation/roadmap.md)) —
   but the sharper, seed-driven form is: *how do we weight/handle a difficulty axis that, for two of three
   bands, every candidate fails roughly equally — without emitting an untruthful "matches your mostly-hard
   preference" rationale?* This argues for Approach C (data-aware gating) or at least Approach A with the
   rationale choosing the **genuinely** dominant axis. **Owner: this slice.**
2. **Experience → difficulty-mix mapping** — no precedent in code; the hardness-index scalar (Approach A) is
   the cleanest proposal but the target values (0.20/0.45/0.70) are a choice to confirm.
3. **Region filter location** — in-memory over the active pool (simple at 40 rows) vs a new
   `findByCountryIn` query. In-memory is fine for v1; note for the worldwide-dataset parked item.
4. **Result route shape** — dedicated `GET /recommend` full page vs HTMX-only `POST /recommend` results
   partial swapped into a container on `/resorts`. Both fit conventions; affects the navigation entry point.
5. **No new migration expected** — confirm S-05 truly reads only existing tables (no persisted
   recommendation history); if any persistence is added, it is **V5**.

## Related Research

- `context/archive/2026-06-21-preference-profile/research.md`
- `context/archive/2026-06-21-resort-catalog-browse/research.md`
- `context/archive/2026-06-26-mark-visited/research.md`
- `context/foundation/test-plan.md` §6.5 (recommender correctness + PIT gate)
