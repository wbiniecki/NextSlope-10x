<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Account & Authentication (S-01)

- **Plan**: `context/changes/account-authentication/plan.md`
- **Mode**: Deep
- **Date**: 2026-06-19
- **Verdict**: SOUND (after fixes — all findings triaged and applied)
- **Findings**: 0 critical · 0 open warnings · 0 open observations (all resolved)

This report consolidates a multi-round review (initial review, an external cross-check, a
re-verification pass, and a final point-by-point triage). Every finding below has been resolved
in the plan/brief/frame; the verdict moved from REVISE to SOUND once the fixes landed.

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | PASS |
| Plan Completeness | PASS |

## Grounding

Paths 9/9 ✓ (`SecurityConfig`, `User`, `UserRepository`, `V1__create_users.sql`, `index.html`,
`render.yaml`, `build.gradle`, `application.properties`, `HomeController`), symbols 5/5 ✓
(`findByEmail`, `formLogin`, `ddl-auto=validate`, `thymeleaf-extras-springsecurity6`,
`HomeController→"index"`), brief↔plan↔frame ✓. The `references/progress-format.md` citation
resolves to `.cursor/skills/10x-plan/references/progress-format.md` (exists).

## Findings (all resolved)

### Auto-login session-context wiring
- **Severity**: WARNING · **Dimension**: Blind Spots / Plan Completeness
- **Detail**: Snippet used `securityContextRepository`/`securityContextHolderStrategy` without saying where they come from or that the filter chain must use a matching session-backed repo.
- **Decision**: FIXED — added Phase 1 §4 (`@Bean HttpSessionSecurityContextRepository` wired via `.securityContext(...)`) + Phase 2 collaborator-wiring block reusing the same bean.

### Duplicate email → possible 500
- **Severity**: WARNING · **Dimension**: Blind Spots
- **Detail**: Controller caught only `EmailAlreadyExistsException`; the unique-constraint backstop could surface as a 500.
- **Decision**: FIXED — Phase 2 §3 also catches `DataIntegrityViolationException` → field error.

### Persisted auth not proven end-to-end
- **Severity**: WARNING · **Dimension**: End-State Alignment / Plan Completeness
- **Detail**: Gating test used `@WithMockUser`; real persisted login was only prose, untracked.
- **Decision**: FIXED — added full-context `@SpringBootTest` `POST /login` criterion + Progress row (1.4).

### Admin "done" criterion ambiguous
- **Severity**: WARNING · **Dimension**: End-State Alignment
- **Detail**: End state asserted admin exists unconditionally; brief said unset-secrets is acceptable.
- **Decision**: FIXED — qualified end state ("when configured"); brief synced.

### Gated-URL test had no named target
- **Severity**: OBSERVATION · **Dimension**: Plan Completeness
- **Decision**: FIXED — named `GET /whatever` in automated + manual checks.

### Bootstrap/HTMX scope wording
- **Severity**: OBSERVATION · **Dimension**: Cross-doc consistency
- **Decision**: FIXED — plan clarifies inline CDN in scope vs deferred shared layout; frame annotated "superseded by plan"; brief HTMX nuance added.

### login.html Intent vs Contract
- **Severity**: WARNING · **Dimension**: Plan Completeness
- **Decision**: FIXED — Intent reworded to `?error` only; post-logout notice on `index.html` (§6).

### Email normalization (rule + tests)
- **Severity**: WARNING · **Dimension**: Blind Spots
- **Detail**: No trim/lowercase → case-variant duplicates and login mismatch on case-sensitive `UNIQUE(email)`; and the rule was untested.
- **Decision**: FIXED — `trim().toLowerCase()` Critical Detail wired into all three contracts; case-variant assertions added to criteria 1.3 + 2.2 and Testing Strategy.

### UserDetails account flags
- **Severity**: OBSERVATION · **Decision**: FIXED — build via Spring `User` builder (enabled/non-expired/non-locked defaults).

### Admin no-promotion edge case
- **Severity**: OBSERVATION · **Decision**: FIXED — documented as accepted MVP boundary + asserted in Phase 3.2.

### Admin skip-log invisible in prod
- **Severity**: OBSERVATION · **Decision**: FIXED — skip now logs at INFO.

### Brief staleness (architecture, decisions)
- **Severity**: OBSERVATION · **Decision**: FIXED — brief Architecture mentions the `SecurityContextRepository` bean; Key Decisions adds the email-normalization row; Testing decision reflects the targeted `@SpringBootTest` tests.

### Sign-up discoverability + index.html verification
- **Severity**: OBSERVATION · **Decision**: FIXED — added "Create account" link to `index.html` §6 and manual rows (1.9 banner, 1.11 unauth links).

### POST /signup "authenticated" underspecified
- **Severity**: OBSERVATION · **Decision**: FIXED — split into `@WebMvcTest` (invalid re-render) + `@SpringBootTest` `authenticated()` assertion (valid auto-login).

### Normalizer helper location
- **Severity**: OBSERVATION · **Decision**: SKIPPED (by request) — left to implementer; tests assert behavior at call sites.

### "Broken" references/progress-format.md
- **Severity**: OBSERVATION · **Decision**: DISMISSED — file exists at `.cursor/skills/10x-plan/references/progress-format.md`; the short name is the project convention.
