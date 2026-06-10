# NextSlope — First Deployment Plan (Render + Neon)

Status: APPROVED
Last updated: 2026-06-08
Approved: 2026-06-08
Target platform: Render (Free web service) + external Neon free Postgres
Sources: `context/foundation/infrastructure.md`, `context/foundation/tech-stack.md`, `AGENTS.md`

---

## Goal

Ship the first publicly accessible deployment of NextSlope (Spring Boot 4 + Thymeleaf, Java 21, Gradle)
to Render, backed by an external Neon free-tier Postgres, with a GitHub Actions test gate before deploy.

> Cost target: $0/mo (Render Free web + Neon Free). Upgrade to Render Starter ($7/mo) is a single
> dashboard toggle later if cold starts hurt — no code change.

## Critical context (read before starting)

- The codebase is a **bare scaffold**: only `src/main/java/com/nextslope/NextslopeApplication.java` and a
  context-loads test. There are no controllers, templates, `Dockerfile`, `render.yaml`, or
  `.github/workflows/` yet. This deployment publishes the scaffold so the pipeline is proven before
  feature work lands.
- **Spring Security is on the classpath** (`spring-boot-starter-security`). By default it secures
  *every* endpoint (including `/actuator/health`), choosing form login or `httpBasic` per the request's
  `Accept` header. Without a `SecurityConfig` that permits the health path, an unauthenticated request
  gets a **302 redirect to `/login`** (form login, the likely outcome for a plain health-check GET with
  `Accept: */*`) or a **401** (`httpBasic`). Crucially, **Render treats any `2xx` *or `3xx`* as healthy**,
  so the 302 case actually **passes** the health check and the deploy goes live — pointing the public URL
  at a `/login` wall behind a meaninglessly green health check. (Only the `httpBasic` 401 path would fail
  the check and cancel the deploy.) Either way the fix is the same: `permitAll` `/actuator/health` so it
  returns a true `200 {"status":"UP"}`, and add a public landing page so `/` is not a login wall. This is
  the single most important edge case in this plan.
- **Version / CVE rationale**: Boot is pinned to **4.0.6** (real GA, released 2026-04-23), which patches
  **CVE-2026-40976** (default filter chain ineffective when actuator is present without the health
  module). `spring-boot-starter-actuator:4.0.6` pulls `spring-boot-health` transitively, so
  `/actuator/health` exists and the CVE does not apply.
- `runtimeOnly 'com.h2database:h2'` and `runtimeOnly 'org.postgresql:postgresql'` are both present, so
  local/CI can stay on H2 while production uses Neon Postgres via a Spring profile.
- **Secrets are never committed** — DB credentials and any API keys/tokens live ONLY in Render env vars
  (runtime) and GitHub Actions secrets (CI). Committed files (`application*.properties`, `render.yaml`,
  `ci.yml`, `Dockerfile`) hold only placeholders or `sync: false` keys:
  - `application-prod.properties` uses `${SPRING_DATASOURCE_URL/USERNAME/PASSWORD}` placeholders;
    `application.properties` carries no secrets.
  - `render.yaml` declares the Neon keys with `sync: false` (no value in the file); the real values are
    entered in the Render dashboard at Blueprint launch (Phase 4) and injected as env vars at runtime.
  - Never reference `SPRING_DATASOURCE_*` via a Dockerfile `ARG`/`ENV` — Render turns a Docker service's
    env vars into build args, so the app must read them only from the runtime process env (never baked
    into image layers).
  - The raw Neon connect string embeds `user:pass@host`, so it is itself a secret: split it (Phase 3) and
    paste only into Render — never into a committed file, commit message, or build log.
  - Repo hygiene: no real `.env` in the repo (the current `.gitignore` does NOT ignore it); local/CI use
    H2 so no prod secret reaches a dev machine. Rotate via Render/Neon, never by committing a new value.

---

## Progress tracker (high-level)

- [x] Phase 0 — Prerequisites and accounts (completed 2026-06-10)
- [x] Phase 1 — Make the app deploy-ready (port, health, security, profiles, memory) (completed 2026-06-10)
- [x] Phase 2 — Containerize (multi-stage Dockerfile + `.dockerignore`) (completed 2026-06-10)
- [ ] Phase 3 — Provision external Neon Postgres
- [ ] Phase 4 — Render Blueprint (`render.yaml`) + secrets
- [ ] Phase 5 — CI/CD (GitHub Actions test gate -> deploy)
- [ ] Phase 6 — First deploy and public-access verification
- [ ] Phase 7 — Post-deploy hardening and edge-case runbook

---

## Phase 0 — Prerequisites and accounts

Do these in order. Steps 0.1–0.3 are dashboard/browser; 0.4 and 0.6 are local CLI; everything stays on
the $0 path (no credit card required for either provider).

### 0.1 — Lock the region decision (do this first; it drives every later step)

- [x] **Region — lock EU / Frankfurt up front** for BOTH Render and Neon, so the datasource is
      co-located from the first provisioning step (removes the forward reference in Phase 3). Co-location
      protects the ~2s recommendation budget; cross-region RTT is the latency edge case.
      **DECIDED 2026-06-10: EU / Frankfurt for both providers** (closest EU region to a PL-based developer
      and user base; both providers offer a $0 tier there).
  - Render region name: **Frankfurt** (used in `render.yaml` as `region: frankfurt`, Phase 4).
  - Neon region id: **`aws-eu-central-1`** = "AWS Europe (Frankfurt)" (used in step 0.5 / Phase 3).
  - Neon's region is **fixed at project creation and cannot be changed** — picking Frankfurt now avoids a
    project-recreate later.

### 0.2 — Create the Render account + workspace

- [x] Go to **`https://dashboard.render.com`** and sign up. Use **"Sign up with GitHub"** (recommended —
      it pre-authorizes the Git connection in 0.3 and lets you log in with GitHub later). Email/Google also
      work. **No credit card is requested** for the free path. (Done 2026-06-10 — signed up via **email**.)
- [x] After signup Render **auto-creates your first workspace on the Hobby ($0) plan** — no action needed.
      (Render reworked workspace plans on 2026-04-23; the free tier is still called **Hobby**. You can hold
      up to 5 Hobby workspaces.) Confirm the workspace dropdown (top-left) shows a Hobby workspace; do NOT
      create or upgrade to a paid workspace.

### 0.3 — Connect the GitHub repo to Render

- [x] Connect repo `git@github.com:wbiniecki/NextSlope-10x.git` to Render (OAuth) — required for
      auto/triggered deploys and for the Blueprint to read `render.yaml` from the connected repo.
      (Done 2026-06-10 — GitHub linked + repo access granted to the Render GitHub App.)
  - If you signed up with GitHub in 0.2, the account link already exists; you may still need to grant the
    **Render GitHub App** access to *this specific repo*.
  - Grant repo access via **`https://github.com/apps/render/installations/new`** (or Render dashboard →
    account settings → **GitHub**) and select the `NextSlope-10x` repo (or "All repositories").
  - The actual Blueprint/service is created later from the dashboard (**New > Blueprint**, Phase 4) — that
    flow also surfaces a "connect repo" prompt if you skip it here.
  - NOTE: the Render CLI **cannot** launch a Blueprint — its only Blueprint command is
    `render blueprints validate [file]`; Blueprints are created from the dashboard.

### 0.4 — Install + authenticate the Render CLI (macOS)

- [x] `brew update && brew install render` — it is a Homebrew **formula, not a tap** (do not
      `brew tap render-oss/...`). (Done 2026-06-10 — installed **render v2.20.0** at `/opt/homebrew/bin/render`.)
- [x] `render login` — opens a browser; click **Authorize CLI**, return to the terminal, and **select the
      Hobby workspace** when prompted.
- [x] Verify: `render whoami` (shows your account) and `render services` (lists services — empty is fine).

### 0.5 — Create the Neon account + free project (Frankfurt)

- [x] Go to **`https://console.neon.tech/signup`** and sign up (email, GitHub, or Google). The **Free
      plan is $0/mo** with no credit card. (Done 2026-06-10.)
- [x] Click **New Project** and set:
  - **Project Name**: e.g. `nextslope`.
  - **Region**: **AWS Europe (Frankfurt) / `aws-eu-central-1`** (must match 0.1 — region is permanent).
  - **Postgres version**: latest is fine (console default). Then click **Create Project**.
  - Do NOT copy/store the connection string yet — pooled-string capture, JDBC reshaping, and the
    pooling/`-pooler` details are handled in **Phase 3** so secrets are gathered right before they are
    pasted into Render (Phase 4).

### 0.6 — Install + authenticate the Neon CLI (macOS, optional but recommended)

- [x] `brew install neonctl` (or `npm i -g neonctl`); the installed command is **`neon`**.
      (Done 2026-06-10 — installed **neonctl 2.23.1** at `/opt/homebrew/bin/neon`.)
- [x] `neon auth` — launches browser authentication.
- [x] Verify: `neon projects list` (should show the `nextslope` project with Region Id `aws-eu-central-1`).
      Console-only setup is also fine — the CLI is just convenience.
  - CLI caveat: `neon projects create` defaults to the newest Postgres version; use the **Console** (0.5) if
    you need to pin an older version.

### 0.7 — Confirm no extra secrets are needed

- [x] **No `RENDER_API_KEY` needed for the chosen path.** The selected "After CI Checks Pass" deploy mode
      needs no API key and no deploy hook. The `RENDER_API_KEY` line in `infrastructure.md` applies ONLY
      to the API/CLI deploy fallback — flag/reconcile it there so it is not stored unnecessarily.
      (Done 2026-06-10 — reconciled in `infrastructure.md` Operational Story → Secrets; nothing to store.)

### 0.8 — Confirm live free-tier limits before relying on them

Verified against live Render + Neon docs on 2026-06-10. The infra doc's bandwidth contradiction is now
**RESOLVED** (see first item).

- [x] **Render bandwidth — CONTRADICTION RESOLVED: 5 GB/mo.** The 5 GB-vs-100 GB ambiguity was the
      legacy-vs-new plan split. Render's **new Hobby plan (effective 2026-04-23)** includes **5 GB
      outbound bandwidth/mo** ($0.15/GB overage); the **100 GB** figure was the *legacy* Hobby plan. This
      workspace was created 2026-06-10 (after the cutover), so it is on the **new plan = 5 GB**. Overage
      with **no payment method on file → all Free services suspended until next month** (not billed).
      Fine for a low-traffic MVP/demo; watch it if traffic grows.
- [x] **Render Free web compute**: **750 Free instance-hrs/workspace/mo** (per *workspace*, not per
      service; resets monthly, no rollover). Spins down after **15 min idle** (~1 min cold start);
      spun-down time does **not** consume instance-hrs. Also: **500 build-pipeline min/mo**, up to **25
      services**, 2 environments. (Render's own free Postgres = 1 GB and self-destructs — not used here;
      we use Neon.)
- [x] **Neon Free plan** (per neon.com/pricing): **0.5 GB storage/project**, **100 CU-hours/project/mo**
      compute (≈400 hrs at the 0.25-CU min; resets monthly), **5 GB egress/project/mo**, 100 projects,
      10 branches/project, autoscaling up to 2 CU (~8 GB RAM), **scale-to-zero after 5 min idle (cannot
      be disabled on Free)**, history window 6 h (1 GB). Hitting **any** monthly limit suspends compute
      until the next billing month. The 5-min autosuspend is exactly why Phase 1 sets
      `initialization-fail-timeout=-1`. All limits are comfortable for this MVP.

## Phase 1 — Make the app deploy-ready

Edits to `src/main/resources/application.properties` (+ a new profile file) and a new `SecurityConfig`.

- [x] **Port binding** — Render forwards to `0.0.0.0:$PORT` (default `10000`). Spring binds to `0.0.0.0`
      by default; just make the port dynamic:
      `server.port=${PORT:8080}`.
- [x] **Health check** — `spring-boot-starter-actuator` is already a dependency. Confirm
      `/actuator/health` returns `200 {"status":"UP"}`. (Verified 2026-06-10 via local bootRun:
      `HTTP 200 {"groups":["liveness","readiness"],"status":"UP"}`.)
  - [x] *(Optional)* `management.endpoints.web.exposure.include=health` — this is already the default;
        include it only as explicit documentation. `health.show-details` defaults to `never`, so the
        public body is just `{"status":"UP"}` — safe to `permitAll`. Keep all other actuator endpoints
        closed.
- [x] **Security: permit the health check and static assets** — add
      `src/main/java/com/nextslope/config/SecurityConfig.java` with a `SecurityFilterChain` that
      `permitAll()` on at least `/actuator/health` (plus `/css/**`, `/js/**`, `/webjars/**`). Without this,
      an unauthenticated health-check request 302-redirects to `/login`; Render accepts `3xx` as healthy,
      so the deploy still goes live but the health signal is meaningless and the public URL is a login wall
      (a `httpBasic` 401 would instead fail the check). `permitAll` fixes both. **Spring Boot 4 ships Spring
      Security 7 — the lambda DSL is mandatory and the bean must
      return `http.build()`.** Use exactly:

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index", "/actuator/health", "/css/**", "/js/**", "/webjars/**").permitAll()
                .anyRequest().authenticated())
            .formLogin(Customizer.withDefaults());
        return http.build();
    }
}
```

- [x] **Public landing (CONFIRMED: add a real index page)** — because there are no controllers yet, the
      public URL would otherwise redirect to Spring Security's `/login`. Add:
  - [x] `src/main/java/com/nextslope/web/HomeController.java` — a `@Controller` mapping `/` to an
        `index` view.
  - [x] `src/main/resources/templates/index.html` — minimal Thymeleaf page (Bootstrap 5 + HTMX CDN per
        tech-stack base layout) so the root URL shows a real public home.
  - [x] `permitAll()` on `/` (and `/index`) in `SecurityConfig`.
- [x] **Custom error page** — add `src/main/resources/templates/error.html` so the public site never
      shows Spring's Whitelabel error page on a 4xx/5xx.
- [x] **HTTPS / proxy headers** — Render terminates TLS automatically (free, auto HTTP->HTTPS), so no cert
      work is needed. Add `server.forward-headers-strategy=framework` to `application.properties` so Spring
      builds correct `https` redirect URLs / scheme behind Render's proxy. Devtools (`developmentOnly`) is
      NOT packaged in the prod boot jar, so no extra exclusion is required.
- [x] **Profiles / datasource split** — keep H2 for local + CI, Postgres for prod:
  - [x] Create `src/main/resources/application-prod.properties` reading Neon from env (placeholders ONLY —
        no literal credentials; see the Secrets bullet in Critical context):
        `spring.datasource.url=${SPRING_DATASOURCE_URL}`,
        `spring.datasource.username=${SPRING_DATASOURCE_USERNAME}`,
        `spring.datasource.password=${SPRING_DATASOURCE_PASSWORD}`,
        `spring.jpa.hibernate.ddl-auto=update` (safe no-op for this deploy — no JPA entities yet; see
        Phase 3 for the migration path).
  - [x] **Defense-in-depth**: `spring.h2.console.enabled=false` in the prod profile (the H2 console
        dependency is also moved to `developmentOnly` in `build.gradle`, so it can never ship to prod).
  - [x] **Neon autosuspend survival**: `spring.datasource.hikari.initialization-fail-timeout=-1` so the
        app boots even while Neon's compute is waking. Also size the pool small with a shorter lifetime
        for Neon autosuspend: `spring.datasource.hikari.maximum-pool-size=5` and
        `spring.datasource.hikari.max-lifetime=300000`.
  - [x] **Cap thread-stack memory**: `server.tomcat.threads.max=50` (each thread reserves stack space; see
        the JVM memory rationale in Phase 2).
  - [x] Render will set `SPRING_PROFILES_ACTIVE=prod` (Phase 4) so local/test stays on H2.
- [x] **JVM memory** is handled in the Dockerfile via `JAVA_TOOL_OPTIONS` (Phase 2). (Done in Phase 2 —
      flags applied and confirmed in container logs.)

## Phase 2 — Containerize (no native Java runtime on Render)

- [x] Add a root `Dockerfile`. Improve on the infra-doc sample with **dependency-layer caching** to
      protect the 500 build-minutes/mo budget, and guard the gradlew exec bit:

```dockerfile
# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew
COPY src ./src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon clean bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=50 -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=64m -XX:MaxDirectMemorySize=64m -Xss512k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
EXPOSE 10000
ENTRYPOINT ["java","-jar","app.jar"]
```

  - **JVM memory rationale (CRITICAL):** `MaxRAMPercentage` sizes the **HEAP ONLY**, but Render Free
    enforces a hard **512 MB whole-container cgroup cap** and counts everything. The old
    `MaxRAMPercentage=65` (~333 MB heap) leaves only ~179 MB for metaspace + code cache + thread stacks +
    direct buffers + GC/JVM native + JRE/OS; a realistic busy worst case is ~533 MB, which OOM-kills the
    container (a real Render-Free OOM used exactly that config). So drop heap to 50% and **cap off-heap
    explicitly** (metaspace, code cache, direct memory, per-thread stack) with margin, and use SerialGC to
    minimize GC native overhead. Pair with `server.tomcat.threads.max=50` (Phase 1) to bound thread-stack
    memory.
  - `-x test` skips tests in the image build because CI is the test gate (Phase 5); flip to running tests
    here if you prefer the image to be self-validating.
  - **Build caching (BuildKit required):** the `--mount=type=cache,target=/root/.gradle` cache mount reuses
    the Gradle dependency cache across builds (Render caches Docker layers and supports BuildKit cache
    mounts), replacing the fragile `dependencies || true` primer. It needs the `# syntax=docker/dockerfile:1`
    directive on the first line (already shown) AND BuildKit enabled. Render builds with BuildKit by
    default; Docker Desktop / Engine 23.0+ also default to BuildKit. On older/CLI setups, `RUN --mount` errors
    with "the --mount option requires BuildKit" — enable it with `DOCKER_BUILDKIT=1` or use `docker buildx
    build`. Keep `chmod +x gradlew` and keep copying the gradle wrapper + build files before `src` so layer
    reuse holds.
- [x] Add `.dockerignore` (exclude `build/`, `.gradle/`, `.git/`, `.idea/`, `context/`, `*.md`) to keep
      the build context small and avoid shipping stale local build output.
- [x] **Edge case — gradlew permission denied** inside the build stage: handled by `chmod +x gradlew`.
      If it still fails, invoke as `sh ./gradlew ...`. (No issue hit — `chmod +x gradlew` worked.)
- [x] Local validation before pushing (BuildKit must be on — see caching note above):
      `DOCKER_BUILDKIT=1 docker build -t nextslope . && docker run -p 8080:10000 -e PORT=10000 nextslope`
      then hit `http://localhost:8080/actuator/health`.
      (Verified 2026-06-10 on Docker 20.10.17 with `DOCKER_BUILDKIT=1`: image built OK, container started
      in ~3.2s on port 10000, `/actuator/health`→200 `{"status":"UP"}`, `/`→200, and the JVM logged
      `Picked up JAVA_TOOL_OPTIONS` with all heap/off-heap caps. BuildKit was required — daemon predates
      the 23.0 default.)

## Phase 3 — Provision external Neon Postgres

- [x] Confirm the Neon project lives in **EU / Frankfurt (eu-central / `aws-eu-central-1`)** (locked in
      Phase 0) to co-locate with the Render Frankfurt region. (Verified 2026-06-10 via `neon projects list`:
      project `nextslope` / `royal-forest-10506783`, Region Id `aws-eu-central-1`.)
- [x] In the Neon Connect widget, enable **Connection pooling** and copy the **POOLED** string for the app
      (hostname contains `-pooler`). Neon's pooler is **PgBouncer in TRANSACTION mode**, but
      **protocol-level prepared statements ARE supported** (`max_prepared_statements`), so default
      Hibernate/pgjdbc works with **no `prepareThreshold=0` needed**. Session-level features and any future
      DB migrations must use the **DIRECT (non-pooler)** endpoint.
- [x] **No networking config needed.** Neon Free has **no IP allowlist** (that is Scale-tier only; the
      default is `0.0.0.0/0`), so Render Free's dynamic outbound IPs connect freely. `sslmode=require`
      needs no extra cert config. (Confirmed — Free plan, no allowlist to configure.)
- [x] **Edge case — reshape the Neon string into a JDBC URL.** Neon hands you
      `postgresql://user:pass@...-pooler.<region>.aws.neon.tech/db?sslmode=require&channel_binding=require`.
      For Spring/Hikari you need a `jdbc:` URL and credentials supplied separately:
  - `SPRING_DATASOURCE_URL=jdbc:postgresql://ep-...-pooler.<region>.aws.neon.tech/<db>?sslmode=require`
  - `SPRING_DATASOURCE_USERNAME=<user>`
  - `SPRING_DATASOURCE_PASSWORD=<pass>`
  - NOTE on `channelBinding`: pgjdbc **silently ignores** the snake_case `channel_binding` param (it does
    not fail — it just falls back); the pgjdbc property is camelCase `channelBinding`. Boot 4.0.6's bundled
    pgjdbc (42.7.x) supports `channelBinding=require`, so appending `&channelBinding=require` is **safe**;
    `sslmode=require` alone is also fine.
- [x] **Migrations / seed data (future work).** `ddl-auto=update` is a safe no-op for THIS deploy (the
      scaffold has no JPA entities yet). For future schema/seed work, Spring Boot 4 needs an explicit
      `spring-boot-starter-flyway` or `spring-boot-starter-liquibase` (not on the classpath today), and
      migrations must run against the **DIRECT** Neon endpoint and stay backward-compatible (no rollback).
      (Confirmed no-op for this deploy — `ddl-auto=update` already set in `application-prod.properties`;
      no migration tooling added now, as intended.)
- [ ] **Commit and push everything to `main` first.** Before launching the Blueprint (Phase 4), push the
      Phase 1-2 app files (`SecurityConfig`, `Dockerfile`, `.dockerignore`, `index.html`, `error.html`),
      the Phase 4 `render.yaml`, AND the Phase 5 `ci.yml` in one push. Reasons: (a) the dashboard Blueprint
      flow reads `render.yaml` from the connected repo, (b) the first build needs the Dockerfile/code,
      (c) `ci.yml` must already be on `main` so the commit carries a passing `Test` check before any
      CI-gated deploy (avoids Render's "zero checks -> won't deploy" gap).

## Phase 4 — Render Blueprint (`render.yaml`) + secrets

- [x] Add a root `render.yaml` declaring a Docker web service on the **Free** plan:
      (Created 2026-06-10 and validated: `render blueprints validate render.yaml` → `"valid": true`,
      1 service `nextslope`.)

```yaml
services:
  - type: web
    name: nextslope
    runtime: docker
    plan: free
    region: frankfurt
    dockerfilePath: ./Dockerfile
    healthCheckPath: /actuator/health
    autoDeployTrigger: checksPass
    envVars:
      - key: SPRING_PROFILES_ACTIVE
        value: prod
      - key: SPRING_DATASOURCE_URL
        sync: false
      - key: SPRING_DATASOURCE_USERNAME
        sync: false
      - key: SPRING_DATASOURCE_PASSWORD
        sync: false
```

  - `sync: false` declares a secret key WITHOUT a value in the file (never committed; see the Secrets
    bullet in Critical context). **Enter the three Neon values from Phase 3 DURING the Blueprint launch
    prompt — not after.** Render's default on-commit auto-deploy will otherwise boot the prod profile with
    no datasource and fail the health check.
  - `region: frankfurt` co-locates with the Neon EU/Frankfurt project (CONFIRMED).
  - `plan: free` flips to `starter` later (single edit) to remove cold starts.
  - `autoDeployTrigger: checksPass` sets the CI-gated deploy mode **declaratively** (this field replaces
    the deprecated boolean `autoDeploy`; valid values are `commit`, `checksPass`, `off`). This means
    "After CI Checks Pass" does not require a separate dashboard step — but the zero-checks caveat still
    applies, so `ci.yml` must already be on `main` (see Phase 3 push step and Phase 5).
- [ ] Create the service from the Blueprint via the Render dashboard: **New > Blueprint**, select the
      connected repo, and Render reads `render.yaml`. (The Render CLI cannot launch a Blueprint; use
      `render blueprints validate render.yaml` beforehand to catch errors.)
- [ ] **Edge case — `PORT`**: do not hardcode. Render injects `PORT=10000`; the app already reads
      `${PORT:8080}`. Confirm the service is reachable on its public `.onrender.com` URL.

## Phase 5 — CI/CD (GitHub Actions test gate -> deploy)

`AGENTS.md` requires `.github/workflows/` to exist before relying on CI gating; it does not yet.

**Deploy trigger (CONFIRMED: Render native "After CI Checks Pass").** Per Render docs there are two
documented patterns; this plan uses the first:

- **(Chosen) Native auto-deploy "After CI Checks Pass"** — Render reads the commit's GitHub Actions
  check results and deploys only when all pass. No deploy hook, no API key, nothing to rotate. This is set
  **declaratively** via `autoDeployTrigger: checksPass` in `render.yaml` (Phase 4); the dashboard
  Settings -> Auto-Deploy toggle is the equivalent manual alternative. Caveat from Render docs: if **zero**
  checks are detected for a commit, Render will not deploy — so the workflow below must always run on
  pushes to `main`.
- **(Documented fallback) Deploy Hook + GitHub Actions `curl`** — disable auto-deploy, store the service's
  Deploy Hook URL as GitHub secret `RENDER_DEPLOY_HOOK_URL`, and `curl` it from a `Deploy` step gated on
  the test job. This is Render's own Deploy Hooks example. Use it if "After CI Checks Pass" proves flaky.

Steps:

- [x] Add `.github/workflows/ci.yml`: (Created 2026-06-10; YAML lint-clean; `gradlew` is mode 100755 in
      git so the runner can execute it.)
  - [x] Trigger on PRs to `main` and pushes to `main`.
  - [x] `Test` job: `actions/checkout@v4`, set up Temurin 21 (`actions/setup-java@v4`, `cache: gradle`),
        run `./gradlew test --no-daemon` (uses H2; no DB secrets needed). This job IS the CI check Render
        waits on (the GitHub check name will be `Test`).
- [ ] Deploy mode is already `autoDeployTrigger: checksPass` from `render.yaml` (Phase 4) — no dashboard
      toggle needed. Just confirm the `Test` check has passed at least once on `main` before relying on the
      gate (otherwise the "zero checks -> won't deploy" gap blocks the first deploy). To switch manually
      instead, use dashboard Settings -> Auto-Deploy.
- [ ] (Only if using the fallback) add the `Deploy` `curl` step + `RENDER_DEPLOY_HOOK_URL` GitHub secret,
      and set Auto-Deploy = Off in Render.

## Phase 6 — First deploy and public-access verification

- [ ] **Trigger the first gated deploy with a fresh commit** (or a Manual Deploy) so the CI-gated path is
      actually exercised: watch GitHub Actions -> `Test` check green -> Render auto-deploys (After CI
      Checks Pass), then run the public-access checks below.
- [ ] Watch the Render build/deploy: `render logs --resources <srv-id> --tail` or the dashboard.
- [ ] **Public accessibility checks** (the core acceptance criteria):
  - [ ] `curl -i https://<service>.onrender.com/actuator/health` returns `200` `{"status":"UP"}`
        from the public internet (no auth).
  - [ ] Open the public URL in a browser; confirm it loads the chosen landing (index page or login),
        not a connection error.
  - [ ] Confirm the health check is green in the Render dashboard (proves the security permitAll worked).
- [ ] **Cold-start expectation**: first hit after 15 min idle spins the instance up in ~1 min. Render
      shows a **branded loading page** during spin-up (not a blank or "broken" screen), so the demo never
      looks down. Free is capped at 750 instance-hrs/mo; the one-toggle Starter ($7) upgrade removes cold
      starts.

## Phase 7 — Post-deploy hardening and edge-case runbook

- [ ] **OOM on 512 MB** (infra doc: High likelihood) — if the service restarts under load, confirm
      `JAVA_TOOL_OPTIONS` is applied (logs show the flags: `MaxRAMPercentage=50`,
      `MaxMetaspaceSize=128m`, `ReservedCodeCacheSize=64m`, `MaxDirectMemorySize=64m`, `-Xss512k`,
      `UseSerialGC`) and that `server.tomcat.threads.max=50` is set. If it still OOM-kills, lower the heap
      percentage or thread cap further; load-test once the recommend endpoint exists.
- [ ] **Build minutes exhausted** — if the 500 min/mo budget runs low, verify the Dockerfile dependency
      layer is caching (no full re-download each build); avoid no-op redeploys.
- [ ] **Neon cold-resume timeouts** — first query after idle may lag; `initialization-fail-timeout=-1`
      already prevents boot failure. If queries time out, raise Hikari `connection-timeout`.
- [ ] **Rollback** — dashboard Deploys -> Rollback (Free retains 5 builds) or `render deploys`. DB
      migrations do NOT roll back; keep schema changes backward-compatible.
- [ ] **Migrations (when schema lands)** — add `spring-boot-starter-flyway` or
      `spring-boot-starter-liquibase` (neither is on the classpath today), run migrations against the
      **DIRECT** Neon endpoint, and keep every change backward-compatible (no rollback path on Neon).
- [ ] **CSRF forethought** — CSRF stays enabled. When HTMX/POST forms land (per the AGENTS.md HTMX
      requirement), they must send the CSRF token.
- [ ] **Secret rotation** — edit the env var value in Render (triggers redeploy). Human approves any
      paid-tier/DB change per infra doc approval policy.
- [ ] **Graduation toggle** — when cold starts hurt: change `plan: free` -> `starter` ($7/mo), redeploy.

---

## Files this plan will create or change (during implementation)

- `src/main/resources/application.properties` — `server.port=${PORT:8080}`, optional actuator exposure,
  `server.forward-headers-strategy=framework`.
- `src/main/resources/application-prod.properties` (new) — Neon datasource, Hikari pool
  (`maximum-pool-size=5`, `max-lifetime=300000`, `initialization-fail-timeout=-1`),
  `server.tomcat.threads.max=50`, `spring.h2.console.enabled=false`.
- `build.gradle` — move `spring-boot-h2console` from `implementation` to `developmentOnly` so it can never
  ship to prod.
- `src/main/java/com/nextslope/config/SecurityConfig.java` (new) — `@Configuration @EnableWebSecurity`
  class returning `http.build()`; permits `/`, `/index`, `/actuator/health`, and static assets.
- `src/main/java/com/nextslope/web/HomeController.java` (new) — public index landing.
- `src/main/resources/templates/index.html` (new) — minimal Bootstrap 5 + HTMX home page.
- `src/main/resources/templates/error.html` (new) — custom error page (no Whitelabel).
- `Dockerfile` (new) — multi-stage, BuildKit gradle cache mount, memory-tuned JVM flags.
- `.dockerignore` (new).
- `render.yaml` (new) — Free Docker web service (Frankfurt), health check, prod profile, secret placeholders.
- `.github/workflows/ci.yml` (new) — `gradlew test` check that Render gates the deploy on.

## Decisions (confirmed)

1. Public landing: **add a real index page** (`HomeController` + `index.html`), `permitAll()` on `/` —
   the public URL shows a home page, not a login wall.
2. Deploy trigger: **Render native "After CI Checks Pass"** (dashboard Auto-Deploy setting) gating on the
   GitHub Actions `gradlew test` check; **Deploy Hook + `curl`** kept as the documented fallback.
3. Region: **EU / Frankfurt** for both Render and Neon (co-located).
4. Public domain: **use the free `*.onrender.com` URL** as the public address (keeps $0). Render provides
   it automatically with free managed TLS and automatic HTTP->HTTPS. No domain purchase — a truly free
   custom registrable domain does not exist in 2026 (Freenom defunct); a custom domain would be paid
   (~$10-15/yr) and is OUT OF SCOPE for this deploy. Render Free supports custom domains + free TLS later
   if wanted.
5. H2 console: **move `spring-boot-h2console` to `developmentOnly`** in `build.gradle` (so it can never
   ship/enable in prod) AND set `spring.h2.console.enabled=false` in the prod profile as
   defense-in-depth.
