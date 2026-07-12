<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Account Deletion (S-07)

- **Plan**: context/changes/account-deletion/plan.md
- **Scope**: Phase 1 of 2
- **Date**: 2026-07-12
- **Verdict**: APPROVED
- **Findings**: 0 critical, 1 warning, 2 observations
- **Reviewed commit**: 5548248 (feature/10x-12-account-deletion)
- **Reviewers**: two parallel subagents — plan-drift (Composer) and safety/pattern (GPT)

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Findings

### F1 — Concurrent visited write can survive account deletion

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/nextslope/user/AccountService.java:26-34
- **Detail**: The delete transaction is atomic but doesn't exclude concurrent writers. Because `visited_resorts` has no FK to `users` (by design), an in-flight visited toggle from another session of the same user could insert a row after `deleteByUserId` runs and commit after the user row is gone, leaving one orphaned visited row. Orphans are unreachable on every product surface (all reads are principal-scoped and the principal no longer resolves).
- **Fix A ⭐ Recommended**: Accept as a documented MVP tradeoff
  - Strength: Matches the plan's explicit "no new migration, app owns integrity" decision; orphan is unreachable data, not a user-visible correctness or privacy leak.
  - Tradeoff: A stray row could persist in the DB under a strict reading of the "removed everywhere" NFR.
  - Confidence: HIGH — race window is one request wide, MVP traffic.
  - Blind spot: PRD privacy guardrail wording, if read strictly.
- **Fix B**: Add V6 migration with FK ON DELETE CASCADE on visited_resorts
  - Strength: Closes the race at the DB level permanently.
  - Tradeoff: Contradicts the plan's decided "What We're NOT Doing"; forward-only DDL on Neon; re-opens planning.
  - Confidence: MEDIUM — safe DDL, but scope reversal mid-change.
  - Blind spot: Whether S-06's FK-free visited design had other reasons (deactivation safety) a FK would break.
- **Decision**: ACCEPTED via Fix A — documented in AccountService class javadoc as an accepted MVP tradeoff.

### F2 — Comments incorrectly describe derived delete as "bulk"

- **Severity**: 👁 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/com/nextslope/visited/VisitedResortRepository.java:19, src/main/java/com/nextslope/user/AccountService.java:29-30
- **Detail**: Spring Data JPA derived deletes load matching entities and remove them one by one — `@Modifying` doesn't make them bulk DML. Behavior is correct, but the comments (inherited from the plan's framing) misstate the mechanism.
- **Fix**: Reword the two comments to drop the "bulk" claim.
- **Decision**: FIXED — both comments reworded (repository javadoc + AccountService inline comment).

### F3 — No failure-path rollback test

- **Severity**: 👁 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: src/test/java/com/nextslope/user/AccountServicePostgresTests.java
- **Detail**: Tests prove committed deletion, two-user isolation, and the bare-user path, but never force a mid-cascade failure to prove earlier deletes roll back. `@Transactional` atomicity makes this near-certain; the plan didn't require it.
- **Fix**: Optionally add a Postgres test that blocks the user delete (test-only trigger) and asserts all rows survive rollback.
- **Decision**: FIXED — added `deleteAccountRollsBackAllTablesWhenUserDeleteFails` (test-only BEFORE DELETE trigger on users; asserts all four tables intact after rollback).

## Verified subtleties (no action needed)

- Hibernate 7.2 flushes collection removals before entity deletions and preserves entity-delete queue insertion order, so regions → profile → user ordering holds even with no mapped association between `User` and `PreferenceProfile`.
- The Postgres tests are not test-transactional: fixtures and `deleteAccount` genuinely commit before assertions — a real commit-level proof.
- The H2 slice test joins a test-managed transaction, but explicit `flush()` makes it a valid SQL/FK-ordering check (it does not prove commit behavior; the Postgres tests do).
- Postgres `@AfterEach` cleanup is FK-safe (visited → profiles as entities → users).
- Service layering, annotations, and dual-engine test structure match sibling patterns (`PreferenceProfileService`, `PreferenceProfileRepository[Postgres]Tests`).
