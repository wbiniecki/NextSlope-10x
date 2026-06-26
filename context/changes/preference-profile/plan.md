# Preference Profile (S-02) Implementation Plan

## Overview

Let a signed-in user create and edit their **preference profile** — experience level, preferred
difficulty band, region preference (a set of countries), and novelty preference — at `/profile`, with
edits persisting across sessions. This is the second authenticated vertical slice (after S-01) and a
prerequisite for the S-05 recommender, which hard-filters on region + novelty and soft-scores
experience + difficulty. The profile is one row per user (create and edit are the same upsert form).

## Current State Analysis

- **Auth + gating are done (S-01).** `SecurityConfig` uses a binary gate (permit-list, else
  `.anyRequest().authenticated()`); a new `/profile` route is gated with no config change, and
  `/profile` is already enumerated as must-stay-gated in `PermitListLockTests.java:65`.
- **The principal is the user's email.** `AppUserDetailsService` maps `User.email` → username and
  `User.role` → `ROLE_USER`/`ROLE_ADMIN`. No code uses `@AuthenticationPrincipal` yet; the controller
  will resolve the current user via `userRepository.findByEmail(principal.getUsername())`.
- **Form pattern is established (S-01).** `AuthController` signup = GET seeds an empty form DTO → POST
  binds `@Valid @ModelAttribute(...) + BindingResult` → re-render same template on error (200),
  `redirect:` on success (PRG); CSRF on by default. Form DTOs are plain Lombok classes with Jakarta
  validation (`RegistrationForm`), unit-testable with a standalone `Validator`.
- **Layout + view patterns exist (S-03).** `fragments/layout.html` exposes `head(title)`, `navbar`,
  `scripts` (Bootstrap 5 + HTMX via CDN). The navbar currently has only the brand link and a Sign out
  button — **there is no way to navigate to a profile page today.** `signup.html` is the canonical
  Bootstrap form template (`th:object`, `th:field`, `is-invalid` + `th:errors`, `novalidate`).
- **Persistence conventions (F-01/S-01/S-03).** Flyway owns the schema; `ddl-auto=validate` means a new
  entity REQUIRES a matching migration or boot fails. Only `V1__create_users.sql` and
  `V2__create_resorts.sql` exist → next is **`V3`**. Entities are Lombok + `IDENTITY` PK + snake_case
  `@Column` + Hibernate audit timestamps (`@CreationTimestamp`/`@UpdateTimestamp`, never DB defaults).
  Repositories extend `JpaRepository` with **derived queries only** (no `@Query`, no `@Repository`).
- **Region vocabulary.** The seed has 14 distinct `Resort.country` values (France, Austria, Switzerland,
  Italy, Germany, Sweden, Spain, Slovenia, Slovakia, Poland, Norway, Czech Republic, Bulgaria, Andorra).
  `ResortRepository.findByActiveTrueOrderByCountryAscNameAsc()` already exists; distinct countries can
  be derived in the service without a custom `@Query`.
- **No relations or collections exist yet.** This slice introduces the project's **first FK**
  (`preference_profiles.user_id → users.id`) and **first `@ElementCollection`** (the region set).

### Key Discoveries:

- `src/main/java/com/nextslope/config/SecurityConfig.java:50-54` — binary gate; `/profile` free to add.
- `src/test/java/com/nextslope/PermitListLockTests.java:65` — `/profile` already locked as gated.
- `src/main/java/com/nextslope/web/AuthController.java:43-84` — canonical GET-form/POST-validate/redirect
  pattern; **signup currently redirects to `/` (line 83)** — this plan changes it to `/profile`.
- `src/test/java/com/nextslope/SignupIntegrationTests.java:43` — asserts `redirectedUrl("/")` after
  signup; **must be updated** to `/profile` when the redirect changes.
- `src/main/java/com/nextslope/user/RegistrationForm.java:11-23` — form-DTO + Jakarta validation pattern.
- `src/main/java/com/nextslope/user/User.java:42-44`, `db/migration/V1__create_users.sql:5` — enum →
  `@Enumerated(STRING)` + `VARCHAR(32)` precedent.
- `src/main/java/com/nextslope/resort/DifficultyMix.java:7` — `record DifficultyMix(int easy, int medium,
  int hard)`; the band→mix shape S-05 will score against.
- `src/main/java/com/nextslope/resort/ResortRepository.java:10` — `findByActiveTrueOrderByCountryAscNameAsc()`
  feeds the distinct-country option list.
- `src/main/resources/templates/fragments/layout.html:14-26` — navbar fragment to extend with a Profile link.
- `src/test/java/com/nextslope/support/TwoUserIntegrationTestBase.java:29-65`,
  `AccessControlAssertions.java:21-68` — owner-only / isolation test scaffolding.

## Desired End State

A signed-in user can open `/profile` (via a new navbar "Profile" link, and automatically right after
signup), see a form pre-filled with sensible defaults (or their saved values on a return visit), change
any of the four axes, save, and have the values persist across sessions. The persisted shape is exactly
what S-05 will consume: region as a set of country strings comparable to `Resort.country` (empty = "any
region", no filter), novelty as a two-value enum, experience as an ordered enum, and difficulty as a
band enum whose canonical easy/medium/hard triple S-05 scores against `Resort.getDifficultyMix()`.

**Verification:** `./gradlew test` is green (including a Testcontainers Postgres proof that `V3` applies
and the entity validates against real Postgres); a manual round-trip (sign up → land on `/profile` →
save → sign out → sign in → values still there → edit → persists) works end to end.

## What We're NOT Doing

- **Not building the recommender (S-05).** No scoring, hard-filtering, ranking, or rationale here — only
  shaping and persisting the inputs it will read.
- **Not changing `SecurityConfig`** — the default gate already covers `/profile`.
- **Not adding role checks** — profile is a plain authenticated-user feature; ADMIN gating is S-06.
- **Not adding account deletion / cascade** — that is S-07 (it will later delete this profile row).
- **Not migrating the legacy inline templates** (`index/login/signup/error`) to the layout fragment.
- **Not persisting raw difficulty percentages as user input** — the UI is preset bands; the triple is
  derived from the chosen band (see Critical Implementation Details).
- **Not adding a DB `CHECK` constraint** — sum-to-100 / value validity stays in the app layer (no CHECK
  convention exists yet), and with band-derived mixes the triple is correct by construction anyway.

## Implementation Approach

A 3-phase, backend-first vertical slice mirroring the shipped S-03 plan (domain & migration → service &
form → controller, view, navigation & gating). New code lives in a new `com.nextslope.profile` package
(entity, enums, repository, service, form); the controller lives in `com.nextslope.web` alongside the
existing controllers. Each phase ends with its own automated verification and a commit; the manual UI
round-trip is verified at the end of Phase 3.

## Critical Implementation Details

- **Preset bands store a band enum, not raw percentages.** The user picks a `DifficultyBand`; the entity
  persists the band (`VARCHAR(32)`). Each band maps in code to a fixed `DifficultyMix` triple summing to
  100, exposed via a derived accessor so S-05 can score it against `Resort.getDifficultyMix()`. Canonical
  mapping to implement: `MOSTLY_EASY → (60,30,10)`, `BALANCED → (34,33,33)`, `MOSTLY_HARD → (10,30,60)`.
  This keeps the *stored* value the truthful thing the user chose, while the triple stays deterministic
  (same band → same mix) — satisfying both the determinism NFR and rationale truthfulness.
- **Region is a set, "any region" is the empty set.** Selecting countries = hard filter to those
  countries in S-05; "Any region" = empty set = no filter. There is no separate "any" flag in storage —
  the empty set *is* "any". The form's "Any region" control is a UX affordance normalized server-side:
  if chosen (or nothing selected), the stored set is empty.
- **Region values must be a subset of the live country vocabulary.** On save, reject any submitted
  country not in `availableCountries()` (derived from active resorts) — prevents storing junk and keeps
  the set comparable to `Resort.country`. This is app-layer validation, not a DB constraint.
- **Owner scoping is structural.** `/profile` has no id path variable; it always resolves the row for
  the authenticated principal. There is no addressable cross-user route, so there is no IDOR surface to
  defend — the test burden is *isolation* (two users' profiles never bleed), not a forbidden response.

## Phase 1: Domain & Migration

### Overview

Create the enums, the `PreferenceProfile` entity with its region collection, the repository, and the
`V3` Flyway migration (two tables). Prove it validates and applies on both H2 and real Postgres.

### Changes Required:

#### 1. Enums

**File**: `src/main/java/com/nextslope/profile/ExperienceLevel.java`,
`src/main/java/com/nextslope/profile/DifficultyBand.java`,
`src/main/java/com/nextslope/profile/NoveltyPreference.java`

**Intent**: Define the three stable, ordered preference vocabularies S-05 will read. `DifficultyBand`
additionally carries its canonical mix so the triple is derivable without a separate lookup.

**Contract**:
- `ExperienceLevel { BEGINNER, INTERMEDIATE, ADVANCED }`.
- `NoveltyPreference { NEW_ONLY, REVISIT_OKAY }`.
- `DifficultyBand { MOSTLY_EASY, BALANCED, MOSTLY_HARD }`, each exposing a `DifficultyMix toMix()` (or a
  final `DifficultyMix mix` field) using the canonical triples in Critical Implementation Details. Reuse
  the existing `com.nextslope.resort.DifficultyMix` record.

A dedicated unit test (`src/test/java/com/nextslope/profile/DifficultyBandTests.java`) gates this S-05
contract constant: `toMix()` returns the exact canonical triple (`MOSTLY_EASY → (60,30,10)`,
`BALANCED → (34,33,33)`, `MOSTLY_HARD → (10,30,60)`) and each sums to 100, for every band. Ships in
Phase 1 with the enum.

#### 2. `PreferenceProfile` entity

**File**: `src/main/java/com/nextslope/profile/PreferenceProfile.java`

**Intent**: JPA entity mapping the `preference_profiles` row (one per user) plus its region set. Follows
the `User`/`Resort` conventions (Lombok, `IDENTITY` PK, snake_case `@Column`, Hibernate audit columns).

**Contract**: Fields — `Long id`; `Long userId` (`@Column(name="user_id")`, the FK value; store the id
rather than a `@ManyToOne` to keep the entity lean and avoid the project's first association graph);
`ExperienceLevel experienceLevel` and `NoveltyPreference noveltyPreference` and `DifficultyBand
difficultyBand`, each `@Enumerated(STRING)` → `VARCHAR(32)`; `Set<String> regionCountries` via
`@ElementCollection` + `@CollectionTable(name="preference_profile_regions",
joinColumns=@JoinColumn(name="profile_id"))` + `@Column(name="country")`; `Instant createdAt`
(`@CreationTimestamp`), `Instant updatedAt` (`@UpdateTimestamp`). Add a derived `DifficultyMix
getPreferredMix()` delegating to `difficultyBand` (the S-05 read accessor).

#### 3. Repository

**File**: `src/main/java/com/nextslope/profile/PreferenceProfileRepository.java`

**Intent**: One-row lookup by owner.

**Contract**: `interface PreferenceProfileRepository extends JpaRepository<PreferenceProfile, Long>` with
`Optional<PreferenceProfile> findByUserId(Long userId)`. Derived query only.

#### 4. Flyway migration

**File**: `src/main/resources/db/migration/V3__create_preference_profiles.sql`

**Intent**: Create the profile table (1:1 to users) and the region collection table, portable across H2
(PostgreSQL mode) and Postgres.

**Contract**: Two `CREATE TABLE`s in one file. `preference_profiles`: `id BIGINT GENERATED BY DEFAULT AS
IDENTITY PRIMARY KEY`, `user_id BIGINT NOT NULL`, `experience_level VARCHAR(32) NOT NULL`,
`difficulty_band VARCHAR(32) NOT NULL`, `novelty_preference VARCHAR(32) NOT NULL`, `created_at TIMESTAMP
NOT NULL`, `updated_at TIMESTAMP NOT NULL`, `CONSTRAINT uq_preference_profiles_user_id UNIQUE (user_id)`,
`CONSTRAINT fk_preference_profiles_user_id FOREIGN KEY (user_id) REFERENCES users (id)`.
`preference_profile_regions`: `profile_id BIGINT NOT NULL`, `country VARCHAR(255) NOT NULL`, `CONSTRAINT
fk_preference_profile_regions_profile FOREIGN KEY (profile_id) REFERENCES preference_profiles (id)`,
`CONSTRAINT uq_preference_profile_regions UNIQUE (profile_id, country)`. (No raw difficulty-percentage
columns — the mix is band-derived.)

#### 5. Repository / entity tests

**File**: `src/test/java/com/nextslope/profile/PreferenceProfileRepositoryTests.java` (`@DataJpaTest`),
and add a `preference_profiles` assertion to the existing Testcontainers Postgres proof (extend
`UserRepositoryPostgresTests`'s sibling or add `PreferenceProfileRepositoryPostgresTests`).

**Intent**: Verify the entity↔schema mapping (including the `@ElementCollection`) and that `V3` applies
on real Postgres.

**Contract**: H2 test — save a profile with a non-empty region set, `findByUserId` round-trips all axes
and the region set; `UNIQUE(user_id)` rejects a second profile for the same user. Postgres test —
context boots (so `V3` applied + `ddl-auto=validate` passed) and a save/read round-trips.

### Success Criteria:

#### Automated Verification:

- Build compiles: `./gradlew compileJava`
- `DifficultyBand.toMix()` returns the canonical triple summing to 100 for each band: `./gradlew test --tests "com.nextslope.profile.DifficultyBandTests"`
- `@DataJpaTest` repository/entity tests pass: `./gradlew test --tests "com.nextslope.profile.*"`
- Testcontainers Postgres migration proof passes (V3 applies, entity validates): `./gradlew test --tests "*Postgres*"`
- Full suite green (no schema-validation boot failures): `./gradlew test`

#### Manual Verification:

- App boots locally with `./gradlew bootRun` (H2 path: `V3` applied, `ddl-auto=validate` passes).

**Implementation Note**: After automated verification passes, pause for human confirmation of the manual
boot check before Phase 2.

---

## Phase 2: Service & Form

### Overview

Add the owner-scoped service (load-or-default + upsert + country options) and the validated form DTO,
with unit tests that need no web layer.

### Changes Required:

#### 1. Form DTO

**File**: `src/main/java/com/nextslope/profile/PreferenceProfileForm.java`

**Intent**: Plain Lombok form object mirroring the template fields, with Jakarta validation — never the
entity.

**Contract**: Fields — `ExperienceLevel experienceLevel` (`@NotNull`), `DifficultyBand difficultyBand`
(`@NotNull`), `NoveltyPreference noveltyPreference` (`@NotNull`), `boolean anyRegion`, `List<String>
regionCountries` (bound from the multi-select; may be empty). Cross-field validity (each selected
country ∈ available list) is enforced in the service against the live vocabulary, not as a static
annotation. Provide a static factory for default values (see service).

#### 2. Service

**File**: `src/main/java/com/nextslope/profile/PreferenceProfileService.java`

**Intent**: Encapsulate owner-scoped load/upsert and the country option list.

**Contract**:
- `PreferenceProfileForm loadFormForUser(Long userId)` — returns the user's saved values mapped to a
  form, or a **defaults** form when none exists (`INTERMEDIATE`, `BALANCED`, `REVISIT_OKAY`, anyRegion=
  true / empty regions). When mapping an existing profile, derive `anyRegion = regionCountries.isEmpty()`
  so a saved-but-empty region set renders the "Any region" checkbox checked (round-trips the inverse of
  `save`'s normalization).
- `void save(Long userId, PreferenceProfileForm form)` — upsert: find-by-userId or new; map axes;
  normalize regions (if `anyRegion` true or list empty → empty set; else the selected countries);
  reject any country not in `availableCountries()` (throw a domain exception the controller maps to a
  field error). Persist.
- `List<String> availableCountries()` — distinct, sorted `country` values from
  `resortRepository.findByActiveTrueOrderByCountryAscNameAsc()`.

#### 3. Service + form unit tests

**File**: `src/test/java/com/nextslope/profile/PreferenceProfileFormValidationTests.java` (standalone
`Validator`), `src/test/java/com/nextslope/profile/PreferenceProfileServiceTests.java` (Mockito).

**Intent**: Verify validation messages, default-form construction, upsert mapping, region normalization
(anyRegion → empty; empty list → empty), and rejection of an out-of-vocabulary country.

**Contract**: `@NotNull` axes produce field errors; new-user load returns the documented defaults; save
on an existing profile updates in place (no duplicate row); a region not in `availableCountries()` is
rejected.

### Success Criteria:

#### Automated Verification:

- Form validation + service tests pass: `./gradlew test --tests "com.nextslope.profile.*"`
- Full suite green: `./gradlew test`

#### Manual Verification:

- None (no UI yet) — covered by Phase 3.

**Implementation Note**: Pause for human confirmation that the suite is green before Phase 3.

---

## Phase 3: Controller, View, Navigation & Gating

### Overview

Wire the route, the Thymeleaf form, the navigation entry points (navbar link + post-signup redirect),
and the gating/isolation tests. This is the phase that makes the page reachable.

### Changes Required:

#### 1. Controller

**File**: `src/main/java/com/nextslope/web/ProfileController.java`

**Intent**: GET seeds the form (saved values or defaults) + country options; POST validates, saves, and
redirects (PRG). Owner is the authenticated principal — never an id from the request.

**Contract**: `@GetMapping("/profile")` — resolve the current user via `@AuthenticationPrincipal
UserDetails` → `userRepository.findByEmail(...)`; add `profileForm` (from `loadFormForUser`) and
`availableCountries` to the model; return `profile/form`. The three enum option lists
(`ExperienceLevel`/`DifficultyBand`/`NoveltyPreference`) are NOT model attributes — the template reads
them via `T(...).values()` SpEL (see §2), so they need no re-adding on the error path. `@PostMapping("/profile")` — `@Valid
@ModelAttribute("profileForm") + BindingResult`; re-render `profile/form` (200) on errors (re-add
`availableCountries`); on the out-of-vocabulary-country exception, `bindingResult.rejectValue(...)`; on
success `redirect:/profile` with a flash "saved" message.

#### 2. Profile view

**File**: `src/main/resources/templates/profile/form.html`

**Intent**: Bootstrap form reusing `fragments/layout` (`head`, `navbar`, `scripts`), following the
`signup.html` error-display idiom.

**Contract**: `th:object="${profileForm}"`, `novalidate`. Experience + novelty as radio/`select` bound
to `*{experienceLevel}` / `*{noveltyPreference}`; difficulty as a preset-band `select`/radio bound to
`*{difficultyBand}`; region as a multi-select / checkbox group over `${availableCountries}` bound to
`*{regionCountries}`, plus an "Any region" checkbox bound to `*{anyRegion}`. The option lists for the
three enums come from SpEL on the enum types — `T(com.nextslope.profile.ExperienceLevel).values()`,
`T(com.nextslope.profile.DifficultyBand).values()`, `T(com.nextslope.profile.NoveltyPreference).values()`
— not from model attributes (only `availableCountries`, being DB-derived, is a model attribute). A small inline script (no
build step) clears country selections when "Any region" is checked; server-side normalization is the
source of truth regardless. Show a success flash and per-field `th:errors`.

#### 3. Navbar entry point

**File**: `src/main/resources/templates/fragments/layout.html`

**Intent**: Give authenticated users a visible way to reach the profile.

**Contract**: In the `sec:authorize="isAuthenticated()"` block, add a `Profile` link (`th:href="@{/profile}"`)
beside the "Signed in as …" text and the Sign out button.

#### 4. Post-signup onboarding redirect

**File**: `src/main/java/com/nextslope/web/AuthController.java`

**Intent**: Send brand-new users straight to the profile to onboard.

**Contract**: Change the signup success redirect from `redirect:/` to `redirect:/profile`
(`AuthController.java:83`).

#### 5. Update the affected signup test

**File**: `src/test/java/com/nextslope/SignupIntegrationTests.java`

**Intent**: Keep the redirect assertion truthful after the onboarding change.

**Contract**: Line 43 `redirectedUrl("/")` → `redirectedUrl("/profile")` (and rename the test method to
reflect the profile destination).

#### 6. Controller gating + view tests

**File**: `src/test/java/com/nextslope/web/ProfileControllerWebMvcTests.java`
(`@WebMvcTest(ProfileController.class) + @Import({SecurityConfig.class, AppUserDetailsService.class})`).

**Intent**: Verify gating, view name, model attributes, and POST validation behavior.

**Contract**: Unauthenticated GET/POST `/profile` → redirect to login (`assertRedirectedToLogin`);
authenticated GET returns `profile/form` with `profileForm` + `availableCountries`; POST missing a
required axis re-renders `profile/form` (200) with errors; valid POST redirects to `/profile`. Mock the
service + `UserRepository` with `@MockitoBean`.

#### 7. Owner-isolation integration test

**File**: `src/test/java/com/nextslope/profile/PreferenceProfileOwnershipIntegrationTests.java`
(extends `TwoUserIntegrationTestBase`).

**Intent**: Prove the privacy guardrail: each user only ever reads/writes their own row; no bleed.

**Contract**: User A saves a profile; user B saves a different profile; each reads back only their own
values; A's row is unchanged by B's save. (Because `/profile` is principal-scoped with no id in the
path, there is no cross-user fetch to assert-forbidden — `assertWrongOwnerDenied` stays a placeholder;
the assertion is isolation.)

### Success Criteria:

#### Automated Verification:

- Controller `@WebMvcTest` gating/view tests pass: `./gradlew test --tests "com.nextslope.web.ProfileControllerWebMvcTests"`
- Owner-isolation integration test passes: `./gradlew test --tests "com.nextslope.profile.PreferenceProfileOwnershipIntegrationTests"`
- Updated signup redirect test passes: `./gradlew test --tests "com.nextslope.SignupIntegrationTests"`
- `PermitListLockTests` still green (`/profile` gated): `./gradlew test --tests "com.nextslope.PermitListLockTests"`
- Full suite green: `./gradlew test`

#### Manual Verification:

- Sign up a new account → land on `/profile` automatically with a defaults-filled form.
- The navbar shows a "Profile" link while signed in; clicking it opens `/profile`.
- Save a profile, sign out, sign back in → saved values are pre-filled (persisted across sessions).
- Edit a value (band, experience, novelty, region set incl. "Any region") and save → change persists.
- A submission with no required axis shows a clear field error and does not save.
- Visiting `/profile` while signed out redirects to the login page.

**Implementation Note**: After automated verification passes, pause for human confirmation of the manual
round-trip before opening the change PR.

---

## Testing Strategy

### Unit Tests:

- Form validation (`@NotNull` axes) via standalone `Validator`.
- Service: default-form construction, upsert mapping, region normalization (anyRegion / empty → empty
  set), out-of-vocabulary country rejection, `availableCountries()` distinct+sorted.
- `DifficultyBand.toMix()` returns the canonical triple summing to 100 for each band.

### Integration Tests:

- `@DataJpaTest`: entity↔schema round-trip incl. `@ElementCollection`; `UNIQUE(user_id)` enforcement.
- Testcontainers Postgres: `V3` applies and entity validates on the prod engine.
- `@WebMvcTest`: gating, view name, model attributes, POST validation/redirect.
- Two-user `@SpringBootTest`: owner isolation (privacy guardrail).

### Manual Testing Steps:

1. New signup → auto-redirect to `/profile` with defaults.
2. Navbar "Profile" link works while authenticated.
3. Save → sign out → sign in → values persist; edit → persists.
4. Required-field error path.
5. Signed-out `/profile` → login redirect.

## Migration Notes

`V3__create_preference_profiles.sql` is forward-only and additive (two new tables, the project's first
FK + first collection table). No existing data is touched. Runs automatically on every boot and test
context start; verified on both engines.

## References

- Research: `context/changes/preference-profile/research.md`
- Roadmap slice S-02: `context/foundation/roadmap.md:94-105`
- PRD FR-004 / US-01 / Guardrails: `context/foundation/prd.md:108-109,57-71,50-55`
- Closest structural analogue: `context/archive/2026-06-21-resort-catalog-browse/plan.md`
- Form pattern: `src/main/java/com/nextslope/web/AuthController.java:43-84`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Domain & Migration

#### Automated

- [x] 1.1 Build compiles (`./gradlew compileJava`) — b7cb28e
- [x] 1.2 `DifficultyBand.toMix()` returns the canonical triple summing to 100 for each band — b7cb28e
- [x] 1.3 `@DataJpaTest` repository/entity tests pass — b7cb28e
- [x] 1.4 Testcontainers Postgres migration proof passes (V3 applies, entity validates) — b7cb28e
- [x] 1.5 Full suite green (no schema-validation boot failures) — b7cb28e

#### Manual

- [x] 1.6 App boots locally via `./gradlew bootRun` (H2 path)

### Phase 2: Service & Form

#### Automated

- [x] 2.1 Form validation + service unit tests pass
- [x] 2.2 Full suite green

### Phase 3: Controller, View, Navigation & Gating

#### Automated

- [ ] 3.1 Controller `@WebMvcTest` gating/view tests pass
- [ ] 3.2 Owner-isolation integration test passes
- [ ] 3.3 Updated signup redirect test passes
- [ ] 3.4 `PermitListLockTests` still green (`/profile` gated)
- [ ] 3.5 Full suite green

#### Manual

- [ ] 3.6 New signup auto-redirects to `/profile` with defaults-filled form
- [ ] 3.7 Navbar "Profile" link opens `/profile` while signed in
- [ ] 3.8 Values persist across sign-out / sign-in; edits persist
- [ ] 3.9 Missing required axis shows a field error and does not save
- [ ] 3.10 Signed-out `/profile` redirects to login
