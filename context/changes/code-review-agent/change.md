---
change_id: code-review-agent
title: Code review agent on Claude Agent SDK as a standalone packages/ package
status: impl_reviewed
created: 2026-08-07
updated: 2026-08-07
---

## Notes

Build the first version of a scripted code-review agent as an independent TypeScript package at
`packages/code-reviewer/`, driven by the Claude Agent SDK. It takes a unified diff, reviews it
against NextSlope's own conventions, and emits a schema-validated JSON verdict plus a readable
markdown summary. Runs locally in this change; a follow-up change (`ci-cd-code-review`) puts it on
GitHub Actions for every PR to `main`.

Linear: 10X-18 (this change) blocks-relationship with 10X-19 (`ci-cd-code-review`). Both issue
descriptions carry the full worked-out scope and are the authoritative source — do not re-derive
them from scratch during research/planning; confirm and refine instead.

Vendor decision (settled, 2026-08-07 — reverse only with a reason):
- Claude Agent SDK (`@anthropic-ai/claude-agent-sdk` 0.3.224, bundling Claude Code 2.1.224), chosen
  over Cursor SDK. Decisive factor: native structured outputs via
  `outputFormat: { type: 'json_schema', schema }`, where the SDK validates the result and
  re-prompts on mismatch, exposing validated data on `message.structured_output`. Cursor SDK has no
  equivalent — `result.result` is a plain string — which would have made a hand-rolled repair loop
  the load-bearing part of the JSON contract. Secondary factor: the SDK bundles a native
  per-platform binary, so there is no local-vs-cloud runtime question to settle before CI work.
- Accepted trade-off: cost moves to a per-token Anthropic bill (Cursor SDK billed into the Cursor
  plan), multiplied by PR count once CI is live. `maxTurns` is therefore a required guard.

Two traps that must survive into the implementation (both cost other people real time):
- Zod v4's `z.toJSONSchema()` defaults to draft-2020-12 and emits a `$schema` field; the SDK expects
  draft-07. On mismatch `outputFormat` is silently ignored — no error, `structured_output` simply
  absent. Always pass `{ target: "draft-07" }`. Refs: anthropics/claude-agent-sdk-typescript #105,
  #227.
- `allowedTools` does NOT restrict the toolset — it only auto-approves; unlisted tools still fall
  through to `permissionMode` / `canUseTool`. Read-only must be enforced via `disallowedTools`
  (a bare `"Write"` / `"Edit"` / `"Bash"` removes the tool from the model's context), or
  `permissionMode: 'plan'` / `'dontAsk'`.

Design constraints to honor:
- Keep the SDK import in exactly one module (`src/agent.ts`) that takes a prompt plus a schema and
  returns a validated object. Criteria, prompt assembly, rendering, exit codes, and the later CI
  integration stay vendor-agnostic, so swapping providers costs one file. This matters because the
  course lesson puts five SDKs on the table and the comparison may well be revisited.
- Set `settingSources` explicitly (`[]`, or `["project"]` for team-shared only). This repo already
  has `.claude/settings.local.json`, and a review run must not depend on anyone's local config.
- The package must stay out of the Gradle build: not in `settings.gradle`, not under `src/`.
  `AGENTS.md`'s "no JS build step / Node tier" hard rule governs the *application*; this change
  needs an explicit carve-out naming `packages/` as a tooling zone. Without it the rule collides
  with this package on every future review — including the reviews this agent itself performs.

Verification standard: the fixture diff carries planted defects, each mapped to one review
criterion (editing an already-applied Flyway migration, IDOR via profile lookup without an
ownership check, `ddl-auto=update`, Postgres-only DDL that won't parse on H2). Compare findings
against that expected list, then do a deliberate break — remove one planted defect and confirm it
disappears from the report. Otherwise a green run doesn't prove the agent reads the diff rather
than inferring from repo context. Separately assert `structured_output` is actually present; its
absence is the signature of the draft-07 trap, not an ordinary failure.

Review criteria should be derived from this repo's existing hard rules rather than invented:
`AGENTS.md` and `context/foundation/lessons.md` are the sources.
