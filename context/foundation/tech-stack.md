---
starter_id: spring
package_manager: gradle
project_name: nextslope
hints:
  language_family: java
  team_size: solo
  deployment_target: render
  ci_provider: github-actions
  ci_default_flow: auto-deploy-on-merge
  bootstrapper_confidence: verified
  path_taken: standard
  quality_override: false
  self_check_answers: null
  has_auth: true
  has_payments: false
  has_realtime: false
  has_ai: false
  has_background_jobs: false
---

## Why this stack

Solo backend developer with minimal frontend experience shipping a 3-week
after-hours MVP for a 20–40 resort comparison tool with email/password auth,
profile editing, a deterministic rule-based recommender, and a small admin
CRUD surface. Spring Boot is the recommended default for `(web, java)` and
clears all four agent-friendly gates within the Java family; bootstrapper
confidence is verified, so scaffolding will be smooth. Thymeleaf is wired in
at scaffold time so the entire UI is server-rendered HTML — no SPA tier, one
language, one build, one deploy, one test runner — the only configuration
that respects both the "frontend must be easy to learn" constraint and the
3-week timeline. Spring Security covers the auth FRs out of the box. Render
is the chosen deployment target (see `context/foundation/infrastructure.md`):
it is the only candidate with a genuine $0/mo path (Free web service + external
Neon free Postgres), which is the lowest-regret option for an MVP that may be
discarded after implementation — with a one-toggle upgrade to the $7/mo Starter
tier if it graduates. Fly.io is the documented runner-up for cheapest always-on
(~$3–4/mo) should cold starts become unacceptable. CI runs on GitHub Actions
with auto-deploy-on-merge — the standard solo-developer shape.

## Post-scaffold UI additions (pinned, applied by hand after bootstrap)

These are not Spring Initializr dependencies — they are CDN drop-ins added to
the Thymeleaf base layout after scaffolding. They keep the stack single-tier
(no JS build step, no second language) while removing the only "clunky" spot
of a pure Thymeleaf UI:

- **Bootstrap 5 via CDN** — `<link>` + `<script>` in the Thymeleaf base
  fragment. Default visual polish without owning a CSS pipeline. Chosen over
  Tailwind because Tailwind requires a Node build tier, which would break the
  "one language / one build" constraint above.
- **HTMX via CDN** — single `<script>` tag in the same base fragment. Used for
  the mark-visited toggle (partial-fragment swap, no full page reload), the
  recommend-button result swap with `hx-indicator` for the progress NFR, and
  form-fragment re-render on admin validation errors. Controllers return
  Thymeleaf `th:fragment` snippets — same mental model as full-page rendering.

Neither addition touches the bootstrapper's `cmd_template`; both are wired in
during the first commit after scaffold.
