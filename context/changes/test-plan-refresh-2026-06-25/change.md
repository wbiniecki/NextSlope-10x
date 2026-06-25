---
change_id: test-plan-refresh-2026-06-25
title: Refresh test plan with scoped PIT gate for Phase 2
status: impl_reviewed
created: 2026-06-25
updated: 2026-06-25
archived_at: null
---

## Notes

Refresh of context/foundation/test-plan.md (via /10x-test-plan --refresh). Plan-amendment change ONLY — do not wire or run PIT yet (the recommender slice S-05 it targets is still `proposed`).

Goal: fold a SCOPED PIT mutation-testing gate into the test plan's Phase 2 (recommender correctness suite) spec, so that when Phase 2 is implemented it includes mutation testing confined to the recommender scoring/filter/rationale packages — never repo-wide.

Edits to the guide (the chain's final sub-phase applies them):
- §4 Stack: add PIT row — info.solidsoft.pitest 1.19.0 + junit5PluginVersion (pitest-junit5-plugin); Java 17+/Gradle 8.4+ confirmed against Java 21 stack, grounded 2026-06-25.
- §3 Phase 2: extend goal/test-types to include the scoped PIT run.
- §5 Quality Gates: add "recommender mutation-score gate (PIT, scoped to recommender packages) — required after §3 Phase 2"; package-scoped threshold, not repo-wide.
- §6.5 cookbook: note the PIT gate (kill mutants in scorer/filter/rationale; expected values derive from user input, never the generator — the oracle-problem guard from Risk Response Guidance #1/#2).
- §7: record that mutation testing is deliberately scoped to the recommender only (cost × signal), not applied repo-wide.
- §8: freshness stamp 2026-06-25.

Do NOT rewrite §1/§2 beyond the citations above.
