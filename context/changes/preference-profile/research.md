---
date: 2026-06-25T22:45:35+0200
researcher: Wojciech Biniecki
git_commit: 82fe1096902f43a2f6ce2399d84e525b96868815
branch: main
repository: NextSlope-10x
topic: "Preference profile (S-02): reusable patterns, data model, and the S-05 input contract"
tags: [research, codebase, preference-profile, S-02, data-model, recommendation-contract]
status: complete
last_updated: 2026-06-25
last_updated_by: Wojciech Biniecki
---

# Research: Preference profile (S-02) — patterns, data model, and the S-05 input contract

**Date**: 2026-06-25T22:45:35+0200
**Researcher**: Wojciech Biniecki
**Git Commit**: 82fe1096902f43a2f6ce2399d84e525b96868815
**Branch**: main
**Repository**: NextSlope-10x

## Research Question

For the S-02 `preference-profile` slice (a signed-in user creates and edits a 4-axis preference
profile: experience level, preferred difficulty mix, region preference, novelty preference, and the
edits persist across sessions — `roadmap.md:94-105`, FR-004, US-01), gather plan-ready research on:

1. **Reusable patterns** from the already-shipped S-01 (`account-authentication`) and S-03
   (`resort-catalog-browse`) slices — entity, repository, controller, form binding/validation,
   Thymeleaf + HTMX, security wiring, and test scaffolding.
2. **Profile data model** — how a profile attaches to `User`, Flyway migration conventions, enum
   and value-object mapping shapes.
3. **The downstream S-05 input contract** — exactly what the (not-yet-built) recommendation slice
   will consume from the profile, so the model is shaped correctly now and not reworked later.

## Summary

S-02 is a thin, well-precedented vertical slice. Everything it needs has a copyable analogue in the
codebase:

- **It is a self-contained CRUD-on-one-row feature.** A signed-in user has exactly **one** profile
  row; create and edit are the same form (upsert). No new security configuration is needed —
  `.anyRequest().authenticated()` already gates a new `/profile` route, and `PermitListLockTests`
  already lists `/profile` as a must-stay-gated path (`src/test/java/com/nextslope/PermitListLockTests.java:65`).
- **Data model: a separate `preference_profiles` table with a `UNIQUE` `user_id` FK** is the right
  shape (mirrors the package-per-domain, one-entity-per-table convention; the codebase has **zero**
  `@Embeddable`/`@Embedded` and **zero** existing relations). Next migration is **`V3__create_preference_profiles.sql`**.
- **Four axes, persisted as stable values:** experience level and novelty as `@Enumerated(STRING)`
  → `VARCHAR(32)` (the `User.Role` precedent); preferred difficulty mix as three `INTEGER NOT NULL`
  columns reusing the `DifficultyMix(easy, medium, hard)` record shape (summing to 100); region as a
  `String` that **must match `Resort.country`'s vocabulary** (free-text country names from the seed).
- **The single most important "get-it-right-now" decision** is that the **region preference must be
  comparable to `Resort.country`** and the **preferred difficulty mix must reuse the `DifficultyMix`
  semantics** — because S-05 hard-filters on region (`country`) and soft-scores the preferred mix
  against `Resort.getDifficultyMix()`. Diverging vocabularies here would force a model rework when
  S-05 lands.
- **The two genuine unknowns are S-05's, not S-02's blockers:** the difficulty-mix input shape
  (three percentages vs. preset bands) and the soft-axis scoring weights. S-02 should persist
  explicit percentages (lowest-risk) regardless of the eventual UI affordance.

## Detailed Findings

### A. Reusable patterns (from S-01 + S-03)

#### Entity conventions
Entities are Lombok-annotated, map a Flyway-created table, use `IDENTITY` PKs, explicit snake_case
`@Column` names, and Hibernate-managed audit columns (`Instant` via `@CreationTimestamp`/`@UpdateTimestamp`,
never DB defaults). `User` is the canonical template (`src/main/java/com/nextslope/user/User.java:23-57`),
`Resort` the larger example (`src/main/java/com/nextslope/resort/Resort.java:22-119`).

#### Repository conventions
Interfaces extend `JpaRepository<Entity, Long>` with **derived query methods only** (no `@Query`, no
`@Repository` annotation). `findBy…` returns `Optional`/`List`; boolean and ordering suffixes are used
(`src/main/java/com/nextslope/resort/ResortRepository.java:8-13`). For the profile:
`Optional<PreferenceProfile> findByUserId(Long userId)`.

#### Controller + form-handling conventions
Controllers live in `com.nextslope.web`; domain logic in `@Service` classes in the domain package.
The signup flow is the canonical form pattern (`src/main/java/com/nextslope/web/AuthController.java:43-64`):

- GET seeds an empty form DTO into the model under a named attribute, returns a template name.
- POST binds with `@Valid @ModelAttribute("formName")` + `BindingResult` (declared immediately after);
  re-render the same template on error (HTTP 200), `redirect:` on success (PRG).
- Field errors via `bindingResult.rejectValue("field", "code", "message")`.
- CSRF is on by default; `th:action` forms inject the token; tests POST with `.with(csrf())`.

**Obtaining the authenticated user:** `@AuthenticationPrincipal` is **not yet used anywhere**. The
principal name is the user's email (`AppUserDetailsService` maps `User.email` → username,
`User.role` → `ROLE_USER`/`ROLE_ADMIN`, `src/main/java/com/nextslope/user/AppUserDetailsService.java:17-26`).
The recommended pattern for the profile controller is `@AuthenticationPrincipal UserDetails principal`
→ `userRepository.findByEmail(principal.getUsername())`. (Only the signup auto-login touches
`SecurityContext` directly today, `AuthController.java:74-81`.)

#### Thymeleaf view conventions
A decomposed base layout exists at `src/main/resources/templates/fragments/layout.html` exposing
`head(title)`, `navbar`, and `scripts` fragments (Bootstrap 5 + HTMX via CDN). Authenticated pages
should reuse it via `th:replace="~{fragments/layout :: …}"` — the resort views are the model
(`src/main/resources/templates/resorts/list.html:4-6,46`). The legacy `index/login/signup/error`
templates inline their own Bootstrap and should **not** be migrated as part of S-02. The signup
template is the canonical **form** layout (`th:object`, `th:field="*{…}"`, `#fields.hasErrors(...)`,
Bootstrap `is-invalid` + `th:errors`, `novalidate`) — `src/main/resources/templates/signup.html:18-36`.

#### Form DTO + validation
Form DTOs are **plain classes, not entities**, with Jakarta Bean Validation + Lombok
(`src/main/java/com/nextslope/user/RegistrationForm.java:11-23`). They can be unit-tested with a
standalone `Validator` and no Spring context
(`src/test/java/com/nextslope/user/RegistrationFormValidationTests.java:15-34`). Create a
`PreferenceProfileForm` mirroring template `th:field` names with `@NotNull`/`@Min`/`@Max`/custom
messages, plus a sum-to-100 class-level constraint for the difficulty mix.

#### Security wiring
`SecurityConfig` uses a binary gate: a permit-list, else `authenticated()` — no per-route role checks
in the prod chain (`src/main/java/com/nextslope/config/SecurityConfig.java:50-54`). **A new `/profile`
route needs no SecurityConfig change.** Roles are `User.Role.USER`/`ADMIN` → `ROLE_USER`/`ROLE_ADMIN`.
`/profile` is already in the must-stay-gated list of `PermitListLockTests`
(`src/test/java/com/nextslope/PermitListLockTests.java:65`).

#### Test scaffolding
Class suffix is `*Tests`. Slice conventions: `@DataJpaTest` (repository/entity, H2),
`@WebMvcTest(controllers = X) + @Import({SecurityConfig.class, AppUserDetailsService.class})` (route
gating, view names, form errors, `@MockitoBean` collaborators), `@SpringBootTest + @AutoConfigureMockMvc`
(full session flows), and `@SpringBootTest + @Testcontainers` (prod-engine Postgres migration proof).
Owner-only / IDOR tests extend the shared `support/` scaffolding:
- `UserFixtures` — canonical user A / user B / admin (`src/test/java/com/nextslope/support/UserFixtures.java:23-30`).
- `TwoUserIntegrationTestBase` — seeds fixtures, `loginAsUserA()/loginAsUserB()`
  (`src/test/java/com/nextslope/support/TwoUserIntegrationTestBase.java:29-65`).
- `AccessControlAssertions` — `assertRedirectedToLogin`, `assertForbidden`, `assertWrongOwnerDenied`
  (the last is a **placeholder** to specialize once cross-user profile access exists,
  `src/test/java/com/nextslope/support/AccessControlAssertions.java:21-68`).
- `OwnershipPatternIntegrationTests` is the IDOR template to extend
  (`src/test/java/com/nextslope/support/OwnershipPatternIntegrationTests.java:29-40`).

#### Plan shape to mirror (from archived plans)
Both shipped slices used a **3-phase, backend-first vertical** with a `## Progress` checkbox + commit
SHA per phase. S-03's shape (domain & migration → service → UI + gating tests) is the closest analogue:
`context/archive/2026-06-19-account-authentication/plan.md`,
`context/archive/2026-06-21-resort-catalog-browse/plan.md`. (No `research.md` exists in either archive.)

### B. Profile data model

- **Next migration: `V3__create_preference_profiles.sql`** (only `V1__create_users.sql` and
  `V2__create_resorts.sql` exist). DDL idioms to mirror: `BIGINT GENERATED BY DEFAULT AS IDENTITY
  PRIMARY KEY`, `VARCHAR(n)`, `INTEGER`, `TIMESTAMP NOT NULL` (no defaults), named
  `CONSTRAINT uq_… UNIQUE (...)`. Portable across H2 (PostgreSQL mode) + Postgres only
  (`src/main/resources/db/migration/V1__create_users.sql:1-9`, `…/V2__create_resorts.sql:1-32`).
- **Separate table + entity, 1:1 via `UNIQUE (user_id)`.** `User` has **no** relations today, and the
  codebase has **no** `@Embeddable`/`@Embedded` — a dedicated `preference_profiles` table keeps the
  auth table lean and matches the one-entity-per-table convention (`src/main/java/com/nextslope/user/User.java:23-57`).
  This would be the **first foreign key** in the project (`… REFERENCES users(id)`).
- **Enums → `@Enumerated(EnumType.STRING)` + `VARCHAR(32)`**, exactly like `User.Role`
  (`src/main/java/com/nextslope/user/User.java:42-44`, `V1__create_users.sql:5`). Applies to experience
  level and novelty preference. No ORDINAL, no lookup tables, no custom converters anywhere.
- **Preferred difficulty mix → three persisted `INTEGER NOT NULL` columns** (`easy_pct`, `medium_pct`,
  `hard_pct`). The `DifficultyMix(int easy, int medium, int hard)` record is **display-only / not
  persisted** for resorts (it is `@Transient`-computed from slope counts,
  `src/main/java/com/nextslope/resort/DifficultyMix.java:7`, `Resort.java:127-161`). The profile must
  **store** the values — reuse the record as the in-memory shape, but map three real columns (or
  introduce the project's first `@Embeddable`). Sum-to-100 validation belongs in the app layer (no
  CHECK constraints exist yet; a portable `CHECK` would be a new convention).
- **`ddl-auto=validate` everywhere** (`src/main/resources/application.properties:14-15`,
  `application-prod.properties:8-10`) → the new entity **requires** the matching `V3` migration or boot
  fails. Verified on real Postgres by the Testcontainers tests.

**Candidate `V3` DDL (sketch only — not implemented):**

```sql
CREATE TABLE preference_profiles (
    id                  BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id             BIGINT       NOT NULL,
    experience_level    VARCHAR(32)  NOT NULL,
    easy_pct            INTEGER      NOT NULL,
    medium_pct          INTEGER      NOT NULL,
    hard_pct            INTEGER      NOT NULL,
    region_country      VARCHAR(255),
    novelty_preference  VARCHAR(32)  NOT NULL,
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP    NOT NULL,
    CONSTRAINT uq_preference_profiles_user_id UNIQUE (user_id),
    CONSTRAINT fk_preference_profiles_user_id FOREIGN KEY (user_id) REFERENCES users (id)
);
```

### C. The S-05 recommendation input contract

S-05 is a **two-stage** recommender — hard filters, then weighted soft scoring on the survivors
(`context/foundation/prd.md:154`, `roadmap.md:133`). What it consumes from the profile, per axis:

| Profile axis | S-05 stage | Compared against | Stored shape S-02 should use |
|---|---|---|---|
| **Region preference** | **Hard filter** (drop if outside region) | `Resort.country` (`src/main/java/com/nextslope/resort/Resort.java:41-42`); `continent` is uniform `Europe` in the v1 seed so it isn't discriminating | `String` matching the `country` vocabulary (e.g. `"Austria"`), or an enum derived from the seed's distinct countries |
| **Novelty preference** | **Hard filter** when `new-only` (exclude visited); no filter when `revisit-okay` | the user's **visited list** (S-04 resort IDs), not a resort field | two-value enum `NEW_ONLY` / `REVISIT_OKAY` |
| **Experience level** | **Soft score** | `Resort.getDifficultyMix()` (the derived E/M/H profile) — there is no experience scalar on `Resort` | stable, ordered enum (values TBD by S-05; just keep them explicit) |
| **Preferred difficulty mix** | **Soft score** | `Resort.getDifficultyMix()` (`Resort.java:127-161`, `DifficultyMix.java:7`) | three `int`s summing to 100 (same semantics as `DifficultyMix`) |

**Other S-05 inputs that are NOT profile fields** (so S-02 must not embed them): the per-user visited
list (S-04), the active-resort flag (`Resort.active`), and the full catalog as the candidate pool.
Determinism NFR (`prd.md:143`): same profile + visited list + resort set → same three resorts in the
same order — so every stored axis must be an explicit, stable value (no inferred/derived persistence).

**Rationale truthfulness (`prd.md:54,70,125,152`):** the one-line "why this matched you" must echo
**stored** profile values (e.g. "matches your preferred 60/30/10 difficulty mix and is in your
preferred region"). This reinforces storing explicit, human-meaningful values for every axis.

## Code References

- `src/main/java/com/nextslope/user/User.java:23-57` — canonical entity template (Lombok, IDENTITY PK, `@Enumerated(STRING)` `Role`, audit columns).
- `src/main/java/com/nextslope/user/UserRepository.java:7-10` — derived-query repository style (`findByEmail`).
- `src/main/java/com/nextslope/user/RegistrationForm.java:11-23` — form DTO + Jakarta validation pattern.
- `src/main/java/com/nextslope/web/AuthController.java:43-64` — GET-form / POST-validate / re-render-or-redirect pattern.
- `src/main/java/com/nextslope/web/AuthController.java:74-81` — programmatic auth (signup auto-login); the only direct `SecurityContext` touch.
- `src/main/java/com/nextslope/user/AppUserDetailsService.java:17-26` — email = principal username, role → authority.
- `src/main/java/com/nextslope/web/ResortController.java:21-33` — read-only GET controller returning view names (no redirects).
- `src/main/java/com/nextslope/config/SecurityConfig.java:50-54` — permit-list + `anyRequest().authenticated()` gate.
- `src/main/resources/templates/fragments/layout.html:4-35` — base layout fragments (`head`, `navbar`, `scripts`); Bootstrap + HTMX CDN.
- `src/main/resources/templates/signup.html:18-36` — canonical Bootstrap form template (`th:object`, `th:field`, error display).
- `src/main/resources/templates/resorts/list.html:4-6,46` — how authenticated pages reuse the layout fragment.
- `src/main/java/com/nextslope/resort/DifficultyMix.java:7` — `record DifficultyMix(int easy, int medium, int hard)` (display-only).
- `src/main/java/com/nextslope/resort/Resort.java:41-45` — `country` (NOT NULL) + `continent` (nullable) String fields = PRD "location/region".
- `src/main/java/com/nextslope/resort/Resort.java:127-161` — `@Transient getDifficultyMix()` derivation (largest-remainder rounding to 100).
- `src/main/resources/db/migration/V1__create_users.sql:1-9` — users DDL idioms (IDENTITY PK, `VARCHAR(32)` role, named UNIQUE).
- `src/main/resources/db/migration/V2__create_resorts.sql:1-32` — resorts DDL (next version is V3).
- `src/main/resources/application.properties:14-15` — `ddl-auto=validate` (new entity requires a migration).
- `src/test/java/com/nextslope/PermitListLockTests.java:65` — `/profile` already enumerated as must-stay-gated.
- `src/test/java/com/nextslope/resort/ResortControllerWebMvcTests.java:29-31,78-102` — `@WebMvcTest` gating-test template.
- `src/test/java/com/nextslope/support/TwoUserIntegrationTestBase.java:29-65` — two-user login scaffolding for owner-only tests.
- `src/test/java/com/nextslope/support/AccessControlAssertions.java:21-68` — access-control assertion vocabulary (`assertWrongOwnerDenied` is a placeholder to specialize).
- `src/test/java/com/nextslope/support/OwnershipPatternIntegrationTests.java:29-40` — IDOR test template.

## Architecture Insights

- **Binary auth gate, not per-route roles.** New authenticated routes are free (gated by default);
  role checks (USER vs ADMIN) only matter for S-06. The permit-list is the single source of "public".
- **Flyway owns the schema; Hibernate only validates.** Every new entity = a new forward-only,
  dual-engine-portable migration. `V3` is next. Audit timestamps come from Hibernate annotations, not
  DB defaults, so behavior is identical across H2 and Postgres.
- **Form = DTO ≠ entity.** Validation lives on a plain form object; the controller maps service/domain
  exceptions to `bindingResult.rejectValue`. Create/edit is one upsert form because a user has one row.
- **Value objects are records exposed via `@Transient`** for *derived* data; *stored* data needs real
  columns. The profile's preferred mix is stored data — don't copy the resort's transient pattern.
- **One package per domain.** Put the profile in `com.nextslope.user` (it is user-owned) or a new
  `com.nextslope.profile`; controller in `com.nextslope.web` either way.
- **Privacy is an enforced guardrail, not a comment** (`prd.md:55,142`). Profile reads/writes must be
  scoped to the authenticated owner (load-by-`user_id` for the current principal); admins must not see
  it. Specialize `assertWrongOwnerDenied` accordingly.

## Historical Context (from prior changes)

- `context/archive/2026-06-19-account-authentication/plan.md` — established the `web`/`user` package
  split, form-DTO-vs-entity rule, `@Valid`+`BindingResult` re-render-or-redirect, `EmailNormalizer`,
  CSRF-on, and the 3-phase + per-phase-commit-SHA `## Progress` convention. Explicitly deferred the
  preference profile to S-02.
- `context/archive/2026-06-21-resort-catalog-browse/plan.md` — established the resort entity + `V2`
  migration + `DifficultyMix` transient derivation, the `fragments/layout.html` base layout, the
  dual-engine (`@DataJpaTest` + Testcontainers) verification standard, and the read-only browse
  controller pattern. Closest structural analogue for the S-02 plan (domain & migration → service → UI).

## Related Research

- None. No prior `research.md` exists under `context/changes/**` or `context/archive/**` (both shipped
  slices went straight from plan to implementation).

## Open Questions

These are flagged for `/10x-plan` (none block starting S-02):

1. **Difficulty-mix input shape** — three free percentages vs. preset bands (`roadmap.md:102-103`).
   Lowest-risk: persist explicit percentages summing to 100 regardless of the UI affordance, so S-05
   reads the same values it scores against. *Owner: plan-level decision.*
2. **Experience-level enum values** — not specified in the PRD; pick a stable, ordered set now (e.g.
   beginner / intermediate / advanced); the mapping to a resort's `DifficultyMix` is an S-05 concern.
   *Owner: plan-level decision.*
3. **Region granularity** — country-level (discriminating in the v1 Europe seed) vs. continent-level
   (uniform `Europe` today, useful only if coverage expands). Country aligns with `Resort.country`.
   *Owner: plan-level decision; must stay comparable to `Resort.country`.*
4. **Profile package** — `com.nextslope.user` vs. a new `com.nextslope.profile`. *Owner: plan-level.*
5. **Sum-to-100 enforcement** — app-layer validation (consistent with current no-CHECK convention) vs.
   adding the project's first portable `CHECK` constraint. *Owner: plan-level decision.*
6. **Soft-axis scoring weights** — entirely an S-05 unknown (`roadmap.md:139-140`); does not affect the
   S-02 model. *Owner: S-05.*
