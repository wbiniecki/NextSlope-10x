# NextSlope code review

**Blocked** — 1 blocking finding at or above `high`, out of 1 finding.

## Blocking reasons

- critical: flyway-forward-only at src/main/resources/db/migration/V1__create_users.sql:6 — This edits the existing, already-applied V1__create_users.sql to add a display_name VARCHAR(100) column, rather than adding a new V6__ migration. V1-V5 are committed and applied; on Neon's free tier (no rollback) this in-place edit can never be corrected, and any environment that already ran V1 will have a schema that silently diverges from this file going forward. The fix is a new forward-only migration such as V6__add_display_name_to_users.sql performing an ALTER TABLE.

## Criterion scores

| Criterion | Score | Justification |
| --- | --- | --- |
| `flyway-forward-only` | 2/10 | The diff modifies src/main/resources/db/migration/V1__create_users.sql (index line shows an existing blob hash changing, with no 'new file mode' marker), adding a display_name column to an already-committed, already-applied migration. Per the stated convention, V1-V5 are applied and corrections belong in a new V{n+1}__ file; editing V1 in place is exactly the prohibited pattern, and on Neon's rollback-less free tier this cannot be corrected after the fact. |
| `ddl-auto-validate` | 8/10 | No spring.jpa.hibernate.ddl-auto property is touched anywhere in the diff. The migration edit adds a display_name column, but no corresponding JPA entity/field mapping is shown in this diff, so there is no visible new unmapped column under validate. Scored short of full marks because the diff gives no entity change to confirm consistency, and the underlying migration-edit defect could compound with an out-of-diff entity change. |
| `constructor-injection` | 10/10 | No production Spring component (@Controller/@Service/@Component/@Repository/@Configuration) is added or modified in this diff; the changes are CI workflow YAML, promptfoo tooling, and a SQL migration. No field/setter @Autowired or @Inject appears anywhere touched. |
| `access-control-scoping` | 10/10 | No controller, endpoint, or repository query is introduced or modified in this diff. Nothing here touches request paths, principal resolution, or ownership predicates. |
| `e2e-conventions` | 10/10 | No files under src/e2eTest/java/ are touched by this diff, so none of the Playwright conventions (waits, locators, teardown, seed data, spinner assertions) are implicated. |

Scores are diagnostic. Only findings at or above the fail-on severity block the change.

## Findings

### `src/main/resources/db/migration/V1__create_users.sql`

- **line 6** · critical · `flyway-forward-only` — This edits the existing, already-applied V1__create_users.sql to add a display_name VARCHAR(100) column, rather than adding a new V6__ migration. V1-V5 are committed and applied; on Neon's free tier (no rollback) this in-place edit can never be corrected, and any environment that already ran V1 will have a schema that silently diverges from this file going forward. The fix is a new forward-only migration such as V6__add_display_name_to_users.sql performing an ALTER TABLE.

---
Reviewed commit `d7e229910a20b42aa43a7d245a88f9a03c5ff6ec` · [workflow run](https://github.com/wbiniecki/NextSlope-10x/actions/runs/31276149189)

# NextSlope code review

**Passed** — no findings.

## Criterion scores

| Criterion | Score | Justification |
| --- | --- | --- |
| `flyway-forward-only` | 10/10 | The diff touches no SQL migration files under src/main/resources/db/migration or similar; it is entirely GitHub Actions workflow/action YAML, a promptfoo eval suite for packages/code-reviewer, and documentation. No existing V{n}__*.sql file is modified and no new migration is added, so this criterion is not implicated. |
| `ddl-auto-validate` | 10/10 | No Spring configuration files (application*.properties/yaml) or JPA entity classes are touched anywhere in the diff. The change is confined to .github/, context/, and packages/code-reviewer (a Node/TypeScript CLI package), so ddl-auto and entity-mapping concerns do not apply. |
| `constructor-injection` | 10/10 | No Spring-annotated classes (@Controller/@Service/@Component/@Repository/@Configuration) under src/main/java are added or modified. The only Java-adjacent-style code introduced is TypeScript/JavaScript in packages/code-reviewer (e.g. promptfoo/provider.js, promptfoo/grader.js), which is outside this criterion's scope. The single production-code edit (exporting REVIEW_ROOT in src/cli.ts) is unrelated to DI style. |
| `access-control-scoping` | 10/10 | No web controllers, request paths, or repository query methods are touched. Nothing in the diff introduces a client-supplied identifier used to load owned data, alters CurrentUserService usage, or widens security permit-lists — the entire change is CI plumbing and an eval harness. |
| `e2e-conventions` | 10/10 | No files under src/e2eTest/java/ are added or modified in this diff, so none of the Playwright-specific conventions (locator strategy, waits, teardown ordering, spinner assertions, naming) are implicated. |

Scores are diagnostic. Only findings at or above the fail-on severity block the change.

## Findings

None.

---
Reviewed commit `daf263da2956425d3d91ca1c0cf3a5c8d237f27f` · [workflow run](https://github.com/wbiniecki/NextSlope-10x/actions/runs/31276260872)

