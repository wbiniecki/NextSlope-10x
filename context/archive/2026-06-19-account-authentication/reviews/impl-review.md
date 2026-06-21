<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Account & Authentication (S-01)

- **Plan**: context/changes/account-authentication/plan.md
- **Scope**: Phase 3 of 3 (full plan — all phases complete)
- **Date**: 2026-06-20 (multi-model pass 2026-06-20 — 5 independent reviewers; F9 added & fixed; F10–F15 logged; stale re-review table corrected)
- **Verdict**: NEEDS ATTENTION (F10 decision pending; F9 test gap now closed)
- **Findings**: 0 critical, 4 warnings (F1, F2, F9 fixed; F10 open), 5 observations + F11–F15 (multi-model)

## Re-review verification (2026-06-20, corrected)

> **Correction (2026-06-20, multi-model pass):** an earlier version of this table claimed
> "F1–F7 all reproduce... no fixes since" — that was **stale and wrong**. It contradicted the
> per-finding `Decision: FIXED` lines below and the actual source. A five-model independent
> review pass verified the current source after `b8105f5`: the fixes for F1, F2, F3, F5, and F8
> **are present in code** (confirmed at their cited locations). The table below now reflects the
> true state. `./gradlew test` → BUILD SUCCESSFUL.

| Finding | State in current source |
|---|---|
| F1 Session fixation on signup auto-login | ✅ FIXED — `AuthController.java:70-72` rotates session ID |
| F2 Admin bootstrap uncaught unique race | ✅ FIXED — `AdminBootstrap.java:62-66` catches `DataIntegrityViolationException` |
| F3 EmailNormalizer lacks null guard | ✅ FIXED — `EmailNormalizer.normalize` returns `""` for null |
| F4 RegistrationForm minor plan drift | ⚠️ OPEN (accepted) — see F9: custom email regexp re-flagged by multi-model pass |
| F5 Signup email input type inconsistency | ✅ FIXED — `signup.html:23` now `type="email"` |
| F6 Email enumeration via dup message | ⏭️ OPEN (accepted, MVP) |
| F7 Broad DataIntegrityViolationException catch | ⏭️ OPEN (accepted) — re-flagged WARNING by GPT‑5.3, still present |
| F8 No max password length (BCrypt 72-byte) | ✅ FIXED — `@Size(min = 8, max = 72)` on password |

## Multi-model review pass (2026-06-20)

Five independent reviewers (Opus 4.8-high, Sonnet 4.6, Sonnet 4.5, GPT‑5.3 Codex-high, GPT‑5.5)
re-reviewed the implemented source without seeing this file. Verdicts: 3 APPROVED, 2 NEEDS
ATTENTION (both hinged on the now-closed F9 test gap). New findings surfaced and triaged:

| ID | Finding | Models | Status |
|---|---|---|---|
| F9 | Duplicate-email controller path (`EmailAlreadyExistsException` + `DataIntegrityViolationException` catch) had no automated test | 3/5 | ✅ FIXED — added `SignupWebMvcTests.postSignupWithDuplicateEmail...` + `...UniqueConstraintRaces...` |
| F10 | Custom email `regexp` stricter than planned `@Email` (rejects valid forms e.g. `user@localhost`, IDN) | 4/5 | ⏭️ OPEN — decision pending (keep+document vs relax) |
| F11 | HTMX CDN omitted from `login.html`/`signup.html` vs "mirror index.html" | 3/5 | ⏭️ OPEN (harmless) |
| F12 | `@DataJpaTest` slices `@Import(SecurityConfig.class)` just for `PasswordEncoder` (vs AGENTS.md "lightest slice") | 1/5 | ⏭️ OPEN |
| F13 | `RouteGatingTests` asserts 404 (not 200 on a real protected endpoint) for authed user | 1/5 | ⏭️ OPEN |
| F14 | Signup auto-login test asserts `authenticated()` on POST but doesn't follow redirect `GET /` | 1/5 | ⏭️ OPEN |
| F15 | `@Profile("!prod")` added to H2 console chain despite plan's "do not touch" (beneficial) | 1/5 | ⏭️ OPEN (accepted) |

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

## Findings

### F1 — Session fixation on signup auto-login

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/nextslope/web/AuthController.java:66-73
- **Detail**: Post-signup auto-login saves the authenticated `SecurityContext` into the existing HTTP session without rotating the session ID. Spring Security's default `SessionAuthenticationStrategy` (changeSessionId) is bypassed because authentication is established programmatically rather than through the `AuthenticationManager` login flow. An attacker who fixed a session ID on a victim before signup could hijack the authenticated session after registration.
- **Fix**: Call `request.changeSessionId()` (Servlet 3.1+) before `securityContextRepository.saveContext(...)`, or invalidate the existing session and create a new one. Alternatively, delegate through `AuthenticationManager` so default session-fixation protection applies.
  - Strength: Eliminates session-fixation class without changing the signup UX.
  - Tradeoff: Must ensure CSRF token in the signup form still matches the new session (Thymeleaf re-renders on error; on success redirect the new session is fine).
  - Confidence: HIGH — standard Spring Security hardening for programmatic auth.
  - Blind spot: Haven't verified Servlet container session-ID rotation behavior under H2 local dev vs Render prod.
- **Decision**: FIXED (2026-06-20) — `AuthController` now calls `request.changeSessionId()` (guarded by `getSession(false) != null`) before `saveContext`.

### F2 — Admin bootstrap uncaught unique-constraint race

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/nextslope/config/AdminBootstrap.java:58
- **Detail**: `userRepository.save(admin)` has no catch for `DataIntegrityViolationException`. A concurrent first boot or a race with signup on the same email can hit the `UNIQUE(email)` constraint after the pre-check passes, potentially failing application startup. The signup path already handles this race; bootstrap does not.
- **Fix**: Wrap `save()` in a try/catch for `DataIntegrityViolationException` and log INFO ("admin bootstrap skipped — account already exists for …") mirroring the pre-check branch.
  - Strength: Matches the idempotent intent documented in the plan; startup never fails on duplicate.
  - Tradeoff: None significant — mirrors existing signup controller pattern.
  - Confidence: HIGH — identical pattern in `AuthController` line 61.
  - Blind spot: None significant.
- **Decision**: FIXED (2026-06-20) — `save()` wrapped in try/catch for `DataIntegrityViolationException`, logs INFO on duplicate.

### F3 — EmailNormalizer lacks null guard

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/nextslope/user/EmailNormalizer.java:9
- **Detail**: `normalize()` calls `trim().toLowerCase()` without a null check. A null `username` passed to `AppUserDetailsService.loadUserByUsername` would throw `NullPointerException` instead of `UsernameNotFoundException`. Normal form login supplies a string (empty, not null), so the practical risk is low, but the helper is shared across three call sites.
- **Fix**: Guard null/blank at the start of `normalize()` — return empty string or throw `IllegalArgumentException` — and let `AppUserDetailsService` reject via `UsernameNotFoundException`.
- **Decision**: FIXED (2026-06-20) — `normalize()` returns `""` for null input.

### F4 — RegistrationForm minor plan drift

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/main/java/com/nextslope/user/RegistrationForm.java:16-18
- **Detail**: Plan specified minimal `@NotBlank @Email` constraints; implementation uses a Lombok class (not record) with custom `@Email` regexp and user-facing messages. Functionally correct and covered by `RegistrationFormValidationTests`; no behavioral gap.
- **Fix**: No action required — drift is intentional UX improvement. Optionally note in plan addendum.
- **Decision**: SKIPPED (2026-06-20) — intentional, no action.

### F5 — Signup email input type inconsistency

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/resources/templates/signup.html:23
- **Detail**: Signup email field uses `type="text"` with `inputmode="email"` while `login.html` uses `type="email"`. Server-side validation still applies; client-side hint differs.
- **Fix**: Change signup email input to `type="email"` to match `login.html`.
- **Decision**: FIXED (2026-06-20) — signup email field now `type="email"`.

### F6 — Email enumeration via signup duplicate message

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/nextslope/web/AuthController.java:62
- **Detail**: Duplicate signup returns "An account with this email already exists" while login uses a generic invalid-credentials message. Attackers can probe for registered emails via signup. Acceptable for S-01 MVP scope (no rate-limiting in plan).
- **Fix**: No action for S-01; revisit if enumeration becomes a concern.
- **Decision**: SKIPPED (2026-06-20) — accepted for S-01 MVP scope.

### F7 — Broad DataIntegrityViolationException catch on signup

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/nextslope/web/AuthController.java:61
- **Detail**: `DataIntegrityViolationException` catch maps any integrity failure to the duplicate-email field error. Future non-email constraints could be misreported. Currently only `UNIQUE(email)` exists on `users`.
- **Fix**: Log underlying cause at WARN when catching; narrow handling when more constraints are added.
- **Decision**: SKIPPED (2026-06-20) — safe today (only `UNIQUE(email)`); revisit when more constraints land.

### F8 — No max password length (BCrypt 72-byte truncation)

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/nextslope/user/RegistrationForm.java:20-21
- **Detail**: `password` has `@Size(min = 8)` but no maximum. `BCryptPasswordEncoder` silently truncates the input at 72 bytes, so any characters beyond 72 do not affect the stored hash (a 100-char password and its 72-char prefix authenticate identically). Negligible at MVP scale and consistent with the "no extra hardening" scope, but the silent truncation is non-obvious. Found in the 2026-06-20 re-review.
- **Fix**: Add `@Size(min = 8, max = 72)` (or a documented cap) to make the boundary explicit, or accept as an MVP non-issue.
- **Decision**: FIXED (2026-06-20) — `@Size(min = 8, max = 72)` added to `password`.

### F9 — Duplicate-email controller path had no automated test

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: src/test/java/com/nextslope/SignupWebMvcTests.java
- **Detail**: Surfaced by the multi-model pass (3/5 reviewers). The plan's Testing Strategy requires "POST /signup: duplicate email shows the field error", but only the service layer (`UserRegistrationServiceTests`) covered the duplicate case. The controller's `catch (EmailAlreadyExistsException | DataIntegrityViolationException)` branch — including the unique-constraint race backstop — had zero automated coverage, so a regression that turned a duplicate into a 500 would not be caught.
- **Fix**: Add two `@WebMvcTest` cases that mock `UserRegistrationService.register(...)` to throw (a) `EmailAlreadyExistsException` and (b) `DataIntegrityViolationException`, asserting HTTP 200, view `signup`, an `email` field error, and the duplicate message (no redirect).
- **Decision**: FIXED (2026-06-20) — added `postSignupWithDuplicateEmailRendersEmailFieldErrorNotRedirect` and `postSignupWhenUniqueConstraintRacesRendersEmailFieldErrorNotRedirect` to `SignupWebMvcTests`; `./gradlew test` BUILD SUCCESSFUL.
