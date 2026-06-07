---
project: "NextSlope"
version: 1
status: draft
created: 2026-05-24
context_type: greenfield
product_type: web-app
target_scale:
  users: small
  qps: low
  data_volume: small
timeline_budget:
  mvp_weeks: 3
  hard_deadline: 2026-06-28
  after_hours_only: true
---

# NextSlope — Product Requirements Document

## Vision & Problem Statement

An avid skier or snowboarder planning the upcoming winter season — typically Oct–Dec, mapping out one to three trips for the months ahead — faces decision paralysis: there are hundreds of resorts to consider, the comparison is multi-criteria (top lift height, slope count, lift count, difficulty mix, location, snow record, vibe), and the relevant data is scattered across resort websites, ski blogs, Reddit threads, and YouTube reviews. They currently spend hours juggling browser tabs and still finish with a fuzzy comparison and a low-confidence pick — often defaulting back to a familiar resort or a friend's recommendation, missing better-fit destinations they have never heard of.

The insight: no existing tool fuses **objective resort facts** (top lift height, slopes, lifts, difficulty percentages, location) with **the user's own preferences** (experience level, difficulty taste, geographic constraints, novelty preference) into a single short ranked answer. Comparators surface stats; review sites surface opinions; nothing translates "is this resort right for *me*?" into three concrete recommendations the user can act on.

## User & Persona

**Primary persona: the avid explorer.** An experienced skier or snowboarder who has been to several resorts already, is comfortable on the mountain, and is actively looking for *new* destinations to try rather than rebooking the familiar. They reach for NextSlope during pre-season planning, when they have a multi-week window of evenings to research the upcoming winter and want a tool that takes their preferences as input and returns a tight shortlist instead of a 60-tab browsing session.

Novice and first-time skiers feel an adjacent pain — they don't yet know which criteria matter — and may benefit from the same tool, but the MVP is tuned for the experienced user who already has preferences to express. They are not a Secondary persona for v1.

## Success Criteria

### Primary

A first-time user completes this end-to-end flow in a single session and finishes with three actionable resort recommendations:

1. Signs up with email and password.
2. Fills in their profile: experience level, difficulty preference (preferred easy/medium/hard slope mix), location/region preference, and novelty preference (revisit-okay vs. new-only).
3. Browses the resort list and marks resorts they have already visited.
4. Clicks "Recommend resorts."
5. Sees exactly three ranked resort recommendations, each showing the key resort facts (top lift height, number of slopes, number of lifts, difficulty percentage mix, location) plus a one-line "why this matched you" rationale that ties back to the user's stated preferences and visited list.

The MVP scope is 20–40 resorts in a single region — chosen by the user as the region they know best — shipped in approximately three weeks of after-hours work.

### Secondary

- A returning user can tweak any preference (for example, flip novelty from "new-only" to "revisit-okay", or change the difficulty mix) and re-run the recommendation without re-typing the rest of the profile.

### Guardrails

- **Recommendations respect the visited list.** A user with novelty preference set to "new-only" never sees a resort they have marked visited in their top three.
- **Always three results, or an explicit explanation.** If the dataset is too sparse for the user's filter combination, the product says so clearly rather than returning one or two results silently or padding the list with poor matches.
- **The rationale must be truthful.** The one-line "why this matched you" line reflects the actual matching logic — not generic flavor text. If the rationale and the ranking diverge, that is a regression.
- **Profile and visited-list privacy.** A user's preferences and visited resorts are visible only to that user. Admins cannot see them; other users cannot see them.

## User Stories

### US-01: Avid explorer gets three ranked resort recommendations

- **Given** a signed-in user who has filled in their preference profile (experience level, difficulty mix, location/region, novelty preference) and marked any resorts they have already visited
- **When** they click "Recommend resorts"
- **Then** they see exactly three resort recommendations, ranked from best fit to third-best fit, each showing the resort's key facts (top lift height, number of slopes, number of lifts, difficulty mix percentages, location) and a one-line rationale that ties back to their stated preferences

#### Acceptance Criteria

- The result page is visible to the user within approximately two seconds of the click.
- A user whose novelty preference is "new-only" never sees a resort they have marked as visited in the result.
- A user whose novelty preference is "revisit-okay" may see visited resorts if they otherwise match.
- The "why this matched you" rationale references at least one preference axis the user actually set (for example, "matches your preferred 60/30/10 difficulty mix and is in your preferred region").
- If fewer than three resorts can be recommended given the user's filters and the available resorts, the product says so explicitly with a one-line explanation rather than padding with poor matches or returning fewer results silently.

### US-02: User marks a resort as visited

- **Given** a signed-in user browsing the resort list
- **When** they tap or click the "mark as visited" control on a resort entry
- **Then** that resort is recorded in their visited list, and the visual state of the entry updates to reflect that

#### Acceptance Criteria

- The mark-visited action is reversible — the same control unmarks the resort.
- Marking or unmarking a resort takes effect immediately for the next recommendation request the user makes.
- The visited list is per-user; no other user (including admins) can see what resorts a given user has visited.

### US-03: Admin adds a new resort

- **Given** a signed-in admin user on the admin resort management view
- **When** they fill in a new-resort form with name, location, top lift height, number of slopes, number of lifts, and easy/medium/hard slope percentages, and submit
- **Then** the new resort appears for users to browse, mark visited, and be matched against in subsequent recommendations

#### Acceptance Criteria

- The percentages for easy/medium/hard slopes must sum to 100; submissions where they don't are rejected with a clear message naming the constraint.
- The numeric fields (top lift height, number of slopes, number of lifts) accept only non-negative integers.
- The admin can submit only when all required fields are present; required fields are clearly marked.
- A regular (non-admin) user attempting to access the admin view receives an access-denied response, not the admin form.

## Functional Requirements

### Authentication & Profile

- FR-001: User can sign up with email and password. Priority: must-have
  > Socrates: Counter-argument considered: "OAuth or magic-link would lower signup friction." Resolution: kept as written — email and password is well-understood, easy to ship in three weeks, no third-party dependency.
- FR-002: User can sign in with their email and password. Priority: must-have
  > Socrates: Counter-argument considered: "Should specify session persistence / 'remember me'." Resolution: kept as written — sign-in is the natural counterpart to sign-up; session details are an implementation choice.
- FR-003: User can sign out. Priority: must-have
  > Socrates: Counter-argument considered: "Too obvious to call out as an explicit FR." Resolution: kept as written — explicit sign-out is a baseline expectation; better visible than implicit.
- FR-004: User can create and edit their preference profile (experience level, difficulty mix preference, location/region preference, novelty preference). Priority: must-have
  > Socrates: Counter-argument considered: "Four preference axes is too many; collapse to two (experience and region)." Resolution: kept as written — four axes is the minimum that makes the recommender meaningfully personalized; edit is needed because the Secondary success outcome (re-run with tweaked preferences) depends on it.
- FR-005: User can mark and unmark resorts as "visited." Priority: must-have
  > Socrates: Counter-argument considered: "A boolean novelty preference plus session de-duplication is enough; the visited list is overkill." Resolution: kept as written — an explicit visited list makes the novelty axis data-driven and the rationale verifiable; "I haven't been to X" is qualitatively different from "I want new resorts."

### Resort Discovery

- FR-006: User can browse the full resort list with key facts visible (name, location, top lift height, number of slopes, number of lifts, difficulty mix). Priority: must-have
  > Socrates: Counter-argument considered: "Browse is busy work; users only need the recommendation result and a search to mark visited." Resolution: kept as written — users need to browse to mark visited (FR-005), and the list also lets them sanity-check that their region is covered. At 20–40 resorts a flat list is appropriate; search/filter becomes necessary if the resort count grows past about a hundred.
- FR-007: User can view a single resort's full detail view. Priority: must-have
  > Socrates: Counter-argument considered: "If the list and recommendation card already show the key facts, a separate detail view adds nothing." Resolution: kept as written — the detail view is the natural drill-down from a recommendation card; even if it shows only the same facts in a focused layout, the dedicated route reads as expected product behavior.

### Recommendation

- FR-008: User can request a recommendation and receive exactly three ranked resort suggestions based on their profile and visited list. Priority: must-have
  > Socrates: Counter-argument considered: "'Exactly three' is brittle when filters narrow the candidate set; revise to 'up to three'." Resolution: kept as written — three is the deliberate decision-aid framing (not an exhaustive list, not a single answer), and the Guardrails already cover the sparse-result case ("the product explains explicitly when fewer are possible").
- FR-009: Each recommendation displays the resort's key facts plus a one-line "why this matched you" rationale. Priority: must-have
  > Socrates: Counter-argument considered: "Generating a truthful rationale is harder than the matching itself; risk is generic flavor text." Resolution: kept as written — the rationale is the trust signal that distinguishes NextSlope from a generic stats comparator; the truthfulness Guardrail backs this up and the rationale-must-reference-an-actual-preference-axis acceptance criterion in US-01 anchors the quality bar.

### Admin (resort data curation)

- FR-010: Admin can sign in via the same login surface as regular users and access an admin-only resort management view. Priority: must-have
  > Socrates: Counter-argument considered: "Drop the admin view from v1 — seed data once outside the product." Resolution: kept as written — a minimal admin UI is needed in v1 because resort facts evolve (new lifts open, slope counts change) and editing them outside the product during the project is fragile.
- FR-011: Admin can create a new resort entry (name, location, top lift height, number of slopes, number of lifts, easy/medium/hard slope percentages). Priority: must-have
  > Socrates: Counter-argument considered: "Six fields aren't enough — missing country, snow record, season length, total piste km." Resolution: kept as written — these six fields are exactly what the recommender consumes; richer fields can be added once the matching logic is validated. Adding more fields now over-commits the admin to data entry without a recommendation-side payoff.
- FR-012: Admin can edit an existing resort entry. Priority: must-have
  > Socrates: Counter-argument considered: "Edits without an audit log are dangerous; revise to include change history." Resolution: kept as written — change-history capture is overkill for v1 with a single trusted admin and a small dataset; can be added later if multi-admin operations begin.
- FR-013: Admin can deactivate a resort entry — a deactivated resort no longer appears to users in browsing or in any new recommendation, while users' existing visited-list references to it continue to work. Priority: must-have
  > Socrates: Counter-argument considered (the original framing was outright removal): "Outright removal of a resort would break any user's visited list referencing it." Resolution: revised to deactivation — protects visited-list integrity (a Guardrail concern) while still letting admins remove resorts from the active product surface. Reactivating a deactivated resort happens by the admin reversing the action; not a separate FR for v1.

## Non-Functional Requirements

- A user input — typing in a field, clicking a button, navigating between views — is acknowledged within 200ms; any operation that takes longer than two seconds presents continuous visible progress to the user.
- A recommendation request returns a result, or an explicit timeout message, within 60 seconds; the user is never left looking at a stalled view past that bound.
- A user's preference profile and visited resort list are not visible to any other user, and are not visible to admins through any product surface.
- Given the same user profile, the same visited list, and the same set of resorts, the recommendation result is deterministic — the same inputs produce the same three resorts in the same order.
- A user can permanently delete their account; once deletion is confirmed, their profile data and visited list are removed from the product and do not reappear on any product surface.

## Business Logic

NextSlope ranks resorts by how well each resort's objective facts (top lift height, slope count, lift count, difficulty mix percentages, location) align with the user's stated preferences and visited history, and returns the top three as recommendations with a one-line rationale that explains the alignment.

The rule consumes three classes of input the user (or admin) supplies. **Resort facts** — for each resort: name, location, top lift height, number of slopes, number of lifts, and the easy/medium/hard slope difficulty mix expressed as percentages summing to 100. **User preferences** — experience level, the user's preferred difficulty mix, location/region preference, and a novelty preference (whether already-visited resorts are eligible at all, or only new ones should be considered). **User visited history** — the explicit list of resort entries the user has marked as already visited.

The output, given those inputs, is a fixed shortlist: three resort entries ordered by alignment from best fit to third-best fit, each annotated with a one-line rationale that names at least one preference axis the user actually set (for example, "matches your preferred 60/30/10 difficulty mix and is in your preferred region", or "matches your experience level and is unvisited"). When the available resorts cannot supply three viable matches under the user's constraints, the output is an explicit explanation instead of a padded result.

Conceptually the matching is two-stage. **Hard filters first**: resorts that violate a non-negotiable preference — for example, sitting outside the chosen region, or already visited when the novelty preference is "new only" — are dropped from consideration. The remaining candidates are then **scored on a weighted alignment** across the soft preference axes (experience level versus resort difficulty profile, preferred difficulty mix versus the resort's actual difficulty mix, and so on). The user encounters the rule by clicking "Recommend resorts" after filling their profile and marking visited entries; the result is a single view with three ranked cards.

## Access Control

NextSlope is a multi-user product. Access is gated behind email-and-password sign-up and sign-in: a profile (experience level, difficulty preference, location/region constraints, novelty preference) is saved to the account and persists across devices and sessions.

Two roles, with this capability matrix:

- **User** — can sign up, sign in, sign out, edit their own profile, request recommendations, view resort details, and mark or unmark resorts as visited. Cannot create, edit, or deactivate resort entries.
- **Admin** — superset of User; additionally can create, edit, and deactivate resort entries (name, location, top lift height, number of slopes, number of lifts, easy/medium/hard slope percentages). Admins log in through the same surface as regular users; the admin role is assigned at user setup and is not self-service.

Sign-up creates a User account; admin assignment is out-of-band (not a self-service operation in v1). Sign-in returns the user to the recommendation flow. Sign-out returns the user to a public landing surface.

Unauthenticated visitors may see a public landing surface but cannot reach the recommendation flow, the visited-marking flow, the resort detail view, or the admin surface. An unauthenticated request to any gated route is redirected to sign-in.

The MVP admin surface is intentionally minimal: a simple form to create or update resort entries plus a control to deactivate or reactivate them. There is no advanced moderation, no change-history view, and no role-management UI in v1.

## Non-Goals

- **No booking or reservation integration.** NextSlope recommends; it does not transact. Lift tickets, lodging, ski school, and equipment rental remain external concerns the user takes elsewhere.
- **No global resort coverage.** v1 covers a single region (20–40 resorts the user knows well). Worldwide coverage is not in scope and is not on the v1 roadmap.
- **No live weather or snow conditions data.** Resort facts are static; admins update them when reality changes (for example, a new lift opens, slope counts shift).
- **No social features.** No sharing, no comments, no communities, no follows, no leaderboards, no profile-visibility-to-others.
- **No user-generated reviews or ratings.** NextSlope is preferences → facts → recommendation. It is not a Yelp for ski resorts.
- **No collaborative filtering or learned ranking.** The recommender is deterministic and explainable. "Users like you also enjoyed X" is intentionally out of scope; the rationale must always trace back to the user's own stated preferences.
- **No native mobile app.** Responsive web only. Mobile users get the responsive web experience.
- **No offline mode.** NextSlope assumes the user is online when using it.
- **No multi-language support.** A single language for v1 (locale to be decided — see Open Questions).
- **No admin change-history view.** Single trusted admin in v1; multi-admin operations and change auditing are not in scope.
- **No email notifications beyond signup confirmation.** No transactional emails (for example, "your recommendations have changed"), no marketing emails, no digests.

## Open Questions

1. **Which region and which 20–40 resorts seed the v1 dataset?** — Owner: user. Resolution before v1 ships: yes.
2. **What is the source of truth for the resort facts (top lift height, slopes, lifts, difficulty mix), and how is the seed file produced (hand-typed, one-off scrape, or compiled from multiple sources)?** — Owner: user. Resolution: dev-time, before deployment.
3. **Which language / locale ships in v1?** — Owner: user. Resolution before v1 ships: yes.
4. **Account deletion: how long does the user have to undo deletion before it becomes permanent, or is it immediate?** — Owner: user. Resolution: flagged for clarification during implementation.
