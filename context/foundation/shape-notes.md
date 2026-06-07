---
project: "NextSlope"
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
created: 2026-05-24
updated: 2026-05-24
checkpoint:
  current_phase: 8
  phases_completed: [1, 2, 3, 4, 5, 6, 7]
  gray_areas_resolved:
    - topic: "pain category"
      decision: "decision paralysis + data trapped across many sources + missing personalized matching capability; novice overwhelm noted as adjacent"
    - topic: "primary persona"
      decision: "avid explorer — experienced skier/snowboarder seeking new resorts to try"
    - topic: "insight"
      decision: "no existing tool combines objective resort facts (slopes, lifts, difficulty mix) with subjective user preferences in one ranked output"
    - topic: "moment of pain"
      decision: "pre-season planning (Oct–Dec): mapping out 1–3 trips for the upcoming winter"
    - topic: "current cost"
      decision: "12 browser tabs + ski blogs/Reddit/YouTube — hours of reading, fuzzy comparison"
    - topic: "auth shape"
      decision: "email + password account; profile persists across devices/sessions"
    - topic: "role separation"
      decision: "two roles — regular user + admin who curates resort data"
    - topic: "admin scope in MVP"
      decision: "minimal admin UI for resort add/edit; no advanced moderation"
    - topic: "MVP flow"
      decision: "sign up → fill profile (experience, difficulty, location, novelty) → browse resorts and mark visited → click recommend → 3 ranked recs with facts and rationale"
    - topic: "dataset scope for v1"
      decision: "20–40 resorts, one region the user knows well; expand later"
    - topic: "secondary outcome"
      decision: "user can tweak preferences and re-run without re-typing the whole profile"
    - topic: "domain rule shape"
      decision: "scoring + ranking — NextSlope ranks resorts by alignment between resort facts and user preferences/visited history, returns top 3 with rationale"
    - topic: "matching strategy"
      decision: "hard filters first (drop resorts violating non-negotiable preferences like region or visited+new-only), then weighted score on the remainder"
    - topic: "product type"
      decision: "responsive web application (Recommended; matches seed)"
    - topic: "target scale"
      decision: "small (single-digit users for MVP); rule scales unchanged at 100x per Socrates probe"
    - topic: "timeline"
      decision: "3-week MVP, after-hours work only, hard deadline 2026-06-28 (~5 weeks out — 2-week buffer above the 3-week target)"
    - topic: "data source"
      decision: "static CSV/JSON seed file delivered with v1; admin types ongoing edits manually; whether the seed was hand-typed or one-off scraped is a dev-time choice, not a product FR"
  frs_drafted: 13
  quality_check_status: accepted
---

# NextSlope — Shape Notes

Seed idea (verbatim, from `project-idea.md`):

> Skiers and snowboarders have huge choice regarding resorts they can pick for their next winter trip. Often it is difficult to chose the perfect one, especially if they are looking for a new place, or have little experience.
> Sifting through data about resorts is time consuming, and there may be multiple criteria that must be taken into consideration.
>
> Web application NextSlope that will help to pick the next destination for avid skier or snowboarder. Application will contain data about ski resorts — name, location, top lift height, number of slopes, number of lifts, how difficulty the slopes are (percentage based information about easy, medium, and difficult runs). Based on the information above, and user profile information (if they prefer new destinations, experience level, location and difficulty preferences) it will provide 3 recommended destinations.

> **Body order note:** the sections below are sequenced to anticipate the 10 greenfield PRD sections in the order `/10x-prd` expects (Vision → Persona → Success Criteria → User Stories → Functional Requirements → Non-Functional Requirements → Business Logic → Access Control → Non-Goals → Open Questions). Quality cross-check sits at the end as a `/10x-shape` artifact, not a PRD section.

## Vision & Problem Statement

An avid skier or snowboarder planning the upcoming winter season (typically Oct–Dec, mapping out 1–3 trips) faces decision paralysis: hundreds of resorts, multi-criteria comparisons (lift height, slope count, difficulty mix, location, snow record, vibe), and the data is scattered across resort websites, ski blogs, Reddit threads, and YouTube reviews. They currently spend hours juggling browser tabs and still finish with a fuzzy comparison and a low-confidence pick — often defaulting back to a familiar resort or a friend's recommendation, missing better-fit destinations they have never heard of.

The insight: no existing tool fuses **objective resort facts** (top lift height, slopes, lifts, difficulty percentages, location) with **the user's own preferences** (experience level, difficulty taste, geographic constraints, novelty preference) into a single short ranked answer. Comparators surface stats; review sites surface opinions; nothing translates "is this resort right for *me*?" into three concrete recommendations the user can act on.

## User & Persona

**Primary persona: the avid explorer.** An experienced skier or snowboarder — has been to several resorts already, is comfortable on the mountain, and is actively looking for *new* destinations to try rather than rebooking the familiar. They reach for NextSlope during pre-season planning, when they have a multi-week window of evenings to research the upcoming winter and want a tool that takes their preferences as input and returns a tight shortlist instead of a 60-tab browsing session.

(Novice / first-time skiers feel an adjacent pain — they don't yet know which criteria matter — and may benefit from the same tool, but the MVP is tuned for the experienced user who already has preferences to express.)

## Success Criteria

### Primary

A first-time user completes this end-to-end flow in a single session and finishes with three actionable resort recommendations:

1. Signs up with email + password.
2. Fills in their profile: experience level, difficulty preference (preferred easy/medium/hard slope mix), location/region preference, novelty preference (revisit-okay vs new-only).
3. Browses the resort list and marks resorts they have already visited.
4. Clicks "Recommend resorts."
5. Sees exactly three ranked resort recommendations, each showing the key resort facts (top lift height, number of slopes, number of lifts, difficulty percentage mix, location) plus a one-line "why this matched you" rationale that ties back to the user's stated preferences and visited list.

The MVP scope: 20–40 resorts in a single region (chosen by the user — the region they know best), shipped in approximately 3 weeks of after-hours work.

### Secondary

- A returning user can tweak any preference (e.g., flip novelty from "new-only" to "revisit-okay", change difficulty mix) and re-run the recommendation without re-typing the rest of the profile.

### Guardrails

- **Recommendations respect the visited list.** A user with novelty preference set to "new-only" never sees a resort they have marked visited in their top 3.
- **Always 3 results, or an explicit explanation.** If the dataset is too sparse for the user's filter combination, the app says so clearly rather than returning 1–2 results silently or padding with poor matches.
- **The rationale must be truthful.** The one-line "why this matched you" line reflects the actual matching logic — not generic flavor text. If the rationale and the ranking diverge, that is a regression.
- **Profile and visited-list privacy.** A user's preferences and visited resorts are visible only to that user. Admins cannot see them; other users cannot see them.

## User Stories

### US-01: Avid explorer gets three ranked resort recommendations

- **Given** a signed-in user who has filled in their preference profile (experience level, difficulty mix, location/region, novelty preference) and marked any resorts they have already visited
- **When** they click "Recommend resorts"
- **Then** they see exactly three resort recommendations, ranked from best fit to third-best fit, each showing the resort's key facts (top lift height, number of slopes, number of lifts, difficulty mix percentages, location) and a one-line rationale that ties back to their stated preferences

#### Acceptance Criteria

- The result page renders within ~2 seconds of the click from the user's perspective.
- A user whose novelty preference is "new-only" never sees a resort they have marked as visited in the result.
- A user whose novelty preference is "revisit-okay" may see visited resorts if they otherwise match.
- The "why this matched you" rationale references at least one preference axis the user actually set (e.g., "matches your preferred 60/30/10 difficulty mix and is in your preferred region").
- If fewer than 3 resorts can be recommended given the user's filters and the dataset, the UI says so explicitly with a one-line explanation rather than padding with poor matches or returning fewer results silently.

### US-02: User marks a resort as visited

- **Given** a signed-in user browsing the resort list
- **When** they tap or click the "mark as visited" control on a resort entry
- **Then** that resort is recorded in their visited list, and the visual state of the entry updates to reflect that

#### Acceptance Criteria

- The mark-visited action is reversible — the same control unmarks the resort.
- Marking or unmarking a resort takes effect immediately for the next recommendation request the user makes.
- The visited list is per-user; no other user (including admins) can see what resorts a given user has visited.

### US-03: Admin adds a new resort to the dataset

- **Given** a signed-in admin user on the admin resort management view
- **When** they fill in a new-resort form with name, location, top lift height, number of slopes, number of lifts, and easy/medium/hard slope percentages, and submit
- **Then** the new resort appears in the dataset and is available for users to browse, mark visited, and be matched against in subsequent recommendations

#### Acceptance Criteria

- The percentages for easy/medium/hard slopes must sum to 100%; the form rejects submissions where they don't.
- All numeric fields (lift height, slope count, lift count) accept only non-negative integers.
- An admin can save a partially-filled draft only if all required fields are present; the form clearly indicates which fields are required.
- A regular (non-admin) user attempting to access the admin view receives an access-denied response, not the admin form.

## Functional Requirements

### Authentication & Profile

- FR-001: User can sign up with email + password. Priority: must-have
  > Socrates: Counter-argument considered: "OAuth or magic-link would lower signup friction." Resolution: kept as written — email+password is well-understood, easy to ship in 3 weeks, no third-party dependency.
- FR-002: User can sign in with their email + password. Priority: must-have
  > Socrates: Counter-argument considered: "Should specify session persistence / 'remember me'." Resolution: kept as written — sign-in is the natural counterpart to sign-up; session details are an implementation choice.
- FR-003: User can sign out. Priority: must-have
  > Socrates: Counter-argument considered: "Too obvious to call out as an explicit FR." Resolution: kept as written — explicit sign-out is a baseline expectation; better visible than implicit.
- FR-004: User can create and edit their preference profile (experience level, difficulty mix preference, location/region preference, novelty preference). Priority: must-have
  > Socrates: Counter-argument considered: "Four preference axes is too many; collapse to 2 (experience + region)." Resolution: kept as written — 4 axes is the minimum that makes the recommender meaningfully personalized; edit is needed because the Secondary success outcome (re-run with tweaked prefs) depends on it.
- FR-005: User can mark and unmark resorts in the dataset as "visited." Priority: must-have
  > Socrates: Counter-argument considered: "A boolean novelty preference plus session de-duplication is enough; the visited list is overkill." Resolution: kept as written — explicit visited list makes the novelty axis data-driven and the rationale verifiable; "I haven't been to X" is qualitatively different from "I want new resorts."

### Resort Discovery

- FR-006: User can browse the full resort list with key facts visible (name, location, top lift height, number of slopes, number of lifts, difficulty mix). Priority: must-have
  > Socrates: Counter-argument considered: "Browse is busy work; users only need the rec result and a search to mark visited." Resolution: kept as written — users need to browse to mark visited (FR-005), and the list also lets them sanity-check that their region is covered. At 20–40 resorts, a flat list is appropriate; search/filter becomes necessary if the dataset grows past ~100 entries.
- FR-007: User can view a single resort's full detail page. Priority: must-have
  > Socrates: Counter-argument considered: "If the list and rec card already show the key facts, a separate detail page adds nothing." Resolution: kept as written — the detail page is the natural drill-down from a recommendation card; even if it shows only the same facts in a focused layout, the dedicated route reads as expected product behavior.

### Recommendation

- FR-008: User can request a recommendation and receive exactly 3 ranked resort suggestions based on their profile and visited list. Priority: must-have
  > Socrates: Counter-argument considered: "'Exactly 3' is brittle when filters narrow the dataset; revise to 'up to 3'." Resolution: kept as written — '3' is the deliberate decision-aid framing (not an exhaustive list, not a single answer), and the Guardrails already cover the sparse-result case ("the system explains explicitly when fewer are possible").
- FR-009: Each recommendation displays the resort's key facts plus a one-line "why this matched you" rationale. Priority: must-have
  > Socrates: Counter-argument considered: "Generating a truthful rationale is harder than the matching itself; risk is generic flavor text." Resolution: kept as written — the rationale is the trust signal that distinguishes NextSlope from a generic stats comparator; the truthfulness Guardrail backs this up and the rationale-must-reference-an-actual-preference-axis acceptance criterion in US-01 anchors the quality bar.

### Admin (resort data curation)

- FR-010: Admin can sign in via the same login surface as regular users and access an admin-only resort management view. Priority: must-have
  > Socrates: Counter-argument considered: "Drop the admin view from v1 — seed data once via SQL/CSV." Resolution: kept as written — minimal admin UI is needed in v1 because resort data evolves (new lifts open, slope counts change) and editing via DB scripts during the project is fragile.
- FR-011: Admin can create a new resort entry (name, location, top lift height, number of slopes, number of lifts, easy/medium/hard slope percentages). Priority: must-have
  > Socrates: Counter-argument considered: "6 fields isn't enough — missing country, snow record, season length, total piste km." Resolution: kept as written — these 6 fields are exactly what the recommender consumes; richer fields can be added once the matching logic is validated. Adding more fields now over-commits the admin to data entry without a recommendation-side payoff.
- FR-012: Admin can edit an existing resort entry. Priority: must-have
  > Socrates: Counter-argument considered: "Edits without an audit log are dangerous; revise to include change history." Resolution: kept as written — audit log is overkill for v1 with a single trusted admin and a small dataset; can be added later if multi-admin operations begin.
- FR-013: Admin can soft-delete a resort entry (resort is marked inactive, hidden from users in browse and recommendation, but preserved in storage so users' visited-list references remain intact). Priority: must-have
  > Socrates: Counter-argument considered (original FR was hard-delete): "Hard delete breaks any user's visited list referencing the deleted resort." Resolution: revised to soft-delete — protects visited-list integrity (a Guardrail concern) while still letting admins remove resorts from active use. Reactivation is implicit (un-set the inactive flag); not a separate FR for v1.

## Non-Functional Requirements

- A user input — typing in a field, clicking a button, navigating between views — is acknowledged within 200ms; any operation that takes longer than two seconds presents continuous visible progress to the user.
- A recommendation request returns a result or an explicit timeout message within 60 seconds; the user is never left looking at a stalled UI past that bound.
- A user's preference profile and visited resort list are not visible to any other user, and are not visible to admins through any product surface.
- Given the same user profile, the same visited list, and the same resort dataset, the recommendation result is deterministic — the same inputs produce the same three resorts in the same order.
- A user can permanently delete their account; once deletion is confirmed, their profile data and visited list are removed from the system and do not reappear in any product surface.

## Business Logic

NextSlope ranks resorts in the dataset by how well each resort's objective facts (top lift height, slope count, lift count, difficulty mix percentages, location) align with the user's stated preferences and visited history, and returns the top three as recommendations with a one-line rationale that explains the alignment.

The rule consumes three classes of input the user (or admin) supplies. **Resort facts** — for each resort: name, location, top lift height, number of slopes, number of lifts, and the easy/medium/hard slope difficulty mix expressed as percentages summing to 100. **User preferences** — experience level, the user's preferred difficulty mix, location/region preference, and a novelty preference (whether already-visited resorts are eligible at all, or only new ones should be considered). **User visited history** — the explicit list of resort entries the user has marked as already visited.

The output, given those inputs, is a fixed shortlist: three resort entries, ordered by alignment score from best fit to third-best fit, each annotated with a one-line rationale that names at least one preference axis the user actually set ("matches your preferred 60/30/10 difficulty mix and is in your preferred region", or "matches your experience level and is unvisited"). When the dataset cannot supply three viable matches under the user's constraints, the output is an explicit explanation instead of a padded result.

Conceptually the matching is two-stage: hard filters first — resorts that violate a non-negotiable preference (e.g., outside the chosen region, or already visited when the novelty preference is "new only") are dropped from consideration. The remaining candidates are then scored on a weighted alignment across the soft preference axes (experience level vs. resort difficulty profile, preferred difficulty mix vs. resort difficulty mix, and so on). The user encounters the rule by clicking "Recommend resorts" after filling their profile and marking visited entries; the result is a single page with three ranked cards.

## Access Control

Email + password account is required to save a profile and receive recommendations. The profile (experience level, difficulty preference, location/region constraints, novelty preference) persists across devices/sessions.

Two roles:

- **User** — can sign up, sign in, edit their own profile, request recommendations, view resort details. Cannot edit resort data.
- **Admin** — superset of User; additionally can add, edit, and soft-delete resort entries (name, location, top lift height, slope counts, lift counts, difficulty mix). Admin role uses the same login surface; the privilege is a flag on the user record. The admin UI is intentionally minimal in the MVP — a simple form to create/update resort entries. No advanced moderation, no audit log, no role-management UI in v1.

Unauthenticated visitors may see a marketing/landing page but cannot reach the recommendation flow or the admin surface.

## Non-Goals

- **No booking or reservation integration.** NextSlope recommends; it does not transact. Lift tickets, lodging, ski school, and equipment rental remain external concerns the user takes elsewhere.
- **No global resort coverage.** v1 covers a single region (20–40 resorts the user knows well). Worldwide coverage is not in scope and is not on the v1 roadmap.
- **No live weather or snow conditions data.** Resort facts are static; admins update them manually when reality changes (new lift, new piste).
- **No social features.** No sharing, no comments, no communities, no follows, no leaderboards, no profile-visibility-to-others.
- **No user-generated reviews or ratings.** NextSlope is preferences → facts → recommendation. It is not a Yelp for ski resorts.
- **No collaborative filtering or learned ranking.** The recommender is deterministic and explainable. "Users like you also enjoyed X" is intentionally out of scope; the rationale must always trace back to the user's own stated preferences.
- **No native mobile app.** Responsive web only. Mobile users get the responsive web experience.
- **No offline / PWA mode.** NextSlope assumes the user is online when using it.
- **No multi-language support.** A single language for v1 (locale TBD).
- **No admin audit log or change history.** Single trusted admin in v1; multi-admin operations and change auditing are not in scope.
- **No email notifications beyond signup confirmation.** No transactional emails (e.g., "your recommendations have changed"), no marketing emails, no digests.

## Open Questions

1. **Which region and which 20–40 resorts seed the v1 dataset?** — Owner: user. Resolution before v1 ships: yes.
2. **What is the source of truth for the resort facts (top lift height, slopes, lifts, difficulty mix), and how is the seed CSV/JSON produced (hand-typed vs. one-off scrape vs. compiled from multiple sources)?** — Owner: user. Resolution: dev-time, before deployment.
3. **Which language / locale ships in v1?** — Owner: user. Resolution before v1 ships: yes.
4. **Account deletion: how long does the user have to undo deletion before it becomes permanent (or is it immediate)?** — Owner: user. Resolution: NFR-driven; flagged for clarification during implementation.

## Quality cross-check

All required elements present at the close of `/10x-shape`:

- Access Control: present.
- Business Logic (one-sentence rule): present.
- Project artifacts (shape-notes.md + frontmatter checkpoint): present.
- Timeline-cost acknowledged: present (mvp_weeks = 3, at the ≤ 3 threshold; no formal acknowledgment block required).
- Non-Goals: present (11 entries).
- Preserved behavior: n/a (greenfield).

Status: `accepted`. No gaps to mirror into `## Open Questions`.
