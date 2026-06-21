# Frame Brief: Account & Authentication (S-01)

> Framing step before /10x-plan. This document captures what is *actually*
> at issue, separated from what was initially assumed.

## Reported Observation

The scope to take on is roadmap slice **S-01 (`account-authentication`)**: a visitor
can sign up with email + password, sign in, and sign out; gated routes are enforced
against a real persisted user + role model, replacing Spring Security's default
in-memory form-login scaffold. (PRD: FR-001/002/003, Access Control.)

## Initial Framing (preserved)

- **User's stated cause or approach**: F-01 left the user data model in place; this
  slice wires real authentication on top of it, replacing the default-user scaffold.
- **User's proposed direction**: Implement the auth slice as written in the roadmap
  (S-01) and PRD (FR-001/002/003, Access Control).
- **Pre-dispatch narrowing**: Layout scope — initially "not separated yet, let's chat";
  **resolved in discussion: base layout is OUT of S-01** — auth ships with throwaway
  markup, S-03 (browse) owns the real reusable base layout, HTMX lands at S-04. Admin
  seeding — user chose **include it** in S-01. Intent — **mostly confirm** the framing,
  just nail down the scope seams.

## Dimension Map

The S-01 framing could break (or leave a gap) at any of these dimensions:

1. **Data model sufficiency** — does auth need entity/schema changes, or did F-01
   genuinely leave a complete model? (Framing assumes complete.)
2. **Auth wiring** — the actual gap: persisted authentication (`UserDetailsService`,
   `PasswordEncoder`) + sign-up/sign-in/sign-out flow replacing default form-login.  ← initial framing
3. **View/layout scope boundary** — does S-01 own the shared base layout + CDN assets,
   or is that a separate slice? (Roadmap parks it here; user moved it OUT — see below.)
4. **Admin role bootstrap** — sign-up only mints `USER`; how does the first `ADMIN`
   ever come to exist before S-06's admin enforcement?

## Hypothesis Investigation

| Hypothesis | Evidence | Verdict |
| --- | --- | --- |
| Dim 1: Data model needs rework for auth | `User.java` has `email` (unique), `password_hash`, `role` enum USER/ADMIN, audit cols; `UserRepository.findByEmail`; `V1__create_users.sql` (`password_hash VARCHAR(255)` fits BCrypt). Model is complete for auth. | NONE (model is sufficient) |
| Dim 2: Real auth wiring is the actual work (initial framing) | `SecurityConfig.java:38` uses `formLogin(Customizer.withDefaults())` with no `UserDetailsService`/`PasswordEncoder`/sign-up — pure default scaffold. This is exactly the gap. | STRONG |
| Dim 3: Shared base layout belongs in S-01 | Only `index.html` + `error.html` exist — no base fragment. Roadmap parks layout here, but user **overrode**: S-01 ships throwaway markup, S-03 owns the reusable base layout, HTMX at S-04. Caveat: FR-003 sign-out must still be reachable/testable in S-01 (minimal sign-out control in throwaway markup). | OUT OF SCOPE (user decision) |
| Dim 4: Admin bootstrap is an unmet need in S-01 | PRD: admin assignment is "out-of-band, not self-service"; sign-up creates a `USER`; `role` is `NOT NULL` with no default seed. No path exists for an ADMIN to exist. User elected to include seeding in S-01. | STRONG (scope addition) |

## Narrowing Signals

- The data model already carries `role` USER/ADMIN and a unique `email` — confirms
  the framing that S-01 only adds *behavior* (auth wiring), not *structure*.
- No base layout fragment exists; auth pages need chrome — but user chose throwaway
  markup for S-01 and deferred the reusable base layout to S-03 (designed against
  richer views). S-01 keeps only a minimal sign-out control to satisfy FR-003.
- Sign-up's USER-only output + a NOT NULL role column with no seed proves admin
  bootstrap is a genuine gap, not a duplicate of S-06 (which only *enforces* admin).

## Cross-System Convention

Standard Spring Security: a persisted `UserDetailsService` over the existing
`UserRepository` + a `PasswordEncoder` (BCrypt) bean + form-login pointed at custom
sign-in/sign-up pages, with the public permit-list extended for those routes. The
leading hypothesis (Dim 2) matches this convention exactly; the F-01 entity was
clearly shaped for it.

## Reframed (or Confirmed) Problem Statement

> **The actual problem to plan around is**: wire real persisted authentication
> (UserDetailsService + PasswordEncoder + sign-up/sign-in/sign-out) onto the
> existing F-01 user model, with a deliberate first-admin bootstrap path — kept lean
> by deferring the reusable shared layout to S-03.

The initial framing was **correct and held up** — F-01 left a complete model and the
work is the auth wiring. Frame's value-add is settling two scope seams the roadmap
left open: (a) the shared base layout is **deferred out of S-01** (user decision) —
S-01 ships throwaway markup with a minimal sign-out control, and S-03 builds the
reusable base layout (HTMX at S-04); (b) admin seeding is **in scope** because no
ADMIN can otherwise exist before S-06. Neither changes the *direction*.

## Resolved Unknowns

- **Session persistence / "remember me"** (roadmap S-01's open unknown; PRD FR-002
  calls it an implementation choice) — **resolved: plain server-side session, no
  "remember me" in S-01.** Spring Security `formLogin` default; zero extra code/table.
  "Remember me" is a clean additive enhancement deferrable to post-MVP if login
  friction proves real — it never requires reworking S-01.

## Confidence

**HIGH** — strong direct evidence (every load-bearing file read), the wiring matches
the canonical Spring Security convention, and the two scope decisions were confirmed
by the user. No reproduction needed.

## What Changes for /10x-plan

Plan the auth wiring as framed. **In scope:** (1) throwaway/minimal Thymeleaf markup
for sign-in/sign-up plus a minimal sign-out control (FR-003) — NO reusable base layout,
NO Bootstrap/HTMX yet
[superseded by plan.md: the inline Bootstrap CDN tags on the throwaway auth pages ARE in scope —
only the reusable/shared base layout is deferred to S-03];
(2) a first-admin bootstrap mechanism — decision left to /10x-plan, but user
**leans toward env-var startup bootstrap** (a CommandLineRunner that create-if-missing
mints the admin from `ADMIN_EMAIL`/`ADMIN_PASSWORD` Render secrets through the real
PasswordEncoder; keeps the secret out of VCS, idempotent on restart). Alternatives
considered and not preferred: Flyway seed migration (hash baked into committed SQL) and
a documented manual SQL step (not reproducible). **Explicitly out of scope:** the reusable base
layout + Bootstrap CDN (deferred to S-03)
[superseded — see plan.md: the inline Bootstrap CDN itself stays in scope for the throwaway auth
pages; only the shared/base layout is deferred], HTMX (S-04), profile (S-02), admin
enforcement/UI (S-06), account deletion (S-07).

## References

- Source files: `src/main/java/com/nextslope/config/SecurityConfig.java:31-40`,
  `src/main/java/com/nextslope/user/User.java:30-58`,
  `src/main/java/com/nextslope/user/UserRepository.java:7-10`,
  `src/main/resources/db/migration/V1__create_users.sql:1-9`
- Product refs: `context/foundation/prd.md` (FR-001/002/003, Access Control),
  `context/foundation/roadmap.md` (S-01), `context/foundation/tech-stack.md` (base fragment / CDN)
- Related research: none (no `research.md` for this change)
- Investigation tasks: none dispatched — small surface, evidence gathered by direct read
