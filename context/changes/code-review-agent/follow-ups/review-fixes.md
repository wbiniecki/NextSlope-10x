# Review Follow-Ups — code-review-agent

Queued from `reviews/impl-review-phase-1.md`. Each entry names the phase that must consume it.

## Phase 6 — scope the "only `src/agent.ts` imports the SDK" rule to `src/`

From F4 (Phase 1 review). Phase 6's contract for `packages/code-reviewer/AGENTS.md` says it covers
"the rule that only `src/agent.ts` may import the SDK, and why". Written unqualified, that sentence
is false: `scripts/smoke.ts` imports `@anthropic-ai/claude-agent-sdk` directly, as Phase 1's contract
requires, and Phase 2's own check (2.6) scopes its grep to the `src/` tree precisely because
`scripts/` is outside it.

Word the rule as scoped to `src/`, and name `scripts/smoke.ts` as the deliberate exception. This is
not pedantry — Phase 6's manual criterion 6.4 runs this agent over the change's own diff, so an
unqualified rule would hand the reviewer a standing spurious finding against the very package that
defines it.
