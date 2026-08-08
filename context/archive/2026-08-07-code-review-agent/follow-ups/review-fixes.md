# Review Follow-Ups — code-review-agent

Queued from `reviews/impl-review-phase-1.md` and from adaptations made during implementation. Each
entry names the phase that must consume it.

## 10X-19 — decide whether an auth failure should exit 1 rather than 2

From F8 of the full-plan review (`reviews/impl-review.md`), deferred by the user on 2026-08-07.

The plan's exit table assigns `1` to "startup/auth failure before a valid session result" and `2` to
"success-without-`structured_output`". A real auth failure was observed arriving as exactly the
second shape (`impl-review-phase-1.md:152-154`): `subtype: "success"` whose text was an
authentication error. `agent.ts:184` therefore classifies it `missing_structured_output` → exit `2`,
which `README.md` documents as "the review is worth retrying".

Both plan rows are satisfiable and the code picked the more specific one, so this is a plan
ambiguity rather than drift. It matters at CI time: a workflow that retries on `2` would burn
retries on a bad credential instead of failing fast. Decide deliberately in 10X-19 — either accept
the retry behavior and document it, or sniff the result text for an auth signature and reclassify
to `startup_failure`.

## 10X-19 — unit-test `src/agent.ts`

From F5 of the full-plan review (`reviews/impl-review.md`), deferred by the user on 2026-08-07.

`src/agent.ts` holds the most branching logic in the package and has no tests: eight failure kinds,
the subtype-to-kind map at `:266-277`, the diagnostic composer at `:279-304`, the
missing-`structured_output` guard at `:184`, the terminal-result-without-init guard at `:209`, and
two stream-closed guards at `:226-257`. `test/cli.test.ts:195-217` covers `exitCodeForFailure`,
which takes a failure kind as *input* and so proves nothing about whether one is ever produced
correctly. The comment at `agent.ts:244-245` records that the no-result guard was learned the hard
way, which is exactly the knowledge that should be pinned by a test.

Approach: inject `query` as a dependency the way `cli.ts` injects `SessionRunner`, then drive
`runStructuredSession` with a hand-written async generator yielding canned `system`/`init` and
`result` messages. No network, no SDK. This matters more once CI runs the reviewer on every PR,
because a misclassified failure kind is the difference between a retry and a hard stop.

## Phase 6 — document `StructuredOutput` as part of the expected tool surface

From a Phase 2 implementation adaptation, approved by the user on 2026-08-07.

Criterion 2.4 as written expects the `system`/`init` message to report "only `Read`, `Glob`, and
`Grep`" as available tools. The live run reported `["Glob","Grep","Read","StructuredOutput"]`.
`StructuredOutput` is the SDK's end-turn carrier: setting `outputFormat: { type: 'json_schema' }`
makes the session an end-turn tool session, and the model delivers its payload by calling that tool.
It is injected by the option itself, not granted by `tools` / `allowedTools`, and removing it would
mean abandoning structured output — the reason this change chose the Claude Agent SDK over the Cursor
SDK in the first place.

2.4 was therefore read as "no tool beyond `Read`/`Glob`/`Grep` plus the SDK-injected carrier", which
the run satisfies. The read-only posture is independently evidenced: Phase 1's smoke printed roughly
thirty default tools including `Bash`, `Edit`, `Write`, and `Task`, and none of them appear here.

When Phase 6 writes the tool-posture section of `packages/code-reviewer/AGENTS.md`, state the
expected surface as the three read-only tools **plus** `StructuredOutput`, so a future reader — or
this agent reviewing itself — does not read the carrier as a permission leak.

## Phase 5 — the end-turn carrier consumes a turn, so the usable turn budget is smaller than it looks

From the same Phase 2 live run. With `maxTurns: 1` the result reported `num_turns: 2`, so the
carrier tool call appears to count against the budget. The plan's `maxTurns: 3` therefore buys fewer
reasoning turns than its face value suggests.

Not a defect and nothing to change now — the run succeeded. But Phase 5's criterion 5.7 watches for
`error_max_turns`, and if it appears, this is the first thing to check: the fix is likely raising the
default rather than tightening the prompt.

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
