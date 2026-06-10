---
project: NextSlope
researched_at: 2026-06-07
recommended_platform: Render
runner_up: Fly.io
context_type: mvp
tech_stack:
  language: Java 21
  framework: Spring Boot 4 + Thymeleaf
  runtime: JVM (Docker container)
---

## Recommendation

**Deploy on Render, starting on the free configuration.**

The developer's top priority for this run is **minimizing cost ("cheapest viable option,
even if DX is rougher")**, with external managed databases acceptable, single region, and
low traffic. A JVM/Spring Boot JAR is a long-running server process, so JS-only edge runtimes
(Cloudflare Workers, Vercel, Netlify) were dropped up front — they can't run it natively. Among
the three container PaaS that can (Render, Fly.io, Railway), **Render is the only one with a
genuine $0/mo path**: a Free web service ($0, 512 MB) plus an external Neon free Postgres costs
**$0/mo indefinitely**. The cost of that $0 is a ~50s cold start after each 15-minute idle gap —
acceptable under the "rougher DX is OK" constraint and within the PRD's hard 60s recommendation
bound, though it does breach the 200ms-ack / 2s-progress NFR for the first hit on *any* page after
idle, which is why Starter is the documented upgrade — and it's removed by a one-click upgrade to
the **$7/mo Starter** (always-on) when the UX warrants it. Render also has the strongest
agent-operability story of the three (CLI, Blueprints, and `.md` docs all GA, plus an MCP server
in beta), which matters for an agent-driven project.

If "minimize cost" actually means "cheapest option that still feels always-on," the right pick is
the runner-up, **Fly.io (~$3–4/mo)** — no cold-start penalty. It was the project's original target
before this research settled on Render.

> **Note — decision finalized; docs updated.** `tech-stack.md` (`deployment_target`) and the
> `AGENTS.md` Deployment section were originally set to Fly.io, but nothing was implemented yet
> (no `fly.toml`, no `.github/workflows/`), so the decision was open. It is now settled on
> **Render** on the strength of the **$0 floor**, GA agent tooling, and the throwaway-friendly
> reasoning below; `tech-stack.md` and `AGENTS.md` have been updated to match. Fly.io remains the
> documented runner-up if always-on UX later becomes a hard requirement.

## Why $0/mo Fits a Possibly-Discarded MVP

This MVP may be discarded after implementation, which sharpens the decision toward the
lowest-regret option:

- **Spend nothing on something that may be deleted.** Render Free is $0/mo; there is no sunk
  cost if the project is abandoned.
- **No surprise-bill exposure** — the decisive factor over Fly.io. Fly has **no spending cap or
  budget alert**, and managed resources (a Fly Postgres) **survive `fly apps destroy`** — a
  forgotten throwaway is exactly when ghost bills accrue. Render Free can't surprise-bill: idle
  compute spins down and free resources hit limits rather than charging.
- **Trivial teardown.** Delete the Render service and the Neon database — done, $0 owed.
- **One-toggle graduation.** If the MVP unexpectedly becomes real, flip the web service from Free
  to Starter ($7/mo) to remove cold starts — no code change — and only then consider
  managed/co-located Postgres, HA, or backups.

Because the MVP may not survive, the usual justifications for paying more (redundancy, managed
backups, scaling headroom, avoiding lock-in) are intentionally **out of scope** — do not
provision them up front.

## External Postgres: Neon Free Tier

The recommended database is **Neon's free tier** (external managed Postgres), not Render's own
Postgres, for one reason: **Render's free Postgres is deleted 30 days after creation**, whereas
**Neon's free tier persists indefinitely** at $0. Using Neon keeps the whole stack at $0/mo with
no 30-day cliff.

- **Cost:** $0/mo (Neon Free) — ample for this MVP (one project, autosuspending compute, ~0.5 GB
  storage). Confirm current free-tier limits at neon.com/pricing before relying on them.
- **Wiring:** create a Neon project in the region nearest the Render service, copy the **pooled**
  connection string, and store it as the `SPRING_DATASOURCE_URL` secret on Render (plus
  `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD`, or embed credentials in the URL).
- **Latency:** co-locate the Neon region with the Render region — a cross-region DB adds per-query
  RTT that eats into the ~2s recommendation budget.
- **Neon autosuspend caveat:** Neon free compute also scales to zero after ~5 min idle and
  cold-resumes in a few hundred ms to a few seconds on the first query — additive to Render's own
  cold start, but still well within the PRD's 60s bound. Set
  `spring.datasource.hikari.initialization-fail-timeout=-1` so the app survives a DB that is
  waking up.
- **Throwaway-friendly:** deleting the Neon project is instant and leaves nothing billable.

## Cost Ladder (the heart of this decision)

External free Postgres = Neon/Supabase free tier ($0, indefinite). All prices verified against
live pricing pages on 2026-06-07.

| Config | Web compute | Database | Monthly | UX caveat |
|---|---|---|---|---|
| **Render Free** (recommended start) | Free $0 (spins down) | external Neon free | **$0** | ~50s cold start after 15-min idle |
| **Render Starter** | Starter $7 (always-on) | external Neon free | **$7** | none |
| Render Starter + managed PG | Starter $7 | Render Basic-256mb $6 | $13 | none; co-located backups/PITR |
| **Fly.io** (runner-up) | 1×512 MB VM ~$3–4 (always-on) | external Neon free | **~$3–4** | none (no free tier; informal "<$5 waived") |
| Railway | Hobby $5 incl. usage | external Neon free | **~$5** | none; Railpack builder + MCP beta |

Key fact that drives the ranking: **Render is cheapest in absolute terms ($0)**, **Fly.io is
cheapest *always-on* (~$3–4)**, and **Render's always-on floor is $7** (2× Fly).

## Render's Offer, Step by Step

Render bills three stacking things: **workspace plan + per-service compute + metered usage**.

1. **Workspace plan**: **Hobby $0/mo** (1 project / 2 environments, up to 25 services, 500 build
   pipeline minutes/mo). Pro $25, Scale $499 — not relevant for an MVP.
2. **Web service compute** (per service):
   - **Free — $0**, 512 MB / 0.1 CPU, **spins down after 15 min idle** (~50s cold start), 750
     instance-hrs/mo (enough for one service).
   - **Starter — $7/mo**, 512 MB / 0.5 CPU, **always-on**, no spin-down. Cheapest always-on tier.
3. **Render Postgres** (per DB):
   - **Free — $0**, 256 MB, 100 connections, **deleted 30 days after creation** (+14-day grace).
   - **Basic-256mb — $6/mo**, persistent, logical backups + 3-day PITR.
4. **Metered**: bandwidth included on Hobby = **5 GB/mo** (RESOLVED 2026-06-10, deployment-plan Phase
   0.8: the 5 GB-vs-100 GB contradiction was the legacy-vs-new plan split — the **new Hobby plan**
   effective 2026-04-23 includes **5 GB** at $0.15/GB overage; **100 GB** was the legacy plan. Workspaces
   created after the cutover are on 5 GB). Egress to an external DB is billable but negligible here;
   **500 build-minutes/mo**.

So the $0 path = Free web + external Neon free Postgres (Render's own free Postgres self-destructs
at 30 days, so use Neon for anything persistent).

## Platform Comparison

Hard runtime filter applied first (JVM server → JS-only edge runtimes eliminated).

| Platform | Cheapest viable | Cheapest always-on | CLI-first | Managed | Agent docs | Deploy API | MCP |
|---|---|---|---|---|---|---|---|
| **Render** | **$0** (cold starts) | $7 | Pass (GA) | Pass | Pass (`.md`, GA) | Pass | Partial (MCP beta) |
| **Fly.io** | ~$3–4 | ~$3–4 | Pass (GA) | Pass | Pass (Markdown) | Pass | Partial (early/unofficial) |
| **Railway** | ~$5 | ~$5 | Partial (rollback dashboard-only) | Pass | Pass (`llms.txt`) | Pass | Partial (beta) |
| Cloudflare / Vercel / Netlify | — | — | — | — | — | — | Dropped (no JVM runtime) |

### Shortlisted Platforms

#### 1. Render (Recommended)

The only platform with a true **$0/mo** path (Free web + external Neon), which directly serves the
"minimize cost" priority; the cold-start tradeoff is sanctioned by "rougher DX is OK" and erased by
a $7 upgrade. Everything an agent needs is GA (CLI since Dec 2024, Blueprints, hosted MCP, `.md`
docs). Single architectural cost: no native Java runtime → a multi-stage Dockerfile.

#### 2. Fly.io

Cheapest **always-on** at ~$3–4/mo and the project's *original* deployment target (now superseded by
Render; retained as the documented runner-up). The right pick if cold starts are unacceptable. Loses
#1 only because it has no $0 floor and a weaker (early/unofficial) agent MCP story; gains it back
instantly if always-on is a hard need.

#### 3. Railway

~$5/mo with the smoothest build (Railpack auto-detects Java 21, no Dockerfile) and `llms.txt` docs,
but its Java builder and MCP are **beta**, rollback is dashboard-only, and Postgres backups aren't
automatic. Solid but neither cheapest-absolute nor cheapest-always-on.

## Counter-Arguments — Against Render

1. **Cheapest always-on is $7/mo — ~2× Fly's ~$3–4.** For no-cold-start UX, Render is pricier.
2. **The free web tier spins down.** A low-traffic MVP is idle most of the time, so the *first*
   visitor after each idle gap eats a ~50s cold start — on the login page, the browse list, *or*
   the recommend button, not just the recommender. Within the 60s hard bound, but a poor first
   impression.
3. **Free Postgres is deleted after 30 days** — not a real product DB; forces external Neon or $6.
4. **512 MB on the cheap tiers is tight for a JVM** — needs `MaxRAMPercentage` tuning or OOM-kills.
5. **Bandwidth ambiguity/history** — page self-contradicts (5 GB vs 100 GB) and was reportedly cut
   100→5 GB in Apr 2026; external-DB egress is billable.
6. **500 build-minutes/mo** can be exhausted by uncached Gradle Docker builds (~5–10 min each).
7. **No native Java runtime** → Dockerfile maintenance.
8. **Hobby caps**: 1 project / 2 environments, no preview environments (Pro+ only), 5 builds
   retained for instant rollback.

## Counter-Arguments — Against Fly.io

1. **No free tier at all** (removed Oct 2024) — you pay from day one (~$3–4/mo); the "invoices
   under $5 waived" rule is **informal and may end**. Render has a genuine $0 path; Fly does not.
2. **No spending cap or budget alerts** — orphaned managed resources (a Fly Postgres that survives
   `fly apps destroy`) silently rack up ghost bills. Render free compute just stops/limits instead.
3. **No real "free when idle" either** — UX requires `min_machines_running=1` (always-on) because a
   JVM cold start is 15–30s; scale-to-zero savings are forfeited.
4. **No native Java buildpack** → Dockerfile required (same as Render).
5. **MCP is early/unofficial** — less turnkey agent ops than Render's GA CLI + MCP + `.md` docs.
6. **Single machine = no redundancy** — a host incident is downtime unless you run a second machine,
   doubling cost and erasing the price edge.
7. **Dedicated IPv4 = $2/mo gotcha** (use free shared IPv4); historically more platform incidents.

## Anti-Bias Cross-Check: Render

### Devil's Advocate — Weaknesses

1. **Cold-start UX on the free tier** is the defining risk: ~50s on the first request after any
   15-min idle, hitting every entry point of the app, not just the recommender.
2. **512 MB Starter/Free is tight for Spring Boot 4** — without `-XX:MaxRAMPercentage` tuning the
   JVM over-allocates and gets OOM-killed.
3. **Free Postgres self-destructs at 30 days** — provisioning it "to save money" silently destroys
   the seed dataset; use external Neon or paid Basic from day one.
4. **500 free build-minutes/mo** are consumed by full Gradle Docker builds; without layer caching,
   *builds* run out before compute.
5. **Cost creep** past the $0/$7 floor — bandwidth overage, a future cron/worker, or moving to
   managed Postgres climbs the bill.

### Pre-Mortem — How This Could Fail

The team shipped on Render's free tier to spend nothing, and six months later it was a problem.
Because the service spun down after every idle gap, real users — and the demo to a potential
collaborator — repeatedly hit a 50s blank-loading cold start on the login page, reading as "the
site is broken"; bounce rate on first visits was brutal. Switching to the $7 Starter fixed it, but
only after the bad first impressions were made. Separately, early on they used Render's *free*
Postgres to save the $6, never migrated it, and 44 days in the seed resort dataset was deleted —
restoring from a stale dump cost a weekend. Finally, the JVM ran on 512 MB with no heap tuning and
intermittently OOM-restarted under recommendation load, producing flaky 502s that were hard to
reproduce. Each failure was a default the team didn't override, not a Render fault.

### Unknown Unknowns

- The free web tier's **750 instance-hours/mo** sustains exactly one always-on-ish service; a second
  free service would exceed it.
- ~~The pricing page disagrees with itself on bandwidth (5 GB vs 100 GB)~~ — **RESOLVED 2026-06-10**: it
  was the legacy-vs-new plan split; new Hobby plan (2026-04-23) = **5 GB/mo** ($0.15/GB overage). See
  deployment-plan Phase 0.8.
- The `.md` docs trick (append `.md` to any docs URL) and `render-oss/skills` repo are real agent
  accelerants but unadvertised.
- Render's hosted MCP server **has no delete operations** by design — some teardown ops still need
  CLI/dashboard.
- Cheapest-tier Postgres **connection limits** are low; Spring Boot's default Hikari pool (10) per
  instance exhausts them if a preview env runs alongside prod.

## Operational Story

- **Preview deploys**: Hobby supports **single-service previews** (full preview *environments* are
  Pro+). PR builds get a preview URL; keep them short-lived since they consume usage.
- **Secrets**: env vars / secret files per service (or an Environment Group); `SPRING_DATASOURCE_URL`
  for the external Neon DB is stored as a secret. Rotate by editing the value (triggers redeploy).
  NOTE (reconciled 2026-06-10, deployment-plan Phase 0.7): the **chosen deploy mode is Render-native
  "After CI Checks Pass"** (`autoDeployTrigger: checksPass`), which needs **no `RENDER_API_KEY` and no
  deploy hook**. A `RENDER_API_KEY` GitHub Actions secret is required ONLY for the API/CLI deploy
  fallback — do not store it unless that fallback is adopted.
- **Rollback**: dashboard → Deploys → "Rollback" (Hobby retains 5 builds), or via REST API /
  `render deploys`. One redeploy (~1–3 min). DB migrations do **not** roll back — keep them
  backward-compatible.
- **Approval**: a human approves production promotion, secret rotation, and any paid-tier/DB change;
  an agent may build+deploy to preview, tail logs, read status, and trigger redeploy/rollback.
- **Logs**: `render logs --resources <srv-id> --tail` (read-only), the Render MCP server's structured
  log/status tools, or the dashboard live viewer.

## Risk Register

| Risk | Source | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| Free-tier cold start (~50s) reads as "broken" to first visitors | Devil's advocate / Pre-mortem | H | M | Upgrade the web service to Starter ($7) before any demo or public launch; the $0 tier is for early dev only |
| Spring Boot OOM-killed on 512 MB | Devil's advocate / Pre-mortem | H | H | `JAVA_TOOL_OPTIONS=-XX:+UseContainerSupport -XX:MaxRAMPercentage=65 -XX:MaxMetaspaceSize=128m -XX:+ExitOnOutOfMemoryError`; load-test the recommend endpoint |
| Free Postgres deleted at 30 days, data lost | Devil's advocate / Pre-mortem | M | H | Use external Neon free (indefinite) or Render Basic-256mb ($6) from day one; never store real data on Render free PG; keep a seeded SQL dump in the repo |
| 500 free build-minutes exhausted by Gradle Docker builds | Pre-mortem / Unknown unknowns | M | M | Multi-stage Dockerfile with Gradle dependency-layer caching + `eclipse-temurin:21-jre` base; avoid no-op redeploys |
| ~~Bandwidth allowance unclear (5 GB vs 100 GB)~~ — **RESOLVED 2026-06-10** | Unknown unknowns | L | L | New Hobby plan (2026-04-23) = **5 GB/mo**, $0.15/GB overage; legacy was 100 GB. Confirmed in deployment-plan Phase 0.8; traffic is far under 5 GB for this MVP |
| Cost creep past $0/$7 | Devil's advocate | L | M | Single web service for the MVP; watch usage; defer managed PG/cron until needed |
| Divergence from documented Fly.io target — **resolved 2026-06-07** | Research finding | — | — | Done: `tech-stack.md` (`deployment_target`) and `AGENTS.md` Deployment section updated to Render; Fly.io retained as the documented runner-up |

## Getting Started

Validated against the pinned stack (Java 21, Gradle, Spring Boot 4) and Render's current GA tooling.

1. **Add a multi-stage Dockerfile** at the repo root (no native Java runtime). Build the boot jar
   with the wrapper, run it on a slim JRE, and cap the heap for 512 MB:

```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew --no-daemon clean bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=65 -XX:MaxMetaspaceSize=128m -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
```

2. **Bind to Render's port + health check**: `server.port=${PORT:8080}` and add
   `spring-boot-starter-actuator` so `/actuator/health` exists; set it as the Render health-check path.
3. **Provision an external Neon free Postgres** in the region nearest your Render service; store its
   connection string as the `SPRING_DATASOURCE_URL` secret (avoids Render's 30-day free-PG deletion).
4. **Add a `render.yaml` Blueprint** declaring a Docker web service on the **Free** plan (flip to
   `starter` to go always-on) with `healthCheckPath: /actuator/health`.
5. **Install the CLI and deploy**: `brew install render` (or `render-oss/cli`), `render login`, then
   push to GitHub for auto-deploy or run `render deploys create --wait`. Gate deploys on
   `./gradlew test` in GitHub Actions — satisfying the `AGENTS.md` note that `.github/workflows/`
   must exist before relying on CI.
6. **When cold starts hurt**, change the web service plan from Free to **Starter ($7/mo)** — a single
   setting; no code change.

## Out of Scope

- Docker image configuration (the Dockerfile above is orientation only)
- CI/CD pipeline setup
- Production-scale architecture (multi-region, HA, DR)
