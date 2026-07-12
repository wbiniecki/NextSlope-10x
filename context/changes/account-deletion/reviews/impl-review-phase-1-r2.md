<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Account Deletion (S-07) — Phase 1, round 2

- **Plan**: context/changes/account-deletion/plan.md
- **Scope**: Phase 1 of 2
- **Date**: 2026-07-12
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 1 observation
- **Reviewed commits**: 5548248 (phase 1 implementation) + 42142d1 (phase 1 impl-review triage fixes)
- **Prior review**: reviews/impl-review-phase-1.md (fully triaged: F1 accepted+documented, F2/F3 fixed). This round re-verifies the phase including those fixes.

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Findings

### F1 — Plan text still misstates derived-delete semantics fixed in code by prior F2

- **Severity**: 👁 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: context/changes/account-deletion/plan.md:54-56 (Key Discoveries) and plan.md:96-98 (Critical Implementation Details)
- **Detail**: The prior review's F2 established that a Spring Data *derived* `deleteByUserId` loads matching entities and removes them one by one — it is not bulk DML, so on `PreferenceProfileRepository` it would in fact cascade the regions element collection. Only a JPQL `@Query("delete ...")` bulk delete bypasses the persistence context and orphans `preference_profile_regions`. The triage commit (42142d1) reworded the two code comments accordingly, but the plan still claims "A derived `deleteByUserId` ... would be a bulk delete that skips the element collection and violates `fk_preference_profile_regions_profile`". The implemented `findByUserId → delete(entity)` pattern is correct and arguably clearer either way, but the plan's stated mechanism is wrong and could mislead Phase 2 work or a future change that trusts the plan as ground truth.
- **Fix**: Reword the two plan sentences to attribute the orphaning risk to a JPQL `@Query` bulk delete (matching the corrected comment in AccountService.java:33-34), keeping the "entity delete, not bulk delete" conclusion unchanged.
- **Decision**: FIXED — both plan passages reworded (Key Discoveries bullet now carries an explicit "Correction, impl-review r2" note; Critical Implementation Details now pins the orphaning risk on JPQL `@Query` bulk deletes).

## Success criteria evidence (fresh runs, 2026-07-12)

- 1.1 `./gradlew test` (forced `--rerun`, Testcontainers Postgres included) → **BUILD SUCCESSFUL in 1m**, exit 0. ✅
- 1.2 `./gradlew test --rerun --tests "com.nextslope.user.AccountService*"` → **BUILD SUCCESSFUL in 20s**, exit 0. ✅
- Manual: none required for Phase 1 (per plan).

## Verified this round (no action needed)

- **Plan contracts hold**: `VisitedResortRepository.deleteByUserId` mirrors the existing `deleteByUserIdAndResortId` declaration (`@Modifying` + repository-level `@Transactional`, explicitly allowed by the plan); `AccountService.deleteAccount` matches the plan's snippet verbatim in ordering (visited → profile-as-entity → user) and annotations (`@Service`, `@RequiredArgsConstructor`, `@Transactional`). Test contracts (a/b/c cases, regions emptiness via native/JDBC count) are covered in both engines.
- **Prior triage fixes are sound**:
  - F1 accepted-tradeoff javadoc (AccountService.java:18-20) accurately describes the visited-write race and why orphans are unreachable.
  - F2 rewordings are technically accurate — repository javadoc now says "entities loaded and removed individually"; AccountService comment correctly pins the orphaning risk on JPQL `@Query` bulk deletes.
  - F3 rollback test (`deleteAccountRollsBackAllTablesWhenUserDeleteFails`) is well-constructed: test-only BEFORE DELETE trigger, cleanup in `finally` so it can't leak into other tests, non-test-transactional context so the rollback assertion is a real commit-level proof across all four tables.
- **No broken assumptions elsewhere**: `deleteByUserId` and `AccountService` are purely additive; no existing caller or test was modified beyond the two comment rewords. `TwoUserIntegrationTestBase` untouched (no Phase 1 test extends it).
- **Scope discipline**: no migration, no soft delete, no web surface — all "What We're NOT Doing" boundaries respected. The 42142d1 extras are the documented review-triage outcomes, not scope creep.
- **Working tree**: clean except untracked `context/changes/admin-resort-management/` (unrelated, pre-existing).
- **Linear**: 10X-12 is **In Progress** — correct for phase 1 done / phase 2 pending, no PR open (lessons.md sync rule satisfied; no churn needed).
- **Excluded by prior agreement**: `org.testcontainers.containers.PostgreSQLContainer` deprecation in `AccountServicePostgresTests` — pre-existing repo-wide pattern shared by all `*PostgresTests`; not counted against this change.
