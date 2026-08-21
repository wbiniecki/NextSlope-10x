# Opportunity Map

## Context

- **Project / context**: NextSlope — solo-developer Spring Boot + Thymeleaf MVP, built through an
  AI-assisted 10x workflow (skills, Linear issues, plan Progress rows, multi-tier CI). Focus of this
  map is the **dev/AI workflow itself**, not the ski-resort product.
- **Data constraint**: mock / local / read-only / non-sensitive. Everything below reads the repo's own
  history and artifacts; no company or customer data, no access-control work needed up front.
- **Date**: 2026-08-06

### Evidence base

Signals were drafted from the repository and confirmed/corrected by the user:

- 12 archived changes in `context/archive/`, 34 merged PRs, roadmap S-01 … S-07 all done.
- **41 review artifacts** across those changes (`reviews/plan-review.md`, `reviews/impl-review.md`,
  several per-phase, one second round). Findings carry stable IDs (`F1`…`F5`).
- Fix commits name which findings were applied — e.g. `apply impl-review fixes (F1, F2, F4)`,
  `(F1, F2, F5)`. **The gaps in those sequences are findings raised and not acted on.**
- `context/foundation/lessons.md` holds **3 entries after 12 changes**.
- `.cursor/rules/linear-sync.mdc` plus a `lessons.md` entry both exist to remind the agent to keep
  Linear honest — a reminder that exists because the state drifts.

Signals considered and dropped by the user: skill/rule copies forking across repos
(`10x-tdd-2`, `10x-e2e-2`, `10x-e2e-java-2` all differ from their global originals); the per-change
archive/bookkeeping PR; PRD-vs-code drift found by the Module 4 architect report.

## Map

| Signal | Existing / default response | Thin complement | First useful version | Data risk | Direction if valuable |
|---|---|---|---|---|---|
| Change state lives in git, Linear and docs; agreement is manual | Linear↔GitHub auto-link by issue ID + PR-triggered transitions (unused); PR state authoritative | Check the **docs layer only** — roadmap Status vs. folder location vs. `change.md` stamp — against merged PRs | Read-only script: one row per change-id with doc/folder/Linear/PR state + mismatch flag | local, read-only | **Wait** — enable Linear's native automation first; residual is bookkeeping, not defect risk |
| Review findings recur instead of becoming rules (3 lessons / 12 changes) | `/10x-lesson` + `lessons.md` already exist; AGENTS.md for hard rules; CI for executable gates | Mine the 41 review artifacts for recurring finding classes; propose lessons at archive time | One-off clustering report over `context/archive/*/reviews/` | local, read-only | Internal tool → **review / CI gate** |
| Reviews miss real problems, are noisy, and an approval carries no trust | `/10x-impl-review` + own diff read; multi-tier CI (test, pitest, Playwright) is the only mechanical gate; Bugbot unused on PRs | A **scope contract**: what a review is responsible for catching, drawn from PRD guardrails, lessons, test-plan risks | Back-test the corpus: accepted vs. dropped findings, and whether late-surfacing problems were diff-visible | local, read-only | Internal tool → **review / CI gate** |

### Diagnosis behind signal 3

"Misses things", "too noisy", and "I can't trust an approval" are not three problems — they are what a
review with **no declared scope** always looks like. Nothing states what the review must catch, so
nothing can be called a miss, everything is fair game to mention, and an approval asserts nothing
falsifiable. The fix is a scope, not a better prompt.

## Recommended First Candidate

```text
Candidate:
Review miss back-test

Reads:
context/archive/*/reviews/*.md (41 artifacts, findings already ID'd F1..F5), the fix commits naming
which findings were applied, and the diffs those reviews were looking at

Returns:
A short markdown report answering three questions — what fraction of raised findings were actually
acted on (the noise rate, already recorded in the F-gaps); what problems surfaced after review
passed, and whether each was visible in the diff or only at runtime; and which finding classes
repeat across changes

Does not do:
No new review tool, no agent, no CI wiring, no prompt rewrite, no scope contract yet — the report
is the deliverable and it is meant to be thrown away

Data risk:
Local, read-only, own repo. Nothing sensitive, no access control needed.

Direction if it proves valuable:
Internal tool → review / CI gate.
```

## Why This Candidate

- **It answers two signals in one pass.** The clustering that shows which findings repeat is the same
  pass that shows which ones were trusted.
- **It needs no new plumbing.** Every input already exists in the repo; the accepted-vs-raised ratio is
  recorded in commit messages, so it is measured rather than remembered.
- **It guards against building the wrong tool.** The diff-visible vs. runtime-only split separates
  *accidental* friction — where a scoped checklist genuinely helps — from *essential* friction, where no
  diff review could ever have caught the problem and the answer is a test. Chasing the wrong half buys
  a tool that cannot work.
- **It replaces nothing.** Linear, GitHub, and CI keep their responsibilities; this only reads.

**Why not the status signal:** Linear already ships the GitHub automation that covers the git↔Linear
half for free, and the branch naming (`feature/10x-6-…`) is set up to use it. Turn that on before
building. What remains is the docs layer — annoying, but low risk and partly intentional, since the
archive stamp is *supposed* to lag until the change closes.

**Why not the lessons signal alone:** same corpus, and "which lesson to encode" is unanswerable until
you know which findings mattered. The back-test orders them.

## Next Direction If Valuable

**Internal tool → review / CI gate.** If the back-test shows findings cluster and misses are largely
diff-visible, the recurring classes become a scoped review checklist stating what a review is
responsible for catching; the top one or two get promoted to executable checks (a test, an ArchUnit
rule, a lint ban). That is also what would finally make `lessons.md` accumulate — a lesson that becomes
a gate does not depend on anyone remembering to write it down.

If instead the misses turn out to be mostly runtime-only, the direction changes: the gap belongs in the
test tiers (`test-plan.md`, pitest, Playwright), not in review, and no review tool should be built.

**Chosen next step:** `/10x-mom-test` → `/10x-shape`. Before either, the cheapest evidence is a
conversation with whoever lives with this friction — here, that is largely you, so the honest version of
the Mom Test is interrogating your own past behavior on the last few changes rather than your opinion
about what a better reviewer would do.
