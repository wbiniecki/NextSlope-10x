# Admin Resort Management (S-06) Implementation Plan

## Overview

Add an admin-only surface where a signed-in **admin** can **create**, **edit**, and
**deactivate/reactivate** resort entries. Deactivated resorts vanish from browsing and from new
recommendations while existing visited-list references keep working; non-admins get access-denied.
This slice introduces the app's **first role-based authorization enforcement**, its **first
`ResortService`**, and the admin CRUD UI — all on top of groundwork that S-01/S-03/S-04 already laid.

PRD refs: US-03, FR-010, FR-011, FR-012, FR-013. Roadmap slice: S-06.

## Current State Analysis

The slice is **more built-out at the data/read layer than the roadmap assumes, and greenfield at the
admin/authorization layer** (full detail in `context/changes/admin-resort-management/research.md`):

- **Deactivation is already wired end-to-end at the read side.** The `active` column exists
  (`V2__create_resorts.sql:28`, `Resort.java:110`) and every user-facing read path — browse list,
  detail, recommendation candidate pool, profile region dropdown, mark-visited — already filters
  through `findByActiveTrue*`. Deactivating is a **field flip + save**; **no new Flyway migration is
  needed**.
- **Visited references survive deactivation by design.** `visited_resorts` stores a bare `resort_id`
  (no FK, `V4`), unmark skips the active check, and `VisitedResortOwnershipIntegrationTests`
  already proves a mark on a later-deactivated resort can still be unmarked.
- **Authorization is modeled but never enforced per-role.** Production `SecurityConfig` is binary
  (`permitAll` list + `anyRequest().authenticated()`; `SecurityConfig.java:53-57`). Roles resolve to
  `ROLE_ADMIN` (`AppUserDetailsService`), `sec:authorize` is wired, `AdminBootstrap` seeds a prod
  admin from env vars, and `RoleGatingPatternTests` + `PermitListLockTests` (which already lists
  `/admin` as a must-stay-gated path) are ready scaffolding.
- **No `ResortService`, DTO, or custom validator exists.** Resort reads go straight from controllers
  to `ResortRepository`; both existing finders are active-only (`ResortRepository.java:10-12`).
- **`external_id` is a nullable `BIGINT` with `UNIQUE (external_id)`** (`V2:3,31`) — multiple NULLs
  allowed on both H2 and Postgres; a non-null value must be unique.

### Key Discoveries

- `Resort.getDifficultyMix()` (`Resort.java:127-162`) derives easy/medium/hard % from the **sum of
  the three band counts** (`beginner/intermediate/difficultSlopes`) via largest-remainder rounding —
  not from `totalSlopes` (which can differ in seed data).
- The `Resort` entity has ~25 columns but only `name`, `country`, `active` are `NOT NULL`; the PRD
  admin form covers just six facts, so unmanaged columns stay null on create and **must be preserved
  on edit**.
- Post-login redirect is `defaultSuccessUrl("/", true)` → `index.html` (a standalone page that does
  **not** use the shared `layout.html` navbar). `/resorts` and `/profile` do use the navbar fragment.
- `PermitListLockTests` currently only asserts anonymous→login for `/admin`; there is **no USER→403
  assertion yet** (the binary chain couldn't produce one).

## Desired End State

A signed-in admin, after login, sees an **Admin** entry point (landing-page button + navbar link),
opens `/admin/resorts`, and sees **all** resorts (active and inactive). They can add a resort via a
validated form, edit an existing one (untouched fields preserved), and toggle a resort
inactive/active in place. Deactivated resorts immediately disappear from `/resorts` and from
`/recommend`, but any user's prior visited mark on them still works. A non-admin sees no Admin links
and receives HTTP 403 on any `/admin/**` URL; an anonymous visitor is redirected to `/login`.

Verified by: the automated suite below (controller 403/CRUD tests, service tests, repository test,
form-validation tests, deactivation integration test) all green under `./gradlew test`, plus the
per-phase manual checks.

## What We're NOT Doing

- **No percentages input / sum-to-100 `ConstraintValidator`.** Decision: the form collects slope
  **counts**; the easy/medium/hard % mix and `totalSlopes` are **derived**. This reframes US-03's
  literal "percentages sum to 100" acceptance criterion into an inherent property (documented
  deviation). No custom validator is written.
- **No custom 403 page.** The default bare HTTP 403 satisfies the PRD ("access-denied, not the admin
  form").
- **No editing of the full entity field set.** Only the PRD six facts + optional `externalId`. All
  other columns (lat/long, price, season, lift breakdowns, boolean flags, …) stay as-is (null on
  create, preserved on edit).
- **No delete** (deactivate/reactivate only — protects visited-list integrity, FR-013).
- **No audit/change-history, no role-management UI, no admin search/pagination** (out of PRD v1 scope).

## Implementation Approach

Three **vertical** phases (per the roadmap's "split create/edit vs deactivate — never by layer"):

1. **Gate + read-only admin list + navigation + local admin access** — the security foundation and a
   reachable screen.
2. **Create & edit** — the validated form and service write path.
3. **Deactivate & reactivate** — the HTMX toggle and the FR-013 lifecycle guarantees.

Authorization uses a **URL-level** matcher (`/admin/**` → `hasRole("ADMIN")`) in `SecurityConfig` —
the smallest change, consistent with the existing filter-chain style. A new `ResortService`
centralizes all resort writes and admin reads (controllers never touch the repository for writes).
All admin routes live under `/admin/resorts…` so the single URL matcher covers them.

### Review Addendum (2026-07-03)

Phase 1 originally introduced `ResortService`, `AdminResortController`, and
`templates/admin/resorts/list.html` with list-only behavior. As work progressed on the same branch,
those shared files were extended in place for Phases 2-3 (form/edit/toggle). This is intentional to
keep admin behavior cohesive in single ownership points; phase-specific reviews should treat later
behavior in these files as expected carry-forward scope, not accidental scope creep.
For local admin bootstrap observability, policy is to log account creation/skips by email only and
never log plaintext credentials.

## Critical Implementation Details

- **Security matcher ordering.** `.requestMatchers("/admin/**").hasRole("ADMIN")` must be added
  **before** `.anyRequest().authenticated()` in `SecurityConfig.filterChain` — Spring evaluates
  matchers top-down. Anonymous still hits `formLogin` → `/login`; authenticated USER → 403; ADMIN
  passes. Because the gate now lives in `SecurityConfig` itself, `@WebMvcTest` importing
  `SecurityConfig` produces a real USER→403 **without** method security (unlike
  `RoleGatingPatternTests`, which needed a test-only `@EnableMethodSecurity`).
- **Edit must preserve unmanaged fields.** `ResortService.update` loads the existing entity and sets
  only the form-managed fields, then saves — it must never rebuild the entity from scratch (that
  would null out lat/long, price, lift breakdowns, etc.).
- **`totalSlopes` is normalized to the sum of the three band counts on save** (create and edit). This
  keeps the derived % mix truthful and gives "number of slopes" a single coherent meaning. On edit of
  a seed resort whose stored `totalSlopes` diverged from the band sum, saving through the admin form
  re-normalizes it — intended curation behavior.
- **Local admin must be provably non-prod.** The dev admin seed is a `@Profile("!prod")` bean, so it
  can never create a known-credentials admin against Neon/Render (which run the `prod` profile). Do
  **not** set default `ADMIN_EMAIL`/`ADMIN_PASSWORD` in `application.properties` — those are inherited
  by prod.
- **HTMX CSRF is auto-attached** by the `layout.html :: scripts` `htmx:configRequest` listener, so
  every admin template must include that fragment. `hx-post` is written as `th:attr="hx-post=@{…}"`.
- **Resync can revert admin edits — documented, not fixed.** `ResortSeedLoader`'s opt-in
  `nextslope.resort-seed.resync` mode (off by default, unset today) reconciles every CSV-seeded
  resort's facts (name, slopes, lifts, etc. — everything except `active`) back to the CSV on each
  boot where it's enabled. Enabling it after an admin has edited a CSV-seeded resort silently
  reverts those edits (deactivation survives; other fields don't). No protection is built for this
  in this slice — do not enable resync after curating resorts via the admin UI without first
  re-exporting the CSV, or accept the loss.

---

## Phase 1: Admin gate, resort list & navigation

### Overview

Enforce `/admin/**` for admins only, introduce `ResortService` with an un-filtered finder, render a
read-only admin resort list (active + inactive), add the admin navigation entry points, and seed a
local dev admin so the view is testable locally.

### Changes Required:

#### 1. Authorization gate

**File**: `src/main/java/com/nextslope/config/SecurityConfig.java`

**Intent**: Make `/admin/**` reachable only by `ROLE_ADMIN`; everyone else authenticated → 403,
anonymous → existing login redirect.

**Contract**: Add `.requestMatchers("/admin/**").hasRole("ADMIN")` to the `filterChain`
`authorizeHttpRequests` block, positioned **after** the `permitAll()` list and **before**
`.anyRequest().authenticated()`.

#### 2. Un-filtered admin finder

**File**: `src/main/java/com/nextslope/resort/ResortRepository.java`

**Intent**: Let the admin list show inactive resorts too.

**Contract**: Add `List<Resort> findAllByOrderByCountryAscNameAsc()` (no active filter). Existing
active-only finders are unchanged.

#### 3. Resort service (new)

**File**: `src/main/java/com/nextslope/resort/ResortService.java`

**Intent**: Introduce the service seam that owns admin resort reads/writes; Phase 1 adds only the
admin list read.

**Contract**: `@Service`, constructor-injected `ResortRepository`. Method
`List<Resort> listAll()` → `resortRepository.findAllByOrderByCountryAscNameAsc()`. Extended in Phases
2–3.

#### 4. Admin controller (new)

**File**: `src/main/java/com/nextslope/web/AdminResortController.java`

**Intent**: Serve the admin resort list under the gated path.

**Contract**: `@Controller`, `@RequiredArgsConstructor`, injected `ResortService`.
`@GetMapping("/admin/resorts")` seeds `model.addAttribute("resorts", resortService.listAll())` and
returns view `admin/resorts/list`. All routes namespaced under `/admin/resorts`.

#### 5. Admin list template (new)

**File**: `src/main/resources/templates/admin/resorts/list.html`

**Intent**: Show every resort with its active status, mirroring the browse table.

**Contract**: Mirror `resorts/list.html` structure (`table table-hover align-middle` in
`table-responsive`, `head`/`navbar`/`scripts` layout fragments, `xmlns:sec`). Columns: name, country,
top lift, total slopes, total lifts, difficulty-mix badges, **Status** (Active/Inactive), and an
**Actions** column. Stable row ids `th:id="'admin-resort-row-' + ${r.id}"`. Empty-state row. A
`resortSaved` flash `alert alert-success` block (populated in Phase 2). "New resort" button and
per-row "Edit"/toggle controls are added in Phases 2–3.

#### 6. Navbar entry point

**File**: `src/main/resources/templates/fragments/layout.html`

**Intent**: Give admins a persistent link to the admin surface from every layout-based page.

**Contract**: Inside the `navbar` fragment's `sec:authorize="isAuthenticated()"` block, add an
admin-only link `sec:authorize="hasRole('ADMIN')"` → `th:href="@{/admin/resorts}"` labeled "Admin",
styled to match the existing "Profile" button.

#### 7. Landing-page entry point

**File**: `src/main/resources/templates/index.html`

**Intent**: Make the admin view reachable directly from the post-login landing page (which does not
use the navbar fragment).

**Contract**: In the `sec:authorize="isAuthenticated()"` block, add an admin-only button
`sec:authorize="hasRole('ADMIN')"` → `th:href="@{/admin/resorts}"` labeled e.g. "Manage resorts",
beside "Browse resorts".

#### 8. Local dev admin seed (new)

**File**: `src/main/java/com/nextslope/config/DevAdminBootstrap.java`

**Intent**: Create a fixed, known-credentials admin locally so the admin view can be exercised
without setting env vars — never in prod.

**Contract**: `@Component`, `@Profile("!prod")`, `ApplicationRunner`. Create-if-missing an
`User.Role.ADMIN` with a documented dev email/password (e.g. `admin@nextslope.local`), reusing
`EmailNormalizer`, `PasswordEncoder`, and the same `DataIntegrityViolationException` guard as
`AdminBootstrap`. Log the seeded credentials at startup (dev only).

### Success Criteria:

#### Automated Verification:

- Build + full suite pass: `./gradlew test`
- New `AdminResortControllerTests` (`@WebMvcTest` importing `SecurityConfig` + `AppUserDetailsService`,
  `@MockitoBean` collaborators, `existsByEmail→true`): anonymous `GET /admin/resorts` → redirect
  `/login`; `@WithMockUser(roles="USER")` → 403; `@WithMockUser(roles="ADMIN")` → 200 + view
  `admin/resorts/list`.
- `PermitListLockTests` extended: `@WithMockUser(roles="USER") GET /admin/resorts` → 403.
- `ResortRepositoryTests` (`@DataJpaTest`): `findAllByOrderByCountryAscNameAsc()` returns both active
  and inactive resorts in country/name order.

#### Manual Verification:

- Sign in as the dev admin locally; the landing page shows "Manage resorts" and `/resorts` navbar
  shows "Admin"; clicking either opens `/admin/resorts` listing all resorts including inactive.
- Sign in as a normal USER: no admin links appear; direct `GET /admin/resorts` returns 403.
- Confirm `DevAdminBootstrap` is absent under the `prod` profile (no known-credentials admin created).

**Implementation Note**: After completing this phase and all automated verification passes, pause for
manual confirmation before proceeding.

---

## Phase 2: Create & edit resort

### Overview

Add the validated create/edit form, the `ResortService` write path (with `externalId` uniqueness and
unmanaged-field preservation), the controller GET/POST handlers, and the form template with a live
read-only difficulty-mix preview. Wire "New resort" and per-row "Edit" navigation into the admin list.

### Changes Required:

#### 1. Resort form DTO (new)

**File**: `src/main/java/com/nextslope/resort/ResortForm.java`

**Intent**: Bindable, validated DTO for create/edit — never bind the entity.

**Contract**: Lombok POJO with `jakarta.validation` annotations. Fields: `id` (Long, null on create);
`name` (`@NotBlank`); `country` (`@NotBlank`); `highestPoint` (Integer "top lift height",
`@NotNull @PositiveOrZero`); `totalLifts` (Integer "number of lifts", `@NotNull @PositiveOrZero`);
`beginnerSlopes`, `intermediateSlopes`, `difficultSlopes` (Integer counts, each
`@NotNull @PositiveOrZero`); `externalId` (Long, optional/nullable). No class-level validator.

#### 2. Not-found exception (new)

**File**: `src/main/java/com/nextslope/resort/ResortNotFoundException.java`

**Intent**: Give the resort domain its own 404 signal for admin lookups/toggles — deliberately not
`com.nextslope.visited.ResortNotFoundException`, which is scoped to the visited-mark flow ("no
active resort") and reusing it would invert the `visited` → `resort` package dependency direction.

**Contract**: `RuntimeException`, constructor `ResortNotFoundException(Long resortId)` with a message
like `"No resort with id " + resortId`. Thrown by `ResortService.loadForm` and `toggleActive`
(below); caught by `AdminResortController` and mapped to `ResponseStatusException(NOT_FOUND)`,
mirroring the *pattern* `VisitedController` uses for its own (different) not-found exception.

#### 3. Service create/edit/load + uniqueness

**File**: `src/main/java/com/nextslope/resort/ResortService.java`

**Intent**: Own the write path: map form↔entity, normalize `totalSlopes`, preserve unmanaged fields on
edit, enforce `externalId` uniqueness.

**Contract**: Add:
- `ResortForm loadForm(Long id)` — load entity or throw `ResortNotFoundException`; map managed
  fields → form.
- `void create(ResortForm form)` — build a new `Resort` with `active=true`, managed fields set,
  `totalSlopes = beginner+intermediate+difficult`; enforce uniqueness then `save`.
- `void update(Long id, ResortForm form)` — load existing entity, set only managed fields (+
  re-normalize `totalSlopes`), leave all other columns untouched, `save`.
- `externalId` uniqueness: when non-null, look up via new repo finder and reject if it belongs to a
  different id, throwing a domain `DuplicateExternalIdException`; also treat a
  `DataIntegrityViolationException` on save as the same collision (race backstop).

#### 4. externalId finder

**File**: `src/main/java/com/nextslope/resort/ResortRepository.java`

**Intent**: Support the uniqueness pre-check.

**Contract**: Add `Optional<Resort> findByExternalId(Long externalId)`.

#### 5. Controller create/edit handlers

**File**: `src/main/java/com/nextslope/web/AdminResortController.java`

**Intent**: Serve the new/edit forms and handle submissions with the canonical validate → PRG
pattern (mirror `ProfileController`).

**Contract**: Add:
- `@GetMapping("/admin/resorts/new")` → seed empty `resortForm` + a `formAction` = `/admin/resorts`,
  return `admin/resorts/form`.
- `@PostMapping("/admin/resorts")` → `@Valid @ModelAttribute("resortForm") ResortForm form,
  BindingResult bindingResult` (BindingResult immediately follows). On errors re-render
  `admin/resorts/form`. On `DuplicateExternalIdException` → `bindingResult.rejectValue("externalId",
  …)` and re-render. On success → `service.create`, flash `resortSaved`, redirect `/admin/resorts`.
- `@GetMapping("/admin/resorts/{id}/edit")` → `service.loadForm(id)` + `formAction` =
  `/admin/resorts/{id}`, return `admin/resorts/form`. Catch `ResortNotFoundException` →
  `throw new ResponseStatusException(HttpStatus.NOT_FOUND)`.
- `@PostMapping("/admin/resorts/{id}")` → same validation/PRG shape calling `service.update(id, form)`.

#### 6. Form template (new)

**File**: `src/main/resources/templates/admin/resorts/form.html`

**Intent**: One template for create and edit with per-field errors and a live difficulty-mix preview.

**Contract**: Mirror `profile/form.html` — `th:object="${resortForm}"`, `th:field`, per-field
`th:errors` + Bootstrap `is-invalid`/`invalid-feedback d-block`, `th:action="@{${formAction}}"` POST
(CSRF auto-injected). Inputs for the DTO fields incl. optional `externalId`. A read-only preview panel
shows derived easy/medium/hard % and total slopes, recomputed on input via a small inline script that
**replicates the largest-remainder rounding** of `Resort.getDifficultyMix()` so the preview matches
the stored mix. Uses the `head`/`navbar`/`scripts` layout fragments.

#### 7. Wire list navigation

**File**: `src/main/resources/templates/admin/resorts/list.html`

**Intent**: Surface entry points to create and edit (navigation-to-new-screen).

**Contract**: Add a "New resort" button → `@{/admin/resorts/new}`; add a per-row "Edit" link →
`@{/admin/resorts/{id}/edit(id=${r.id})}` in the Actions column. Render the `resortSaved` flash alert.

### Success Criteria:

#### Automated Verification:

- Build + full suite pass: `./gradlew test`
- `ResortFormValidationTests` (standalone `jakarta.validation.Validator`): blank name/country, null or
  negative integer fields each produce violations; a fully valid form produces none.
- `AdminResortControllerTests` extended: `GET /admin/resorts/new` (ADMIN 200 + empty form; USER 403);
  `POST /admin/resorts` invalid → re-renders `admin/resorts/form` with field errors and
  `service.create` never called; valid → `service.create` called, redirect `/admin/resorts`, flash
  set; duplicate `externalId` → field error on `externalId`; `GET /admin/resorts/{id}/edit` populated
  (404 when missing); `POST /admin/resorts/{id}` valid → `service.update` called + redirect.
- `ResortServiceTests`: `create` sets `active=true` and `totalSlopes = sum of bands`; `update`
  preserves an unmanaged field (e.g. `price`/`latitude`) while changing a managed one; `loadForm`
  maps fields; duplicate `externalId` throws `DuplicateExternalIdException`.

#### Manual Verification:

- As admin, open "New resort", watch the % preview + total update as counts are typed, submit a valid
  resort, and see it in both the admin list and the `/resorts` browse list.
- Edit an existing seed resort, change one field, save, and confirm previously-set fields (e.g. price,
  coordinates) are unchanged.
- Submit invalid input (blank name, negative slope count) → clear per-field errors, no save.
- Submit a duplicate `externalId` → a clear field error naming the conflict.

**Implementation Note**: After completing this phase and all automated verification passes, pause for
manual confirmation before proceeding.

---

## Phase 3: Deactivate & reactivate

### Overview

Add the in-place HTMX active-toggle (mirroring the visited toggle), the service method, and the
tests proving FR-013: a deactivated resort disappears from browse + recommendations while existing
visited references still work, and reactivation restores it.

### Changes Required:

#### 1. Service toggle

**File**: `src/main/java/com/nextslope/resort/ResortService.java`

**Intent**: Flip a resort's active state.

**Contract**: `boolean toggleActive(Long id)` — load entity or throw `ResortNotFoundException`
(added in Phase 2), invert `active`, `save`, return the new state. (Mirrors
`VisitedResortService.toggle` returning the resulting boolean.)

#### 2. Controller toggle endpoint

**File**: `src/main/java/com/nextslope/web/AdminResortController.java`

**Intent**: HTMX endpoint returning the updated control fragment.

**Contract**: `@PostMapping("/admin/resorts/{id}/active")` → call `service.toggleActive(id)`, catch
`ResortNotFoundException` → `throw new ResponseStatusException(HttpStatus.NOT_FOUND)` (same mapping
pattern as `VisitedController`, own exception type), set model attrs `resortId` and `active` (names
must match the fragment params), return view `admin/resorts/active-toggle-response`.

#### 3. Toggle response template (new)

**File**: `src/main/resources/templates/admin/resorts/active-toggle-response.html`

**Intent**: The endpoint's return contract — render just the swapped control.

**Contract**: `th:replace="~{admin/resorts/list :: activeToggle(${resortId}, ${active})}"` — mirror
`resorts/visited-toggle-response.html`.

#### 4. Toggle fragment + row wiring + inactive styling

**File**: `src/main/resources/templates/admin/resorts/list.html`

**Intent**: Define the reusable toggle control, render it per row, and visually distinguish inactive
resorts.

**Contract**: Add an `activeToggle(resortId, active)` `th:fragment` inside a never-rendered
`th:block th:if="${false}"` (mirror `visitedToggle`): a button with
`th:attr="hx-post=@{/admin/resorts/{id}/active(id=${resortId})}, data-active=${active}"`,
`hx-swap="outerHTML"`, class `active-toggle`, label "Deactivate"/"Reactivate" by state. Render it in
each row's Actions column via `th:insert`. Apply an inactive row style (e.g. `table-secondary`/muted)
driven by `${r.active}` on initial render.

#### 5. In-place row restyle on swap

**File**: `src/main/resources/templates/fragments/layout.html`

**Intent**: Re-derive the row's active/inactive style after a toggle swap without replacing the `<tr>`.

**Contract**: Extend the existing `htmx:afterSwap` listener with a branch: if the swapped element has
class `active-toggle`, toggle the row's inactive style class from the button's `data-active` (single
source of truth), mirroring the existing `.visited-toggle` branch.

### Success Criteria:

#### Automated Verification:

- Build + full suite pass: `./gradlew test`
- `AdminResortControllerTests` extended: `POST /admin/resorts/{id}/active` with `.with(csrf())` — ADMIN
  → 200 returning `admin/resorts/active-toggle-response` with `service.toggleActive` called; USER →
  403; missing id → 404.
- `ResortServiceTests` extended: `toggleActive` inverts state and returns the new value; missing id
  throws the not-found exception.
- Deactivation integration test (`@SpringBootTest` + `@AutoConfigureMockMvc`, mirror existing
  integration tests): after an admin deactivates a resort, it is absent from the `/resorts` browse
  list and excluded from `/recommend`; a user's prior visited mark on it still unmarks successfully;
  reactivation restores it to browse.

#### Manual Verification:

- As admin, deactivate a resort from the list — the control flips and the row restyles in place with
  no full reload; reactivate flips it back.
- As a normal user, the deactivated resort no longer appears in `/resorts` and is not returned by
  "Recommend resorts"; if the user had marked it visited earlier, they can still unmark it.

**Implementation Note**: After completing this phase and all automated verification passes, pause for
final manual confirmation before opening the PR.

---

## Testing Strategy

### Unit Tests:

- `ResortFormValidationTests` — bean-validation on the DTO (required fields, non-negative integers).
- `ResortServiceTests` — create (`active=true`, `totalSlopes` = band sum), update (unmanaged-field
  preservation), `loadForm` mapping, `externalId` uniqueness, `toggleActive`.

### Integration Tests:

- `AdminResortControllerTests` (`@WebMvcTest`) — the full 403/CRUD/toggle surface with `SecurityConfig`
  imported (URL-level gate) and mocked collaborators.
- Deactivation `@SpringBootTest` test — browse/recommendation exclusion, visited-reference survival,
  reactivation restore.
- `ResortRepositoryTests` (`@DataJpaTest`) — un-filtered finder returns inactive; `findByExternalId`.
- `PermitListLockTests` — USER→403 lock on `/admin/resorts`.

### Manual Testing Steps:

1. Log in as dev admin → reach `/admin/resorts` from both the landing page and the navbar.
2. Create, edit, deactivate, reactivate a resort; watch browse/recommendation reflect the changes.
3. Log in as a plain USER → no admin links; `/admin/resorts` → 403; a prior visited mark on a
   later-deactivated resort still unmarks.

## Performance Considerations

Negligible — the dataset is ~40 resorts, admin traffic is single-user and infrequent. The un-filtered
finder scans the whole small table by design; no indexing or pagination needed at this scale.

## Migration Notes

**No Flyway migration.** The `active` column and `UNIQUE(external_id)` already exist
(`V2__create_resorts.sql`); the next version would be `V5__` but none is required. `ddl-auto=validate`
stays satisfied because no entity mapping changes.

## References

- Research: `context/changes/admin-resort-management/research.md`
- Auth gate seam & role mapping: `SecurityConfig.java:53-57`, `AppUserDetailsService.java:23-26`
- Form/PRG pattern to mirror: `ProfileController.java:33-74`, `templates/profile/form.html`
- HTMX toggle pattern to mirror: `VisitedController.java:30-45`,
  `templates/resorts/visited-toggle-response.html`, `templates/resorts/list.html:82-91`
- Not-found exception pattern to mirror (own type, don't reuse): `visited/ResortNotFoundException.java`
- Admin-gating test template: `RoleGatingPatternTests.java`; lock: `PermitListLockTests.java:87-96`
- Derived mix: `Resort.java:127-162`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename
> step titles. See `references/progress-format.md`.

### Phase 1: Admin gate, resort list & navigation

#### Automated

- [x] 1.1 Build + full suite pass: `./gradlew test` — 5baa268
- [x] 1.2 `AdminResortControllerTests`: anonymous→login, USER→403, ADMIN→200 + view name — 5baa268
- [x] 1.3 `PermitListLockTests` extended: USER→403 on `/admin/resorts` — 5baa268
- [x] 1.4 `ResortRepositoryTests`: `findAllByOrderByCountryAscNameAsc()` returns active + inactive — 5baa268

#### Manual

- [x] 1.5 Dev admin reaches `/admin/resorts` from landing button and navbar; list shows inactive — 5baa268
- [x] 1.6 Normal USER sees no admin links; direct `GET /admin/resorts` → 403 — 5baa268
- [x] 1.7 `DevAdminBootstrap` absent under the `prod` profile — 5baa268

### Phase 2: Create & edit resort

#### Automated

- [x] 2.1 Build + full suite pass: `./gradlew test` — 7b97b71
- [x] 2.2 `ResortFormValidationTests`: required + non-negative integer rules — 7b97b71
- [x] 2.3 `AdminResortControllerTests`: new/create/edit/update handlers incl. dup `externalId` field error — 7b97b71
- [x] 2.4 `ResortServiceTests`: create (`active=true`, `totalSlopes`=band sum), update preserves unmanaged fields, `loadForm`, dup `externalId` throws — 7b97b71

#### Manual

- [ ] 2.5 Create a resort via the form; % + total preview updates live; appears in admin + browse lists
- [ ] 2.6 Edit a seed resort; unmanaged fields (e.g. price/coords) preserved
- [ ] 2.7 Invalid input → per-field errors, no save; duplicate `externalId` → clear field error

### Phase 3: Deactivate & reactivate

#### Automated

- [x] 3.1 Build + full suite pass: `./gradlew test`
- [x] 3.2 `AdminResortControllerTests`: toggle endpoint ADMIN→200 fragment, USER→403, missing→404
- [x] 3.3 `ResortServiceTests`: `toggleActive` inverts + returns state; missing→throws
- [x] 3.4 Deactivation `@SpringBootTest`: deactivated resort absent from browse + recommendation; visited reference survives; reactivate restores

#### Manual

- [ ] 3.5 Admin toggles active/inactive in place (no reload); row restyles
- [ ] 3.6 Deactivated resort gone from browse + not recommended; prior visited mark still unmarkable
