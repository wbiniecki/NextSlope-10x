---
date: 2026-07-02T00:00:00+02:00
researcher: binieckw
git_commit: e7ea3f76ac16a09d4552d680543a271456832330
branch: main
repository: NextSlope
topic: "S-06 Admin resort management (create, edit, deactivate) — codebase readiness"
tags: [research, codebase, admin, resort, security, flyway, htmx, validation]
status: complete
last_updated: 2026-07-02
last_updated_by: binieckw
---

# Research: S-06 Admin resort management — codebase readiness

**Date**: 2026-07-02T00:00:00+02:00
**Researcher**: binieckw
**Git Commit**: e7ea3f76ac16a09d4552d680543a271456832330
**Branch**: main
**Repository**: NextSlope

## Research Question

What does the live codebase already provide, and what is missing, to implement roadmap
slice **S-06 `admin-resort-management`**: a signed-in admin reaches an admin-only view and
can **create**, **edit**, and **deactivate** resort entries; deactivated resorts vanish from
browsing and from new recommendations while existing visited-list references keep working;
non-admins get access-denied. (PRD refs: US-03, FR-010–FR-013.)

## Summary

The slice is **more built-out at the data layer than the roadmap assumes, and completely
greenfield at the admin/authorization layer.**

- **Deactivation is already wired end-to-end at the read side.** The `active` column exists in
  `V2__create_resorts.sql` and on the `Resort` entity, and **every** user-facing resort read
  path (browse list, detail, recommendation candidate pool, profile region dropdown, mark-visited)
  already filters through `findByActiveTrue*`. Deactivating a resort is a **field flip + save** —
  **no new Flyway migration is required** for deactivation itself.
- **Visited references survive deactivation by design.** `visited_resorts` stores a bare
  `resort_id` (`Long`, no `@ManyToOne`, no DB FK), unmark skips the active check, and an
  integration test already proves a mark on a later-deactivated resort can still be unmarked.
- **Admin authorization does not exist yet.** Production `SecurityConfig` is binary
  (`permitAll` list + `anyRequest().authenticated()`) — no `hasRole`, no `@EnableMethodSecurity`,
  no admin routes, no admin controller. S-06 introduces the **first role-based authorization** in
  the app. Roles already resolve correctly to `ROLE_ADMIN`, and `sec:authorize` is wired in
  templates, and admin accounts already exist via `AdminBootstrap` — so the primitives are ready.
- **A resort service layer and admin form/validator are net-new.** Resort reads go straight from
  controllers/services to `ResortRepository` (no `ResortService`, no DTO/mapper). There is **no
  `ConstraintValidator` anywhere in the codebase** — the "easy/medium/hard percentages sum to 100"
  rule (FR-011 / US-03) would be the first custom validator written.
- **A PRD↔schema modeling decision is load-bearing:** the PRD/US-03 admin form collects **easy/
  medium/hard percentages**, but the `Resort` entity stores **raw slope counts**
  (`beginnerSlopes` / `intermediateSlopes` / `difficultSlopes`) and *derives* the percentages at
  runtime. The plan must decide whether the admin form collects counts (no sum-to-100 constraint
  needed) or percentages (needs the new validator + a percentage→count mapping).

## Detailed Findings

### A. Resort domain & every resort read path

**Package** `com.nextslope.resort` is lean: `Resort`, `ResortRepository`, `ResortSeedLoader`,
`DifficultyMix` (derived record). **No `ResortService`, `ResortMapper`, or `ResortDto` exists.**

**Entity** — `src/main/java/com/nextslope/resort/Resort.java`
- Stores resort facts as **raw slope counts** (`beginnerSlopes`, `intermediateSlopes`,
  `difficultSlopes`, `totalSlopes`), lift counts, `highestPoint` ("top lift height"), plus a
  nullable unique `externalId` (CSV key), and booleans (`childFriendly`, `snowparks`, …).
- **`active` `Boolean` column** already present (`nullable = false`), with `@CreationTimestamp`
  `createdAt` and `@UpdateTimestamp` `updatedAt` (audit auto-maintained by Hibernate).

```110:119:src/main/java/com/nextslope/resort/Resort.java
	@Column(name = "active", nullable = false)
	private Boolean active;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
```

- Difficulty mix is **derived, not persisted** — `@Transient getDifficultyMix()` computes
  easy/medium/hard percentages (largest-remainder rounding to sum 100) from the three slope counts
  (`Resort.java:127-162`). Proven by `ResortDifficultyMixTests`.

**Repository** — `src/main/java/com/nextslope/resort/ResortRepository.java`
```8:13:src/main/java/com/nextslope/resort/ResortRepository.java
public interface ResortRepository extends JpaRepository<Resort, Long> {

	List<Resort> findByActiveTrueOrderByCountryAscNameAsc();

	Optional<Resort> findByIdAndActiveTrue(Long id);
}
```
Both existing finders are **active-only**. S-06 admin views need un-filtered finders — e.g.
`findAllByOrderByCountryAscNameAsc()` (admin list including inactive) and plain inherited
`findById(id)` (edit/reactivate a deactivated resort). Inherited `save()` covers create/edit/flip.

**Every production resort read site (all already active-filtered):**

| Consumer | Query | Active filter? |
|---|---|---|
| `ResortController.list` (`GET /resorts`) | `findByActiveTrueOrderByCountryAscNameAsc` | Yes |
| `ResortController.detail` (`GET /resorts/{id}`) | `findByIdAndActiveTrue` → 404 | Yes |
| `RecommendationService` candidate pool | `findByActiveTrueOrderByCountryAscNameAsc` | Yes |
| `PreferenceProfileService.availableCountries` (region dropdown) | `findByActiveTrueOrderByCountryAscNameAsc` | Yes |
| `VisitedResortService.toggle` (mark only) | `findByIdAndActiveTrue` | Yes (mark only) |
| `ResortSeedLoader` | `count` / `findAll` / `saveAll` | No (seed path) |

**Recommendation candidate pool** — `src/main/java/com/nextslope/recommendation/RecommendationService.java`
```55:64:src/main/java/com/nextslope/recommendation/RecommendationService.java
	private RecommendationResult recommendFor(Long userId, ProfileSnapshot profile) {
		Set<Long> visited = profile.noveltyPreference() == NoveltyPreference.NEW_ONLY
				? visitedResortService.visitedResortIds(userId)
				: Set.of();

		List<Resort> survivors = resortRepository.findByActiveTrueOrderByCountryAscNameAsc().stream()
				.filter(resort -> !profile.hasRegionFilter()
						|| profile.regionCountries().contains(resort.getCountry()))
				.filter(resort -> !visited.contains(resort.getId()))
				.toList();
```
Deactivated resorts are **already excluded** from recommendations at the source query — S-06 needs
no engine change; flipping `active = false` is sufficient (FR-013).

### B. Visited-list integrity across deactivation (FR-013)

**Entity** — `src/main/java/com/nextslope/visited/VisitedResort.java` stores a plain
`Long resortId` (no `@ManyToOne`). **Migration `V4__create_visited_resorts.sql` has no FK to
`resorts`** — only `UNIQUE(user_id, resort_id)`. Marks are immutable (existence = visited).

**Toggle** — `src/main/java/com/nextslope/visited/VisitedResortService.java`
- **Mark** requires an active resort (`findByIdAndActiveTrue` → `ResortNotFoundException`).
- **Unmark** performs **no** active check → works after deactivation.
- Reading visited ids returns raw ids from `visited_resorts`; no join to `resorts`.

Why deactivation doesn't break visited references: rows store only `resort_id`, there is no FK
cascade, and unmark skips the active check. Already proven by
`VisitedResortOwnershipIntegrationTests.aMarkOnALaterDeactivatedResortCanStillBeUnmarked`.

**UX caveat for the plan:** the browse list is active-only, so a mark on a later-deactivated
resort no longer appears highlighted in the table (its row is gone), though the DB row persists and
is still unmarkable via the toggle endpoint.

### C. Security, roles, and admin-only enforcement (the greenfield part)

**`SecurityConfig`** — `src/main/java/com/nextslope/config/SecurityConfig.java` — main chain is binary:
```53:57:src/main/java/com/nextslope/config/SecurityConfig.java
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/", "/index", "/login", "/signup", "/actuator/health", "/css/**", "/js/**",
						"/webjars/**")
				.permitAll()
				.anyRequest().authenticated())
```
No `hasRole`/`hasAuthority`, no `exceptionHandling`/`accessDeniedPage`, no `@EnableMethodSecurity`.
There is a separate `@Profile("!prod")` H2-console chain (`@Order(1)`).

**Role model** — `src/main/java/com/nextslope/user/User.java`: nested `enum Role { USER, ADMIN }`
stored as a single `VARCHAR(32)` column (`V1__create_users.sql`); no join table, no DB CHECK.
Self-signup always creates `USER` (`UserRegistrationService`).

**Authority mapping** — `src/main/java/com/nextslope/user/AppUserDetailsService.java`:
```23:26:src/main/java/com/nextslope/user/AppUserDetailsService.java
		return User.withUsername(user.getEmail())
				.password(user.getPasswordHash())
				.roles(user.getRole().name())
				.build();
```
`.roles(...)` auto-prefixes `ROLE_`, so `ADMIN` → authority **`ROLE_ADMIN`**. Verified by
`AppUserDetailsServiceTests`. **Use `hasRole("ADMIN")` / `@PreAuthorize("hasRole('ADMIN')")` /
`@WithMockUser(roles = "ADMIN")`** — never `hasRole("ROLE_ADMIN")`.

**Two valid seams for the admin gate (pick one in the plan):**
1. **URL-level:** add `.requestMatchers("/admin/**").hasRole("ADMIN")` before
   `anyRequest().authenticated()` in `SecurityConfig`. Anonymous → `/login` (existing behavior);
   authenticated USER → 403; ADMIN → passes.
2. **Method-level:** add `@EnableMethodSecurity` to a production `@Configuration` +
   `@PreAuthorize("hasRole('ADMIN')")` on admin handlers. This is the exact pattern demonstrated
   (test-only today) in `RoleGatingPatternTests`.

**Admin accounts already exist out-of-band** — `src/main/java/com/nextslope/config/AdminBootstrap.java`
creates an ADMIN from `ADMIN_EMAIL`/`ADMIN_PASSWORD` env vars (create-if-missing; never promotes an
existing USER). Wired in `render.yaml`. **Local-dev gap:** no default local admin — a manual test
needs those env vars set (or an inserted ADMIN row). No self-service admin promotion exists (per PRD).

**403 UX** — no custom access-denied handler; default is bare HTTP 403. `error.html` is a generic
Boot error page (not 403-specific, and `/error` is itself gated). Optional plan item: friendly 403
page + `exceptionHandling().accessDeniedPage(...)`.

### D. UI / form / HTMX / validation conventions to mirror

**Layout** — `src/main/resources/templates/fragments/layout.html` exposes three named fragments
pulled via `th:replace`: `head(title)` (Bootstrap + HTMX CDN + CSRF `<meta>` tags), `navbar`, and
`scripts` (Bootstrap+HTMX JS + an `htmx:configRequest` listener that attaches the CSRF token to
every HTMX request). Pages declare `xmlns:sec` and assemble their own `<body>`.
- Navbar is gated `sec:authorize="isAuthenticated()"` only — **no role-conditional nav yet.** The
  admin nav entry mirrors `index.html`'s pattern using `sec:authorize="hasRole('ADMIN')"`.

**Form pattern (mirror `ProfileController` / `AuthController`):**
- Controller GET seeds a `*Form` model attribute + auxiliary attrs; POST takes
  `@Valid @ModelAttribute("xForm") XForm form, BindingResult bindingResult` (BindingResult must
  immediately follow the validated object), re-renders the same view on `bindingResult.hasErrors()`,
  surfaces service-layer failures via `bindingResult.rejectValue(...)`/`reject(...)`, and on success
  does `redirectAttributes.addFlashAttribute(...)` + `return "redirect:/..."` (POST-redirect-GET).
- Never bind the entity — use a dedicated Lombok `*Form` DTO with `jakarta.validation` annotations.
  For numeric resort fields use `@NotNull` + `@PositiveOrZero`/`@Min` on `Integer` fields.
- Templates: `th:object` + `th:field`, per-field errors via
  `th:errors` + Bootstrap `is-invalid`/`invalid-feedback d-block`; class-level ("sum to 100") errors
  surface through the `#fields.hasGlobalErrors()` block already used in `profile/form.html`. CSRF is
  auto-injected into `th:action` POST forms.

**No custom validator exists anywhere** — the difficulty *preference* is a fixed-triple enum
(`DifficultyBand`), not user-entered percentages, so there is nothing to reuse. The
percentages-sum-to-100 rule is a **net-new** `@Constraint` + `ConstraintValidator<..., ResortForm>`
(only needed if the form collects percentages rather than counts).

**HTMX partial pattern (mirror `VisitedController` for a deactivate/reactivate toggle):** a plain
`@Controller` method sets model attributes matching a fragment's parameters and returns a small
response template (`resorts/visited-toggle-response.html`) that `th:replace`s a parameterized
`th:fragment` defined in the list page inside a never-rendered `th:if="${false}"` block, swapped via
`hx-swap="outerHTML"`. `hx-post` is written as `th:attr="hx-post=@{...}"`. Don't swap the whole row.

**Flash feedback:** `RedirectAttributes.addFlashAttribute("resortSaved", true)` rendered as an
`alert alert-success` block, mirroring `profileSaved` in `resorts/list.html`.

**Admin list template:** mirror `src/main/resources/templates/resorts/list.html` (Bootstrap
`table table-hover align-middle` in `table-responsive`, stable row ids, difficulty-mix badges,
empty-state row). The admin list must show **inactive** resorts too, so it needs an un-filtered
finder.

### E. Test scaffolding & conventions

**Shared support** (`src/test/java/com/nextslope/support/`):
- `UserFixtures` — includes `admin(passwordEncoder)` (`Role.ADMIN`) + `ADMIN_EMAIL`/`ADMIN_PASSWORD`.
- `TwoUserIntegrationTestBase` (`@SpringBootTest` + `@AutoConfigureMockMvc`) — seeds A/B/admin per
  test, exposes `loginAsAdmin()` (real form login → `ROLE_ADMIN` session).
- `AccessControlAssertions` — `assertRedirectedToLogin`, `assertForbidden`, `assertReachedPastSecurity`.
- **`RoleGatingPatternTests` is the documented executable template** for the admin 403 test
  (anonymous → login; `@WithMockUser(roles="USER")` → 403; `roles="ADMIN"` → 200).
- `PermitListLockTests` already lists `/admin` among must-stay-gated paths — extend with the concrete
  admin routes when they exist.

**Slices:** controllers are tested with `@WebMvcTest(controllers = X.class)` +
`@Import({SecurityConfig.class, AppUserDetailsService.class})` + `@MockitoBean` collaborators
(+ stub `userRepository.existsByEmail(...)→true` for `StaleAuthenticatedSessionFilter`, `.with(csrf())`
on POSTs). Full `@SpringBootTest` is reserved for integration/ownership tests.

**Validation tests:** Pattern A — standalone `jakarta.validation` `Validator` on the `*Form`
(fast, no context); Pattern B — MockMvc POST asserting `model().attributeHasFieldErrors(...)` +
`view().name(...)` + service `never()` called.

**Persistence/migration proof:** `@DataJpaTest` (H2, e.g. `ResortRepositoryTests` already tests
active/inactive filtering) + `@SpringBootTest @Testcontainers` real Postgres 16 (e.g.
`ResortRepositoryPostgresTests`). CI runs `./gradlew test` (all, incl. Testcontainers) on push/PR to
`main`.

**PIT mutation testing is scoped to `com.nextslope.recommendation.*` at threshold 90** — the admin/
resort packages are **not** mutation-gated; conventional slice/integration tests suffice.

## Code References

- `src/main/java/com/nextslope/resort/Resort.java:110-162` - `active` column + `@Transient getDifficultyMix()` (counts → percentages)
- `src/main/java/com/nextslope/resort/ResortRepository.java:8-13` - active-only finders; admin needs un-filtered finder(s)
- `src/main/java/com/nextslope/web/ResortController.java:27-41` - browse/detail read paths (active-only) to mirror for admin list/detail
- `src/main/java/com/nextslope/recommendation/RecommendationService.java:55-64` - candidate pool already active-filtered
- `src/main/java/com/nextslope/visited/VisitedResort.java:32-47` - bare `resortId`, no FK → deactivation-safe
- `src/main/java/com/nextslope/visited/VisitedResortService.java:31-47` - mark needs active; unmark does not
- `src/main/java/com/nextslope/config/SecurityConfig.java:47-67` - binary auth chain; where to add `/admin/**` role matcher
- `src/main/java/com/nextslope/user/User.java:42-57` - `Role` enum (USER/ADMIN), single VARCHAR column
- `src/main/java/com/nextslope/user/AppUserDetailsService.java:17-27` - `ROLE_ADMIN` authority mapping
- `src/main/java/com/nextslope/config/AdminBootstrap.java:42-66` - out-of-band admin creation (env vars)
- `src/main/resources/templates/fragments/layout.html:4-65` - head/navbar/scripts fragments + HTMX CSRF wiring
- `src/main/java/com/nextslope/web/ProfileController.java:33-74` - canonical form GET/POST + validation + PRG
- `src/main/java/com/nextslope/web/VisitedController.java:30-45` - HTMX fragment-return pattern for a toggle
- `src/main/resources/templates/resorts/list.html:37-91` - table template + reusable `th:fragment` toggle to mirror
- `src/main/resources/db/migration/V2__create_resorts.sql:28` - `active BOOLEAN NOT NULL DEFAULT TRUE` (already present)
- `src/test/java/com/nextslope/support/RoleGatingPatternTests.java:57-138` - admin 403 test template
- `src/test/java/com/nextslope/support/TwoUserIntegrationTestBase.java:42-77` - `loginAsAdmin()` + seeded admin
- `src/test/java/com/nextslope/PermitListLockTests.java:87-96` - gated-path lock already lists `/admin`
- `build.gradle:65-73` - PIT scoped to recommendation only (admin not gated)

## Architecture Insights

- **Deactivation was designed in ahead of S-06.** The `active` column, active-only finders, and the
  seed loader's "never touch `active` on resync" guarantee mean deactivation is a data-flip, not a
  schema/engine change. The roadmap's own S-06 risk note ("deactivation-not-deletion protects
  visited-list integrity") is already structurally enforced.
- **Layered convention:** one package per domain; controllers → (service) → repository; DTO `*Form`
  objects for binding; Flyway owns schema with `ddl-auto=validate`; audit timestamps via Hibernate
  annotations (portable across H2/Postgres). S-06 should introduce a `ResortService` to centralize
  create/edit/deactivate + validation rather than fattening the controller.
- **Authorization has been *modeled* (roles, authorities, admin bootstrap, test scaffolding, a
  `/admin` gated-path placeholder) but never *enforced* per-role.** S-06 is the deliberate first
  consumer of that groundwork — a small, well-scaffolded addition, not a security redesign.
- **The one genuine open modeling question is percentages-vs-counts** (below), which the plan must
  resolve because it determines whether a custom validator + mapping layer is in scope.

## Historical Context (from prior changes)

- `context/archive/2026-06-21-resort-catalog-browse/` (S-03) — established the `Resort` entity,
  `V2__create_resorts.sql` (incl. `active`), the CSV `ResortSeedLoader` (idempotent empty-table
  guard; `resync` never touches `active`), and the `resorts/list.html` table + browse/detail
  controllers the admin views mirror.
- `context/archive/2026-06-19-account-authentication/` (S-01) — modeled the `Role` enum,
  `AppUserDetailsService` `ROLE_*` mapping, `AdminBootstrap`, and the `UserFixtures` /
  `TwoUserIntegrationTestBase` / `AccessControlAssertions` / `RoleGatingPatternTests` scaffolding.
- `context/archive/2026-06-26-mark-visited/` (S-04) — the HTMX toggle pattern (`VisitedController` +
  fragment) and the FK-free `visited_resorts` design that keeps deactivation safe.
- `context/archive/2026-06-26-three-resort-recommendation/` (S-05) — the active-only candidate pool
  the deactivation guarantee flows into.

## Related Research

- `context/archive/2026-06-26-three-resort-recommendation/research.md`
- `context/archive/2026-06-21-resort-catalog-browse/` (plan + reviews)
- `context/foundation/test-plan.md` §6.3–6.4 (access-control / role-gating recipes) and §7 (S-06 form validation)

## Open Questions

1. **Percentages vs. slope counts (must resolve before/at planning).** US-03/FR-011 describe the
   admin form as easy/medium/hard **percentages summing to 100**, but the `Resort` entity persists
   raw slope **counts** and derives percentages. Options: (a) admin form collects counts (matches
   storage, recommendation, and difficulty-mix derivation directly; **no sum-to-100 validator
   needed**, but "percentages sum to 100" from US-03's acceptance criteria is then reframed); or
   (b) admin form collects percentages + a total-slopes figure, add the first custom
   `ConstraintValidator` (sum to 100) and a percentage→count mapping. This choice drives validator
   scope and the acceptance-criteria wording.
2. **Authorization seam:** URL-level `requestMatchers("/admin/**").hasRole("ADMIN")` vs method-level
   `@EnableMethodSecurity` + `@PreAuthorize`. URL-level is the smaller change and matches the
   existing filter-chain style; method-level matches the `RoleGatingPatternTests` template. Pick one.
3. **Deactivate control interaction:** HTMX in-place toggle (mirror `VisitedController`) vs a plain
   POST-redirect with a flash message. The toggle is more consistent with S-04/S-05; the PRG form is
   simpler and matches the create/edit flow. Consistency vs. simplicity — a plan-level call.
4. **Custom 403 UX:** accept the bare default 403, or add a friendly access-denied page +
   `exceptionHandling().accessDeniedPage(...)`. PRD only requires "access-denied, not the admin
   form," which the default 403 already satisfies.
5. **Admin-created resorts & `externalId`:** CSV-seeded resorts carry a unique `externalId`;
   admin-created ones would use `externalId = NULL` (nullable; multiple NULLs allowed under the
   UNIQUE constraint on both H2 and Postgres). Confirm this is the intended convention.
6. **Local-dev admin access:** no default local admin exists (bootstrap needs `ADMIN_EMAIL`/
   `ADMIN_PASSWORD`). Decide how a developer signs in as admin to exercise the view locally.
