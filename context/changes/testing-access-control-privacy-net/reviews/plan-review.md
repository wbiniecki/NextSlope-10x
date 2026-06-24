<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Access-control & Privacy Regression Net

- **Plan**: `context/changes/testing-access-control-privacy-net/plan.md`
- **Mode**: Deep
- **Date**: 2026-06-24
- **Verdict**: REVISE → SOUND (all 6 findings fixed in plan, 2026-06-24)
- **Findings**: 0 critical, 4 warnings, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | WARNING |
| Lean Execution | PASS |
| Architectural Fitness | WARNING |
| Blind Spots | WARNING |
| Plan Completeness | WARNING |

## Grounding

Paths checked and present: `SecurityConfig.java`, `RouteGatingTests.java`, `AuthenticationIntegrationTests.java`, `AppUserDetailsService.java`, `User.java`, `context/foundation/test-plan.md`, `AGENTS.md`. Symbol checks confirm production has no `hasRole`, `hasAuthority`, `@PreAuthorize`, or `@EnableMethodSecurity`; `SecurityConfig` is binary `permitAll()` plus `anyRequest().authenticated()`; `RouteGatingTests` still uses `/whatever`; existing integration tests provide the `formLogin`, `MockHttpSession`, `deleteAll()`, and `User.builder()` patterns. Brief-plan consistency passed. Progress-phase consistency passed. `context/foundation/lessons.md` and `docs/reference/contract-surfaces.md` are absent, so those optional checks were skipped.

## Findings

### F1 - Phase 3 role-gating demo lacks a concrete enforcement mechanism

- **Severity**: WARNING
- **Impact**: MEDIUM - real tradeoff; pause to reason through it
- **Dimension**: Plan Completeness / Architectural Fitness
- **Location**: Phase 3, `RoleGatingPatternTests`
- **Detail**: Production `SecurityConfig` only distinguishes permit-listed paths from authenticated paths. There is no `hasRole`, method-security enablement, or production role matcher. If `RoleGatingPatternTests` imports the production chain and adds only a demo controller, an authenticated `USER` will pass `anyRequest().authenticated()` and will not produce the promised `USER -> 403` result. The plan currently says the route can be gated via a test-local `HttpSecurity` rule or method security, leaving the implementer to choose the mechanism.
- **Fix Recommended**: Pin method security in the plan: use a test-local `@TestConfiguration` with `@EnableMethodSecurity` and a demo controller method annotated with `@PreAuthorize("hasRole('ADMIN')")`, while importing production `SecurityConfig` for the anonymous login redirect and authenticated request flow.
  - Strength: Produces the exact anonymous/USER/ADMIN vocabulary without changing production code and gives S-06 an explicit recipe to mirror.
  - Tradeoff: Introduces method-security test wiring before production uses method security.
  - Confidence: HIGH - production role enforcement is confirmed absent, so the role rule must be test-local.
  - Blind spot: S-06 may ultimately choose URL authorization instead of method security; this should be called out if that direction is likely.
- **Decision**: FIXED (Fix Recommended)

### F2 - IDOR and wrong-owner coverage is overpromised

- **Severity**: WARNING
- **Impact**: MEDIUM - real tradeoff; pause to reason through it
- **Dimension**: End-State Alignment
- **Location**: Desired End State, Phase 3, Progress 3.5
- **Detail**: The plan says the Risk #5 vocabulary, including `wrong-owner -> denied`, is proven green and that the access-control + IDOR gate is satisfied for what exists today. The same plan and research also state that no owned resource, user-owned route, profile route, visited-list route, or admin route exists yet. Phase 3 can seed fixtures and vocabulary, but it cannot prove a real wrong-owner denial.
- **Fix Recommended**: Reword the end state and progress gate so Phase 1 "seeds and documents" wrong-owner coverage, while real wrong-owner denial remains required in S-02/S-04 when owned resources exist.
  - Strength: Preserves the valuable harness work without claiming coverage the current application cannot provide.
  - Tradeoff: The test-plan gate remains partially deferred until later slices add real owned routes.
  - Confidence: HIGH - the codebase and research both confirm no owned resource exists.
  - Blind spot: None significant.
- **Decision**: FIXED (Fix Recommended)

### F3 - Permit-list lock wording is broader than the sample-path test proves

- **Severity**: WARNING
- **Impact**: MEDIUM - real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Desired End State, Phase 2
- **Detail**: The plan says the permit-list lock fails CI if "any future edit widens `permitAll()`." The chosen strategy explicitly avoids reflecting over `SecurityFilterChain` and instead uses curated MockMvc sample paths. That can catch widened permit-list entries only when the widened surface is represented by the curated sample set.
- **Fix Recommended**: Narrow the claim to "curated high-value route samples" and list representative must-stay-gated prefixes such as `/profile`, `/visited`, `/recommend`, and `/admin`, with a note that each future slice must add route-specific assertions.
  - Strength: Keeps the lean MockMvc approach while making the coverage boundary honest.
  - Tradeoff: A future unlisted path could still be widened without this specific lock catching it.
  - Confidence: HIGH - the plan's own "no reflection" decision implies sample-based coverage.
  - Blind spot: The exact future prefix list should be aligned with the roadmap when the plan is edited.
- **Decision**: FIXED (Fix Recommended)

### F4 - `/error` closes F13 only on the security-filter axis

- **Severity**: WARNING
- **Impact**: MEDIUM - real tradeoff; pause to reason through it
- **Dimension**: End-State Alignment
- **Location**: Phase 2, `PermitListLockTests`; Desired End State F13 bullet
- **Detail**: `/error` is a better real path than synthetic `/whatever`, and anonymous `/error -> /login` is meaningful. The finding originally assumed `BasicErrorController` may not load under `@WebMvcTest`, so authenticated `/error` might prove only "not redirected" rather than handler reachability.
- **RE-EVALUATED (2026-06-24)**: The premise is **false** for this stack. `ErrorMvcAutoConfiguration` is explicitly part of the Boot 4.0.6 `@WebMvcTest` slice (verified in `spring-boot-webmvc-test-4.0.6.jar` → `META-INF/spring/...AutoConfigureWebMvc.imports`, line 5). `BasicErrorController` is therefore registered, and an authenticated `GET /error` reaches a real handler past the security filter (the existing `RouteGatingTests` already proves "reached past security" via a concrete `404` on `/whatever`). The earlier "may not load" hedge contradicted the plan's own Critical Implementation Details. F13 was under-claimed, not over-claimed.
- **Resolution**: Strengthened the F13 bullet and Critical Implementation Details — authenticated `/error` reaches `BasicErrorController` past the filter, asserted as a non-redirect to `/login` (exact status not pinned, since a direct `/error` hit has no forwarded error attribute and its code is an impl detail). Removed the false "may not load" claim and grounded the wording in the verified slice.
- **Decision**: FIXED (re-evaluated → strengthened, grounded in verified `@WebMvcTest` slice)

### F5 - Phase 2 targeted verification omits planned test classes

- **Severity**: OBSERVATION
- **Impact**: LOW - quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 2 Automated Verification / Progress 2.1
- **Detail**: Phase 2 may put CSRF tests in `CsrfEnforcedTests` and H2 tests in `H2ConsoleProfileTests`, but the targeted command is only `./gradlew test --tests com.nextslope.PermitListLockTests`. The full suite still catches failures, but the phase-specific gate does not verify all Phase 2 deliverables.
- **Fix**: Change the targeted verification to include all Phase 2 test classes, or require CSRF and H2 assertions to live in `PermitListLockTests`.
- **Decision**: FIXED

### F6 - `RouteGatingTests` fate should be explicit

- **Severity**: OBSERVATION
- **Impact**: LOW - quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 2 Changes Required; Migration Notes
- **Detail**: The plan says `PermitListLockTests` may replace or absorb `RouteGatingTests`, and later says to either delete it or fold its canary assertion. That leaves the old `/whatever` proxy's final state open. Keeping both could leave duplicate canary coverage and muddy whether F13 is actually closed.
- **Fix**: Commit to one outcome. Recommended: delete `RouteGatingTests` after carrying the `/whatever` anonymous catch-all canary into `PermitListLockTests`.
- **Decision**: FIXED (Fix Recommended)
