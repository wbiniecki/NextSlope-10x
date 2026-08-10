# NextSlope code reviewer

Reviews a unified diff against NextSlope's own conventions and writes a machine-readable verdict
plus a human-readable summary. It scores six criteria derived from this repo's hard rules —
Flyway forward-only migrations, `ddl-auto=validate`, constructor injection, principal-scoped access
control, the E2E testing conventions, and tests that can actually fail — and blocks on findings at
or above a severity threshold you choose.

It is developer tooling. The package sits outside the Gradle build, is not in `settings.gradle`, and
is not part of the deployed artifact.

## Prerequisites

- Node `>=20.6.0`
- A credential the SDK can resolve. There is no `--api-key` flag: the SDK spawns a native Claude
  Code binary that finds its own, from `ANTHROPIC_API_KEY` if exported or from a Claude Code CLI
  login otherwise. Export the key for CI and for reproducibility; a CLI login is fine locally.
  `npm run smoke` prints `apiKeySource`, which tells you which one actually won.

## Install

```bash
cd packages/code-reviewer
npm install
```

The lockfile is committed. Never install with `--omit=optional` — the native binary ships as a
per-platform optional dependency, and skipping it breaks the run with a confusing resolution error.

If a first run misbehaves, `npm run smoke` makes the smallest possible session call and prints
`apiKeySource` and `claude_code_version`, which separates a bad credential from an unresolved binary
from a network failure.

## Review a diff

```bash
npm run review -- --diff-file ../../my-change.patch
```

| Flag | Default | Notes |
|---|---|---|
| `--diff-file <path>` | — | Required. A unified diff. Larger than 200,000 bytes is rejected before any session starts. |
| `--model <id>` | `claude-sonnet-5` | Pinned so cost is predictable. |
| `--max-budget-usd <n>` | `0.50` | Per-run ceiling; the session aborts rather than exceeding it. |
| `--fail-on <severity>` | `high` | One of `low`, `medium`, `high`, `critical`. Findings at or above it block. |
| `--out <dir>` | cwd | Where the two artifacts are written. |
| `--verbose` | off | Logs diff size, configured and resolved model, turn count, and per-run cost. |
| `--help` | — | Prints usage. |

The turn budget is fixed at 3. Reads (`Read`, `Glob`, `Grep`, all read-only) are available as
optional enrichment, but every criterion is answerable from the diff alone, which is what keeps the
budget workable. Those reads are scoped to `src/` — not the repo root — so a review session cannot
reach `.env`, `.claude/`, or the local database, whose contents would otherwise be one prompt
injection away from a published PR comment.

## Outputs

**`review.json`** — the model's report plus the deterministic gate:

```jsonc
{
  "criteria": [
    { "id": "flyway-forward-only", "applicable": true, "score": 3, "justification": "…" },
    { "id": "e2e-conventions", "applicable": false, "score": 10, "justification": "…" }
  ],
  "findings": [
    {
      "file": "src/main/resources/db/migration/V3__create_preference_profiles.sql",
      "line": 12,
      "criterionId": "flyway-forward-only",
      "severity": "critical",
      "message": "…"
    }
  ],
  "passed": false,
  "reasons": ["critical: flyway-forward-only at …:12 — …"]
}
```

Scores are diagnostic only — they never move `passed`. Read `passed` rather than re-deriving it
from `findings` and a threshold you would have to duplicate.

`applicable: false` means the diff contained nothing that criterion governs. Its integer `score` is
retained only because the schema requires one and carries no meaning — `review.md` renders an em
dash rather than a number for such an entry, so "not assessed" is never mistaken for "fully
compliant".

**`review.md`** — the same report as plain GitHub-flavored markdown: verdict line, blocking reasons,
a criterion score table, then findings grouped by file with line anchors. It is meant to be pasted
into a PR comment unedited.

Both filenames are gitignored repo-wide, not just here — `--out` defaults to the process working
directory, so a run started from the repo root writes them there.

## Exit codes

| Code | Meaning |
|---|---|
| `0` | Run completed, no blocking findings |
| `1` | Invalid invocation or input, or a startup/auth failure before a session ran |
| `2` | A session started but produced no usable result |
| `3` | Run completed, but findings at or above `--fail-on` blocked it |

`1` versus `2` is the useful split: `1` means the setup is wrong, `2` means the review is worth
retrying. Linear 10X-19 (`ci-cd-code-review`) consumes these codes and `review.json` from GitHub
Actions, so both are a cross-change contract.

## Verifying review quality

```bash
npm run verify
```

Runs three checked-in fixtures against `fixtures/expectations.json` and prints a pass/fail table
with per-fixture expected, observed, missing, and unexpected criterion ids, plus a total cost line.
Together they prove three different things: `sample-diff.patch` (six planted defects) proves defects
are found, `sample-diff-broken.patch` (the same diff with the IDOR defect removed) proves the report
comes from the diff rather than from repo context, and `clean-diff.patch` proves a compliant diff
produces nothing.

Three real API calls per pass, so run it deliberately. `npm test` and `npm run typecheck` are free
and make no network calls.

Do not apply the fixture patches to the working tree — they are review input, and they contain real
defects plus an inert prompt-injection attempt used as an adversarial control.

## Measuring review quality over time

```bash
npm run promptfoo          # the production agent, runs on the subscription credential
npm run promptfoo:compare  # adds a raw-model column; requires ANTHROPIC_API_KEY
```

Where `npm run verify` answers "did it find the planted defects", promptfoo answers "is the review
getting better or worse" as the prompt and model change. It scores the same three fixtures on three
assertions: the output validates against the schema emitted from `src/schema.ts`, the diagnostic
scores agree with each fixture's planted defects, and an `llm-rubric` check that justifications name
the actual defect rather than boilerplate. Results land in a local history browsable with
`npx promptfoo view`.

The suite runs the real agent through a custom provider, not a bare model call, so it measures what
actually reviews pull requests. Real API calls per row — a deliberate action, never wired into CI.
