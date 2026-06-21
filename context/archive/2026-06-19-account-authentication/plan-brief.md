# Account & Authentication (S-01) — Plan Brief

> Full plan: `context/changes/account-authentication/plan.md`
> Frame brief: `context/changes/account-authentication/frame.md`

## What & Why

Wire real persisted authentication (`UserDetailsService` + `PasswordEncoder` + sign-up/sign-in/sign-out)
onto the existing F-01 user model, with a deliberate first-admin bootstrap path — kept lean by deferring
the reusable shared layout to S-03. This replaces Spring Security's default in-memory form-login scaffold,
the gap that currently leaves the app with no real accounts.

## Starting Point

F-01 already shipped a complete `User` entity (`email` unique, `passwordHash`, `role` USER/ADMIN, audit
cols), `UserRepository.findByEmail`, and the `V1__create_users.sql` table. `SecurityConfig.java:38` still
uses `formLogin(Customizer.withDefaults())` — no `UserDetailsService`, no `PasswordEncoder`, no sign-up.
All needed dependencies (security, validation, thymeleaf-security, test slices) are on the classpath, and
`index.html` already links to `/login` with inline Bootstrap CDN.

## Desired End State

A visitor can self-register at `/signup` and is auto-signed-in to `/`; a returning user signs in at
`/login` and signs out (returning to `/?logout`); every gated route redirects anonymous requests to
`/login`, backed by the persisted user table; and in prod, when `ADMIN_EMAIL`/`ADMIN_PASSWORD` are set,
a single ADMIN exists at startup from those secrets with no manual SQL (unset secrets are a safe no-op —
no admin, startup still succeeds).

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Auth wiring | Persisted `UserDetailsService` + BCrypt `PasswordEncoder`, custom login page | Canonical Spring Security shape the F-01 entity was built for | Frame |
| Shared base layout | Out of scope (throwaway markup, inline CDN) | Deferred to S-03; keeps the slice lean | Frame |
| Session model | Plain server-side session, no "remember me" | Resolved out of S-01; zero extra code/table | Frame |
| Admin bootstrap | Env-var startup `ApplicationRunner` (create-if-missing) | Keeps secret out of VCS, idempotent, no manual SQL | Plan |
| Post-sign-up | Auto-authenticate, then redirect to `/` | Smoothest flow; no redundant login step | Plan |
| Validation | Email format + password ≥8 + duplicate-email field error | Standard, low-friction, still safe | Plan |
| Redirects | Sign-in → `/`; sign-out → `/?logout` | Recommendation flow (S-05) doesn't exist yet | Plan |
| Email normalization | `trim().toLowerCase()` at all store/lookup sites | Prevents case-variant duplicates & login mismatch on the case-sensitive `UNIQUE(email)` | Plan |
| Testing | Lightweight slices (`@WebMvcTest`/`@DataJpaTest`/security-test) + targeted `@SpringBootTest` tests where session/auto-login must be proven (login, logout, signup auto-login) + dual-engine smokes | Lightest viable slice per AGENTS.md, but session-backed auth needs full context to verify | Plan |

## Scope

**In scope:** persisted auth wiring (`UserDetailsService`, `PasswordEncoder`, filter-chain rewrite);
self-service sign-up with validation + auto-login; throwaway login/signup views + sign-out control on
`index.html`; first-admin env-var bootstrap; slice + security tests.

**Out of scope:** reusable base layout / shared CDN wiring (S-03), HTMX behaviors (S-04; the incidental
inline HTMX script copied from `index.html` is fine — only HTMX-driven interactions are deferred), profile (S-02), browse
(S-03), mark-visited (S-04), admin enforcement/UI (S-06), account deletion (S-07), "remember me",
password reset, email verification, account lockout, and any new Flyway migration.

## Architecture / Approach

Backend-first, then view, in three phases. Phase 1 adds the `PasswordEncoder` bean + `AppUserDetailsService`
+ a session-backed `SecurityContextRepository` bean wired into the chain (so Phase 2's auto-login persists
to the session), and rewrites the single application `SecurityFilterChain` (custom `/login`, extended
permit-list, `/logout`, CSRF on) plus the login view and sign-out control. Phase 2 adds a validated `RegistrationForm`,
`UserRegistrationService` (BCrypt encode, USER role, duplicate handling), an `AuthController` for
`GET/POST /signup`, the signup view, and programmatic auto-login (persisted into the session). Phase 3 adds
an `ApplicationRunner` seeding the first ADMIN from `ADMIN_EMAIL`/`ADMIN_PASSWORD` and the `render.yaml`
secret declarations.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Auth core & sign-in/out | Persisted login/logout + gated routes + login view & sign-out control | Filter-chain rewrite must not disturb the `@Order(1)` H2 chain |
| 2. Self-service sign-up | Validated registration + auto-login + signup view | Auto-login must persist `SecurityContext` to the session, not just the thread |
| 3. First-admin bootstrap | Idempotent env-var ADMIN seed + render secrets | Must safely no-op (no throw) when env vars are unset |

**Prerequisites:** F-01 done (it is — archived). No new migration; all deps present.
**Estimated effort:** ~1–2 sessions across 3 phases.

## Open Risks & Assumptions

- Programmatic auto-login after sign-up requires saving the context via the session-backed
  `SecurityContextRepository`; getting this wrong makes the post-redirect request anonymous.
- The post-sign-in target (`/`) is interim until S-05 (recommendation flow) exists.
- Admin bootstrap relies on operator-supplied Render secrets; if unset in prod, no admin will exist
  (acceptable — surfaced via startup log).

## Success Criteria (Summary)

- A new visitor can sign up, land signed-in, sign out, and sign back in — all gated routes enforced.
- A prod admin exists at startup when the env-var secrets are set (idempotently, no manual SQL); unset secrets are an accepted no-op.
- `./gradlew test` is green across the new slice/security tests and the existing dual-engine smokes.
