# Account & Authentication (S-01) Implementation Plan

## Overview

Wire real persisted email/password authentication onto the existing F-01 user model,
replacing Spring Security's default in-memory form-login scaffold. Deliver self-service
sign-up (mints a `USER`), sign-in, and sign-out against `UserRepository`, gate every
non-public route behind a real `UserDetailsService` + role model, and provide a deliberate
first-admin bootstrap path. The UI is throwaway Thymeleaf markup (inline Bootstrap CDN like
the existing `index.html`) — the reusable base layout is deferred to S-03 and HTMX to S-04.

## Current State Analysis

- **Data model is complete and needs no change.** `User` (`src/main/java/com/nextslope/user/User.java:30-58`)
  carries `email` (unique, NOT NULL), `passwordHash` (NOT NULL), a `Role` enum `USER`/`ADMIN`
  (NOT NULL, no default), and audit columns via Hibernate `@CreationTimestamp`/`@UpdateTimestamp`.
  `UserRepository.findByEmail` exists (`src/main/java/com/nextslope/user/UserRepository.java:9`).
  `V1__create_users.sql:1-9` backs it; `password_hash VARCHAR(255)` fits a BCrypt hash. Admin
  seeding adds no schema (it inserts a row), so **no new migration is required**.
- **The gap is purely auth wiring.** `SecurityConfig.filterChain` (`src/main/java/com/nextslope/config/SecurityConfig.java:31-40`)
  uses `formLogin(Customizer.withDefaults())` — Spring's generated login page over an in-memory
  default user — with no `UserDetailsService`, no `PasswordEncoder`, and no sign-up. The permit-list
  (line 36) lists `/`, `/index`, `/actuator/health`, `/css/**`, `/js/**`, `/webjars/**`.
- **Dependencies are all present** (`build.gradle:24-28`): `spring-boot-starter-security`,
  `-validation`, `-thymeleaf`, and `thymeleaf-extras-springsecurity6` (so `sec:authorize` /
  `${#authentication}` work in templates). Test slices on classpath (`build.gradle:38-41`):
  `-security-test`, `-webmvc-test`, `-thymeleaf-test`, `-data-jpa-test`.
- **Views**: only `index.html` and `error.html` exist. Both already inline the Bootstrap 5.3.3 +
  HTMX 2.0.4 CDN tags and `index.html:23` already links to `/login`. No base fragment.
- **Prod secret infra is ready**: `render.yaml:10-22` declares `sync: false` secrets; the
  pattern for adding `ADMIN_EMAIL`/`ADMIN_PASSWORD` is established. `application-prod.properties`
  pulls all secrets from the environment.

### Key Discoveries:

- `formLogin(Customizer.withDefaults())` (`SecurityConfig.java:38`) is the single line that defines
  the throwaway auth — it must become a custom-login-page config with explicit `/login`, `/logout`,
  and `defaultSuccessUrl`.
- `index.html:23` already points its CTA at `/login`, so an authenticated-home + sign-out control
  belongs on `index.html` (it is permit-listed and is the post-logout landing).
- `ddl-auto=validate` everywhere (`application.properties:15`, `application-prod.properties:10`):
  any entity change would fail fast — but this slice changes no entity, so validation stays green.
- Test convention: lightest viable slice (`@WebMvcTest`/`@DataJpaTest`), reserving `@SpringBootTest`
  for the full-context smoke; `*Tests` suffix (per `AGENTS.md` + `NextslopeApplicationTests`).

## Desired End State

- A visitor can open `/signup`, register with email + password, and is **automatically signed in**
  and redirected to `/` (authenticated home).
- A returning user can sign in at `/login` and sign out via a visible control; sign-out returns them
  to the public landing (`/?logout`).
- Every non-public route redirects unauthenticated requests to `/login`; authentication is backed by
  the persisted `User` table through a `UserDetailsService` + BCrypt `PasswordEncoder`.
- In prod, **when `ADMIN_EMAIL`/`ADMIN_PASSWORD` are configured**, a single ADMIN account exists at
  startup, minted from those secrets without any manual SQL, idempotently across restarts. If the
  secrets are unset the runner is a safe no-op (no admin exists) and startup still succeeds — that is
  an accepted state, surfaced via a startup log, not a failure.
- `./gradlew test` is green: new WebMvc/security/service slice tests plus the existing dual-engine
  context smokes pass.

## What We're NOT Doing

- No reusable base layout fragment, no shared Bootstrap/HTMX wiring beyond the existing inline-CDN
  copies (deferred to S-03 / S-04). The inline Bootstrap CDN on the throwaway auth pages **is
  intentional and in scope** — it is not the deferred shared layout. (Resolves the frame's
  "no Bootstrap/HTMX yet" wording, which refers only to the shared/base wiring.)
- No "remember me" / persistent-token login (resolved out of S-01 — plain server-side session).
- No preference profile (S-02), resort browse (S-03), mark-visited (S-04), admin enforcement/UI
  (S-06), or account deletion (S-07).
- No admin self-service signup, no role-management UI, no email verification / confirmation send.
- No password-reset flow, no account-lockout / rate-limiting (out of S-01 scope).
- No new Flyway migration (the model and table already exist; admin bootstrap inserts a row).

## Implementation Approach

Follow the canonical Spring Security persisted-auth shape, layered backend-first then view:
(1) a `PasswordEncoder` bean + a `UserDetailsService` adapting `User` → Spring `UserDetails`, then
rewrite the single application filter chain to use a custom login page, explicit logout, and a
default success URL; (2) a registration service + validated DTO + controller that encodes the
password, assigns `USER`, handles duplicate email, and programmatically authenticates on success;
(3) a startup `ApplicationRunner` that create-if-missing seeds the first ADMIN from env vars.
Views are throwaway, each inlining the Bootstrap CDN already used by `index.html`. Tests use the
lightest slice that proves each unit, with one full-context smoke retained.

## Critical Implementation Details

- **Auto-login after sign-up must persist the security context to the session.** Programmatic
  authentication after registration has to write the `SecurityContext` into the `HttpSession` (via
  the session-backed `SecurityContextRepository`), not just set it on the thread — otherwise the
  user appears authenticated for the current request but is anonymous on the redirect. Use the
  request/response-aware context-repository save, not a bare `SecurityContextHolder` set.
- **CSRF stays enabled for the application chain.** Spring Security enables CSRF by default; the
  `login`, `signup`, and `logout` forms must POST a `_csrf` token (Thymeleaf injects it automatically
  for `th:action` forms). The `/h2-console` chain already disables CSRF in isolation
  (`SecurityConfig.java:26`) — do not touch it.
- **Logout is POST-only by default.** The sign-out control must be a POST form to `/logout`
  (a plain link will 405/404), so FR-003's control lives in markup as a small form, not an anchor.
- **Admin bootstrap must no-op safely.** When `ADMIN_EMAIL`/`ADMIN_PASSWORD` are unset (e.g. local
  dev, tests) the runner must do nothing and never throw; when set and the email already exists it
  must not overwrite or duplicate. This keeps `@SpringBootTest` context loads green without env vars.
- **Email is normalized with `trim().toLowerCase()` everywhere it is stored or looked up.** Apply the
  same normalization in `UserRegistrationService` (before the duplicate pre-check and before save), in
  `AppUserDetailsService.loadUserByUsername` (before `findByEmail`), and in `AdminBootstrap` (before
  the lookup and save). Without this, the case-sensitive `UNIQUE(email)` constraint admits
  case-variant duplicates (`Alice@x.com` vs `alice@x.com`) and a user could fail to log in under a
  differently-cased email. Centralize it in one small helper so all three call sites agree.

## Phase 1: Authentication core & sign-in/out

### Overview

Replace the default form-login with persisted authentication: a BCrypt `PasswordEncoder`, a
`UserDetailsService` over `UserRepository`, and a rewritten application filter chain using a custom
`/login` page, explicit `/logout`, and a default success URL. Add a throwaway login view and an
authenticated-home + sign-out control on `index.html`. After this phase a manually-seeded user can
sign in and out and gated routes are enforced.

### Changes Required:

#### 1. Password encoder bean

**File**: `src/main/java/com/nextslope/config/SecurityConfig.java`

**Intent**: Provide a BCrypt `PasswordEncoder` bean used by both authentication and the registration
service so stored hashes match `password_hash VARCHAR(255)`.

**Contract**: `@Bean PasswordEncoder passwordEncoder()` returning `BCryptPasswordEncoder`.

#### 2. Persisted UserDetailsService

**File**: `src/main/java/com/nextslope/user/AppUserDetailsService.java` (new)

**Intent**: Adapt the persisted `User` to Spring Security's `UserDetails` so authentication validates
credentials against the database and exposes the role as an authority.

**Contract**: `@Service` implementing `UserDetailsService.loadUserByUsername(String email)`; normalizes
the incoming email (`trim().toLowerCase()`, see Critical Implementation Details) before
`UserRepository.findByEmail`, throws `UsernameNotFoundException` when absent, and maps `User` to a
`UserDetails` whose username is the (normalized) email, password is `passwordHash`, and authority is
`ROLE_<role>` (e.g. `ROLE_USER`, `ROLE_ADMIN`). Build it via Spring's `User.withUsername(...)` builder
so the account flags default to enabled, non-locked, and non-expired (credentials included) — S-01 has
no account-disable/lockout concept (out of scope), so these stay at their permissive defaults.

#### 3. Rewrite the application filter chain

**File**: `src/main/java/com/nextslope/config/SecurityConfig.java`

**Intent**: Point form-login at the custom pages, extend the permit-list for the public auth routes
and landing, and wire explicit logout returning to the public landing. The `@Order(1)` H2 chain is
untouched.

**Contract**: In `filterChain` (the `@Order(2)` bean): permit-list adds `/login`, `/signup`;
`formLogin` configured with `.loginPage("/login").defaultSuccessUrl("/", true)` and `.permitAll()`;
`logout` configured with `.logoutSuccessUrl("/?logout").permitAll()`. CSRF remains enabled (default).
The `UserDetailsService` + `PasswordEncoder` beans are picked up by Spring's default `AuthenticationManager`.

#### 4. Shared session-backed SecurityContextRepository

**File**: `src/main/java/com/nextslope/config/SecurityConfig.java`

**Intent**: Give the Phase 2 programmatic auto-login a concrete, session-backed store to save into —
the *same* store this filter chain reads from on the next request — so the post-sign-up redirect is
authenticated rather than anonymous. Spring Security exposes no `SecurityContextRepository` bean by
default, so we declare one explicitly and wire the chain to it.

**Contract**: Expose `@Bean SecurityContextRepository securityContextRepository()` returning
`new HttpSessionSecurityContextRepository()`, and in `filterChain` add
`.securityContext(sc -> sc.securityContextRepository(securityContextRepository()))` so the chain's
read-side and the controller's save-side (Phase 2) are provably the same instance.

#### 5. Auth view controller + login view

**File**: `src/main/java/com/nextslope/web/AuthController.java` (new), `src/main/resources/templates/login.html` (new)

**Intent**: Serve the throwaway `GET /login` page. The page surfaces the failed-login error from the
standard `?error` query param; the post-logout notice is shown on `index.html` (§6), not here, since
sign-out lands on `/?logout`.

**Contract**: `@Controller` with `@GetMapping("/login")` returning `"login"`. `login.html` is a
standalone Bootstrap-CDN page (mirroring `index.html` head/scripts) with a `th:action="@{/login}"`
POST form posting `username` (email) + `password`, conditionally showing a **failed-login alert from
`?error`**, and a link to `/signup`. (No `?logout` handling here — sign-out lands on `/?logout`, not
`/login`, so the post-logout notice lives on `index.html` per §6.)

#### 6. Authenticated home + sign-out control (FR-003)

**File**: `src/main/resources/templates/index.html`

**Intent**: Make the permit-listed landing show a sign-out control when authenticated and the sign-in
link when not, so FR-003 is reachable and testable without a shared layout.

**Contract**: Use `sec:authorize="isAuthenticated()"` to render a `th:action="@{/logout}"` POST form
(sign-out button) plus the signed-in email via `${#authentication.name}`; use `sec:authorize="!isAuthenticated()"`
to keep the existing "Sign in" link **and add a "Create account" link to `@{/signup}`** so first-time
visitors can reach sign-up directly from `/`. Add the `xmlns:sec` namespace to the `<html>` tag. Also render a
small "You've been signed out." alert via `th:if="${param.logout}"` — this is the surface for the
post-logout notice, since `logoutSuccessUrl` lands the user on `/?logout`.

### Success Criteria:

#### Automated Verification:

- Full build + test suite passes: `./gradlew test`
- Route-gating slice test: an anonymous request to a named non-permit-listed path (e.g. `GET /whatever`)
  redirects to `/login`, and a `@WithMockUser` request reaches it (HTTP 200/expected): new `*Tests`
  under `src/test/java/com/nextslope/`
- `AppUserDetailsService` unit/slice test: loading a persisted user yields the correct username +
  `ROLE_<role>` authority; an unknown email throws `UsernameNotFoundException`; **a mixed-case lookup
  (`Alice@x.com`) resolves the lowercase-stored row** (proves normalization is wired before `findByEmail`).
- Full-context persisted-auth test (`@SpringBootTest` + `MockMvc`): a user seeded via `UserRepository`
  with the **real** `PasswordEncoder` can `POST /login` and land authenticated on `/`; bad credentials
  redirect to `/login?error`. This proves `AppUserDetailsService` + BCrypt end-to-end (not just `@WithMockUser`).
- `POST /logout` (full-context + CSRF token) invalidates the session and redirects to `/?logout`.
- Existing dual-engine context smokes still pass: `NextslopeApplicationTests`, `UserRepositoryPostgresTests`

#### Manual Verification:

- Visiting a gated URL while signed out redirects to `/login` — use any non-permit-listed path
  (e.g. `GET /whatever`); Spring Security runs before the dispatcher, so the redirect happens even
  with no controller mapped.
- Signing in with a manually-created user (e.g. seeded via H2 console with a BCrypt hash) lands on `/`.
- The signed-in landing shows the email + a working sign-out button; sign-out returns to `/?logout` and
  that landing shows the "You've been signed out." banner.
- The H2 console still loads locally (the `@Order(1)` chain is intact).
- The unauthenticated landing (`/`) shows both a "Sign in" link and a "Create account" link to `/signup`.

**Implementation Note**: After completing this phase and all automated verification passes, pause for
manual confirmation before proceeding.

---

## Phase 2: Self-service sign-up

### Overview

Add the registration flow: a validated DTO, a registration service that encodes the password and
assigns `USER`, a controller serving `GET /signup` and handling `POST /signup`, a throwaway signup
view, and programmatic auto-login on success redirecting to `/`. Duplicate emails are rejected with a
field-level error.

### Changes Required:

#### 1. Registration form DTO with validation

**File**: `src/main/java/com/nextslope/user/RegistrationForm.java` (new)

**Intent**: Carry and validate sign-up input: a well-formed email and a password of at least 8
characters.

**Contract**: A bean-validated record/class with `@NotBlank @Email String email` and
`@NotBlank @Size(min = 8) String password`. Field names align with the signup form inputs.

#### 2. Registration service

**File**: `src/main/java/com/nextslope/user/UserRegistrationService.java` (new)

**Intent**: Encapsulate creating a `USER` account — encode the password with the `PasswordEncoder`,
persist via `UserRepository`, and signal a duplicate email distinctly so the controller can render a
field error rather than a 500.

**Contract**: `@Service` with `register(String email, String rawPassword)` (or `register(RegistrationForm)`)
that normalizes the email (`trim().toLowerCase()`, see Critical Implementation Details), builds a `User`
with `role = USER`, the normalized email, and the BCrypt hash, saves it, and throws a dedicated
checked/unchecked `EmailAlreadyExistsException` (new) when `findByEmail` on the normalized email already
returns a user (pre-check) — the DB unique constraint remains the backstop.

#### 3. Signup controller with auto-login

**File**: `src/main/java/com/nextslope/web/AuthController.java`

**Intent**: Serve the signup form and process submission: on validation/duplicate failure re-render
the form with errors; on success register, programmatically authenticate, and redirect to `/`.

**Contract**: `@GetMapping("/signup")` returns `"signup"` with an empty `RegistrationForm` model
attribute; `@PostMapping("/signup")` takes `@Valid RegistrationForm` + `BindingResult` +
`HttpServletRequest`/`HttpServletResponse`, returns `"signup"` on binding errors, catches
`EmailAlreadyExistsException` **and `DataIntegrityViolationException`** (the unique-constraint
backstop, in case the pre-check loses a race) — both map to `rejectValue("email", ...)` and re-render,
so a duplicate email never yields a 500. On success it calls the service, then establishes an
authenticated session and returns `"redirect:/"`.

**Collaborator wiring (resolves the non-obvious bits):**

- Inject the **same** `SecurityContextRepository` bean declared in Phase 1 §4
  (`HttpSessionSecurityContextRepository`) — do not `new` a second one — so the saved context lands in
  the store the filter chain reads on the redirect.
- Obtain the strategy via `SecurityContextHolder.getContextHolderStrategy()` (assign to a
  `securityContextHolderStrategy` field).
- Build `userDetails` by calling `AppUserDetailsService.loadUserByUsername(email)` **after** the save,
  so authorities/role come from the persisted row.

**Contract (auto-login snippet — non-obvious session persistence):**

```java
UserDetails userDetails = appUserDetailsService.loadUserByUsername(email); // freshly persisted user
UsernamePasswordAuthenticationToken auth =
        UsernamePasswordAuthenticationToken.authenticated(userDetails, null, userDetails.getAuthorities());
SecurityContext context = securityContextHolderStrategy.createEmptyContext();
context.setAuthentication(auth);
securityContextHolderStrategy.setContext(context);
securityContextRepository.saveContext(context, request, response); // persists into the session (same bean as the chain)
```

#### 4. Signup view

**File**: `src/main/resources/templates/signup.html` (new)

**Intent**: Throwaway Bootstrap-CDN signup page with email + password fields, inline validation error
display, and a link to `/login`.

**Contract**: Standalone page (mirroring `login.html`) with a `th:action="@{/signup}"` POST form
bound to `RegistrationForm` via `th:object`, `th:field` inputs, and `th:errors` messages per field.

### Success Criteria:

#### Automated Verification:

- Full build + test suite passes: `./gradlew test`
- `UserRegistrationService` test: registering encodes the password (hash ≠ raw, `BCrypt` matches),
  assigns `USER`, and a second registration with the same email raises `EmailAlreadyExistsException`;
  **a case-variant registration (`Alice@x.com` after `alice@x.com`) also raises it** (proves
  normalization — one row, not two) (`@DataJpaTest` + the real encoder, or a focused service test)
- `POST /signup` tests: invalid input re-renders with errors (no redirect) — cheap `@WebMvcTest` slice;
  valid input redirects to `/` **with an authenticated session** — `@SpringBootTest` + `MockMvc`
  asserting `authenticated()` after following the redirect (the auto-login writes the context to the
  session, so a pure slice can't prove it — mirrors Phase 1's full-context login test)

#### Manual Verification:

- Submitting `/signup` with a new email lands on `/` already signed in (no separate login step).
- Submitting a too-short password or malformed email re-renders the form with a clear message.
- Submitting an already-registered email shows a duplicate-email field error, not a 500.

**Implementation Note**: After completing this phase and all automated verification passes, pause for
manual confirmation before proceeding.

---

## Phase 3: First-admin bootstrap

### Overview

Add a startup `ApplicationRunner` that create-if-missing mints the first `ADMIN` from
`ADMIN_EMAIL`/`ADMIN_PASSWORD` through the real `PasswordEncoder`, idempotent across restarts and a
safe no-op when the env vars are unset. Declare the two secrets in `render.yaml`.

### Changes Required:

#### 1. Admin bootstrap runner

**File**: `src/main/java/com/nextslope/config/AdminBootstrap.java` (new)

**Intent**: Ensure a single ADMIN exists in environments that supply the credentials, without manual
SQL and without overwriting an existing account.

**Contract**: A Spring `ApplicationRunner` (or `@Bean ApplicationRunner`) that reads `ADMIN_EMAIL` and
`ADMIN_PASSWORD` (e.g. `@Value` with empty defaults / `Environment`); if either is blank it logs at
**INFO** (e.g. "admin bootstrap skipped — ADMIN_EMAIL/ADMIN_PASSWORD not set") and returns — INFO so
the "no admin" state is actually visible under default prod logging, satisfying the brief's "surfaced
via startup log"; otherwise it normalizes the email (`trim().toLowerCase()`, see Critical
Implementation Details) and, if `UserRepository.findByEmail(normalizedEmail)` is empty, saves a `User`
with `role = ADMIN`, the normalized email, and a BCrypt-encoded password; if the user already exists it
makes no change. Logs which branch it took (created / already-present / skipped) without logging the
password.

**Accepted edge case (MVP boundary)**: if a non-admin `USER` already exists at `ADMIN_EMAIL`, the
runner leaves it untouched — there is **no auto-promotion** to `ADMIN` (a seed runner silently mutating
an existing user's role on every boot is surprising and hard to audit; role management proper is S-06).
The operator must pick a dedicated admin email, or change the role manually in the DB.

#### 2. Render secret declarations

**File**: `render.yaml`

**Intent**: Register the admin bootstrap credentials as runtime-only secrets following the existing
`sync: false` pattern.

**Contract**: Add `ADMIN_EMAIL` and `ADMIN_PASSWORD` entries with `sync: false` under `envVars`
(values supplied in the Render dashboard, never committed).

### Success Criteria:

#### Automated Verification:

- Full build + test suite passes: `./gradlew test`
- Bootstrap test: with admin env vars set the runner creates exactly one ADMIN (and is idempotent on
  a second run); with env vars unset it creates nothing and does not throw; **a pre-existing `USER` at
  `ADMIN_EMAIL` is left as `USER`** (no auto-promotion, no duplicate) (focused test using a test
  `Environment`/properties + `@DataJpaTest` or direct runner invocation)
- Context still loads without admin env vars present: `NextslopeApplicationTests` passes

#### Manual Verification:

- Booting locally with `ADMIN_EMAIL`/`ADMIN_PASSWORD` set creates an ADMIN that can sign in; a second
  boot does not duplicate or error.
- Booting locally without the vars set starts cleanly and creates no admin row.
- `render.yaml` shows both secrets as `sync: false`.

**Implementation Note**: After completing this phase and all automated verification passes, pause for
final manual confirmation before opening the PR to `main`.

---

## Testing Strategy

### Unit / Slice Tests:

- `AppUserDetailsService`: loads a persisted user → correct username/authority; missing email →
  `UsernameNotFoundException`; a mixed-case email resolves the lowercase-stored row (normalization).
- `UserRegistrationService`: password is BCrypt-encoded and verifiable, role is `USER`, duplicate
  email raises `EmailAlreadyExistsException` — including a case-variant duplicate (normalization).
- `AdminBootstrap`: creates one ADMIN when configured, idempotent on re-run, no-op + no throw when
  unconfigured.

### Integration / WebMvc + Security Tests:

- Gated route → anonymous request redirects to `/login`; authenticated request reaches it.
- `POST /login` with valid persisted credentials authenticates and lands on `/`; invalid shows `?error`.
- `POST /signup`: invalid input re-renders form with field errors; valid input redirects to `/` with
  an authenticated session; duplicate email shows the field error.
- `POST /logout` ends the session and redirects to `/?logout`.

### Manual Testing Steps:

1. Sign up with a fresh email → confirm auto-login lands on `/` with the email + sign-out shown.
2. Sign out → confirm redirect to `/?logout` and gated routes again redirect to `/login`.
3. Sign back in with the same credentials → confirm success.
4. Attempt sign-up with an existing email and with a 5-char password → confirm clear field errors.
5. Boot with admin env vars → confirm the seeded admin can sign in; reboot → confirm no duplicate.

## Performance Considerations

BCrypt hashing is intentionally slow but negligible at this scale (small user base, low QPS per the
PRD `target_scale`). Default BCrypt strength (10) is appropriate; no tuning needed.

## Migration Notes

No new Flyway migration: the `users` table and `User` entity already exist and are unchanged. Admin
bootstrap inserts a row at runtime through JPA, not via DDL. `ddl-auto=validate` stays green because
no entity mapping changes.

## References

- Frame brief: `context/changes/account-authentication/frame.md`
- Source: `src/main/java/com/nextslope/config/SecurityConfig.java:31-40`,
  `src/main/java/com/nextslope/user/User.java:30-58`,
  `src/main/java/com/nextslope/user/UserRepository.java:9`,
  `src/main/resources/db/migration/V1__create_users.sql:1-9`,
  `src/main/resources/templates/index.html:23`, `render.yaml:10-22`
- Product refs: `context/foundation/prd.md` (FR-001/002/003, Access Control),
  `context/foundation/roadmap.md` (S-01)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Authentication core & sign-in/out

#### Automated

- [x] 1.1 Full build + test suite passes: `./gradlew test`
- [x] 1.2 Route-gating slice test: anonymous request to a named non-permit-listed path (`GET /whatever`) redirects to `/login`; `@WithMockUser` reaches it
- [x] 1.3 `AppUserDetailsService` slice test: persisted user → correct username + `ROLE_<role>`; unknown email → `UsernameNotFoundException`; mixed-case lookup resolves the lowercase-stored row
- [x] 1.4 Full-context persisted-auth test (`@SpringBootTest`): seeded user with real `PasswordEncoder` `POST /login` lands on `/`; bad credentials → `/login?error`
- [x] 1.5 Full-context `POST /logout` (with CSRF token) invalidates the session and redirects to `/?logout`
- [x] 1.6 Existing dual-engine context smokes still pass (`NextslopeApplicationTests`, `UserRepositoryPostgresTests`)

#### Manual

- [x] 1.7 Gated URL (`GET /whatever`, any non-permit-listed path) while signed out redirects to `/login`
- [x] 1.8 Sign-in with a seeded user lands on `/`
- [x] 1.9 Signed-in landing shows email + working sign-out; sign-out returns to `/?logout` and shows the "You've been signed out." banner
- [x] 1.10 H2 console still loads locally (`@Order(1)` chain intact)
- [x] 1.11 Unauthenticated landing (`/`) shows both a "Sign in" and a "Create account" link to `/signup`

### Phase 2: Self-service sign-up

#### Automated

- [ ] 2.1 Full build + test suite passes: `./gradlew test`
- [ ] 2.2 `UserRegistrationService` test: encodes password, assigns `USER`, duplicate raises `EmailAlreadyExistsException`; case-variant duplicate (`Alice@x.com` after `alice@x.com`) also raises it
- [ ] 2.3 `POST /signup`: invalid re-renders with errors (`@WebMvcTest` slice); valid redirects to `/` with an authenticated session (`@SpringBootTest` + `MockMvc`, assert `authenticated()`)

#### Manual

- [ ] 2.4 `/signup` with a new email lands on `/` already signed in
- [ ] 2.5 Too-short password / malformed email re-renders with a clear message
- [ ] 2.6 Already-registered email shows a duplicate-email field error (no 500)

### Phase 3: First-admin bootstrap

#### Automated

- [ ] 3.1 Full build + test suite passes: `./gradlew test`
- [ ] 3.2 Bootstrap test: creates one ADMIN when configured + idempotent; no-op + no throw when unset; pre-existing `USER` at `ADMIN_EMAIL` stays `USER` (no promotion/duplicate)
- [ ] 3.3 Context loads without admin env vars (`NextslopeApplicationTests` passes)

#### Manual

- [ ] 3.4 Boot with admin env vars set creates a sign-in-able ADMIN; reboot does not duplicate/error
- [ ] 3.5 Boot without the vars set starts cleanly and creates no admin row
- [ ] 3.6 `render.yaml` shows both secrets as `sync: false`
