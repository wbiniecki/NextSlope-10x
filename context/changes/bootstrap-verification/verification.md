---
bootstrapped_at: 2026-05-25T19:50:07Z
starter_id: spring
starter_name: Spring Boot
project_name: nextslope
language_family: java
package_manager: gradle
cwd_strategy: subdir-then-move
bootstrapper_confidence: verified
phase_3_status: ok
audit_command: "null"
---

## Hand-off

Hand-off frontmatter, copied verbatim from `context/foundation/tech-stack.md`:

```yaml
starter_id: spring
package_manager: gradle
project_name: nextslope
hints:
  language_family: java
  team_size: solo
  deployment_target: fly
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
```

### Why this stack (verbatim from hand-off body)

Solo backend developer with minimal frontend experience shipping a 3-week
after-hours MVP for a 20–40 resort comparison tool with email/password auth,
profile editing, a deterministic rule-based recommender, and a small admin
CRUD surface. Spring Boot is the recommended default for `(web, java)` and
clears all four agent-friendly gates within the Java family; bootstrapper
confidence is verified, so scaffolding will be smooth. Thymeleaf is wired in
at scaffold time so the entire UI is server-rendered HTML — no SPA tier, one
language, one build, one deploy, one test runner — the only configuration
that respects both the "frontend must be easy to learn" constraint and the
3-week timeline. Spring Security covers the auth FRs out of the box. Fly.io
is Spring's first deployment default and fits the small-scale, low-QPS profile
of the PRD; CI runs on GitHub Actions with auto-deploy-on-merge — the standard
solo-developer shape.

The hand-off body also lists two post-scaffold UI additions (Bootstrap 5 via
CDN, HTMX via CDN) that are intentionally NOT applied by bootstrapper — they
are documented in the hand-off as hand-applied steps after scaffold.

## Pre-scaffold verification

| Signal       | Value                                                  | Severity | Notes                                                                                                          |
| ------------ | ------------------------------------------------------ | -------- | -------------------------------------------------------------------------------------------------------------- |
| npm package  | not run                                                | n/a      | not a JS-family starter (`language_family: java`); the `cmd_template` invokes `curl` against start.spring.io, not an npm CLI |
| GitHub repo  | `spring-projects/spring-boot` last pushed 2026-05-25T15:17:23Z | fresh    | `docs_url` is `https://docs.spring.io/spring-boot/` (not a github.com URL); repo resolved by convention as the canonical Spring Boot upstream. `gh api` returned `Bad credentials`; the timestamp came from an unauthenticated GET against `https://api.github.com/repos/spring-projects/spring-boot` |

## Scaffold log

**Strategy**: subdir-then-move (default — `spring` is not listed in
`references/bootstrapper-config.yaml` under `starters:`).

**Registry-defined invocation** (from `starter-registry.yaml`, with `{name}` substituted to `.bootstrap-scaffold`):

```
mkdir -p .bootstrap-scaffold && cd .bootstrap-scaffold && curl -s https://start.spring.io/starter.tgz -d type=gradle-project -d javaVersion=21 -d groupId=com.nextslope -d artifactId=nextslope -d name=nextslope -d packageName=com.nextslope -d dependencies=web,devtools,thymeleaf,security,data-jpa,validation,h2,postgresql,lombok,actuator | tar -xzf - --strip-components=1
```

### Registry deviation (one-time workaround applied this run)

The registry-defined `cmd_template` uses `tar -xzf - --strip-components=1`,
but the Spring Initializr `starter.tgz` response has no wrapping top-level
directory — its entries are already at the root (`build.gradle`,
`settings.gradle`, `gradlew`, `gradlew.bat`, `.gitignore`, `.gitattributes`,
`HELP.md`, plus directories `gradle/`, `src/`). Applying `--strip-components=1`
silently drops every root-level file and rebases nested paths
(`src/main/...` → `main/...`, `gradle/wrapper/...` → `wrapper/...`), leaving
the scaffold non-functional (no `build.gradle`, no `gradlew`).

`tar` returned exit code 0 on the first attempt, so this was not a
HARD-STOP-by-exit-code case but a silent partial success. With the user's
explicit consent, bootstrapper deviated from the registry for this run only
and re-extracted without `--strip-components`.

**Actually-executed invocation** (this run only):

```
mkdir -p .bootstrap-scaffold && cd .bootstrap-scaffold && curl -sS -o starter.tgz https://start.spring.io/starter.tgz -d type=gradle-project -d javaVersion=21 -d groupId=com.nextslope -d artifactId=nextslope -d name=nextslope -d packageName=com.nextslope -d dependencies=web,devtools,thymeleaf,security,data-jpa,validation,h2,postgresql,lombok,actuator && tar -xzf starter.tgz && rm starter.tgz
```

**Recommended registry fix** (file an issue / open a PR against
`/skills/10x-tech-stack-selector/references/starter-registry.yaml` line 635):
remove `--strip-components=1` so the Spring Initializr tarball extracts
correctly. The corrected template:

```
mkdir -p {name} && cd {name} && curl -s https://start.spring.io/starter.tgz -d type=gradle-project -d javaVersion=21 -d groupId=com.nextslope -d artifactId=nextslope -d name=nextslope -d packageName=com.nextslope -d dependencies=web,devtools,thymeleaf,security,data-jpa,validation,h2,postgresql,lombok,actuator | tar -xzf -
```

Additionally, the `groupId`, `artifactId`, `name`, `packageName` parameters are
hard-coded in the template — they should ideally be parameterised
(`{group_id}`, `{artifact_id}`, `{package_name}`) so the template is reusable
beyond a `nextslope`-named project. This is a follow-up registry quality
item, not blocking for this run.

**Exit code**: 0 (workaround invocation)

**Files moved**: 9 top-level entries from `.bootstrap-scaffold/` into cwd:

- `.gitattributes` (moved silently)
- `.gitignore` (moved silently — cwd had none)
- `HELP.md` (moved silently — cwd had no `HELP.md`; cwd's `project-idea.md` is untouched)
- `build.gradle` (moved silently)
- `gradle/` (directory; moved silently — contains `wrapper/gradle-wrapper.jar`, `wrapper/gradle-wrapper.properties`)
- `gradlew` (moved silently; executable bit preserved)
- `gradlew.bat` (moved silently)
- `settings.gradle` (moved silently)
- `src/` (directory; moved silently — contains `main/java/com/nextslope/NextslopeApplication.java`, `main/resources/application.properties`, `test/java/com/nextslope/NextslopeApplicationTests.java`)

**Conflicts (.scaffold siblings)**: none. cwd held only `.cursor/`,
`context/`, `package-lock.json`, `project-idea.md` before scaffold — no
collision with any scaffold-emitted path.

**.gitignore handling**: moved silently (cwd had no prior `.gitignore`,
so the scaffold's file landed at the root; no append-merge needed).

**.bootstrap-scaffold cleanup**: deleted (empty after move-up).

**context/ preservation**: confirmed. The conflict matrix drops any
scaffold-emitted path under `context/**`; the Spring scaffold did not emit
any such paths, so the rule was a no-op this run.

## Post-scaffold audit

**Tool**: skipped — no built-in audit tool for `java`.

**Recommended external tool**: OWASP Dependency-Check (Gradle plugin
`org.owasp.dependencycheck`) for offline CVE scanning, or Snyk (`snyk test`)
for hosted-database scanning. Both can be wired into the Gradle build later;
neither was applied by bootstrapper.

For Spring projects specifically, the Gradle `dependencyUpdates` task (via
`com.github.ben-manes.versions` plugin) is a useful adjacent check — it
flags out-of-date dependencies, not just vulnerable ones.

## Hints recorded but not acted on

These hint values were read from the hand-off and preserved for the audit
trail. v1 of bootstrapper does not act on any of them; a future M1L4 skill
("Memory Architecture") will use the same hint surface to generate
`AGENTS.md` / `CLAUDE.md` and to wire CI/CD scaffolding.

| Hint                       | Value                       |
| -------------------------- | --------------------------- |
| bootstrapper_confidence    | verified                    |
| quality_override           | false                       |
| path_taken                 | standard                    |
| self_check_answers         | null                        |
| team_size                  | solo                        |
| deployment_target          | fly                         |
| ci_provider                | github-actions              |
| ci_default_flow            | auto-deploy-on-merge        |
| has_auth                   | true                        |
| has_payments               | false                       |
| has_realtime               | false                       |
| has_ai                     | false                       |
| has_background_jobs        | false                       |

## Next steps

Next: a future skill will set up agent context (CLAUDE.md, AGENTS.md). For
now, your project is scaffolded and verified — happy hacking.

Useful manual steps in the meantime:

- `git init` (if you have not already) to start your own repo history. The
  scaffold's `.gitignore` and `.gitattributes` are already in place.
- Wire in the post-scaffold UI additions documented in `tech-stack.md`:
  Bootstrap 5 via CDN and HTMX via CDN, both as `<link>` / `<script>` tags
  in the Thymeleaf base fragment.
- File the registry fix for the Spring `cmd_template` (remove
  `--strip-components=1`; consider parameterising the hard-coded
  `groupId`/`artifactId`/`packageName`).
- The stray `package-lock.json` in cwd (88 bytes, predates this run) does
  not belong in a Java/Gradle project — likely a leftover from an earlier
  experiment. Delete when convenient.
- Verify the toolchain locally: `./gradlew --version` should print Gradle
  + JVM details. The Spring Initializr request asked for Java 21; ensure
  your local JDK is 21+ before `./gradlew bootRun`.
- Address future audit findings per your project's risk tolerance — there
  is no automated audit on disk yet (see Post-scaffold audit section
  above).
