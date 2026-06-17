---
change_id: persistence-migration-baseline
title: Persistence & migration baseline (local + production DBs)
status: implemented
created: 2026-06-16
updated: 2026-06-18
archived_at: null
---

## Notes

Roadmap F-01 (`context/foundation/roadmap.md`). Foundation slice: wire a schema-migration mechanism and persistence (entity/repository) conventions, verified to run identically against the local (H2) and production (Postgres/Neon) databases — closing the production profile's "migrations come later" gap. Kept minimal: tooling + conventions + one verifying migration; S-01 (account-authentication) is its first real consumer. Unlocks S-01–S-07; establishes the migrations-run-in-CI verification path.
