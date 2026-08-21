# Mom Test Validation Plan

- **Date**: 2026-08-06
- **Input artifact**: `context/team/opportunity-map.md` (signal 3 + recommended candidate)
- **Validation mode**: self-interrogation (n=1 — the builder is the only user)

## Input Idea

From the opportunity map: reviews in the 10x workflow "miss real problems, are noisy, and an approval
carries no trust." The map diagnosed this as one problem — a review with **no declared scope** — and
proposed a *review miss back-test* over `context/archive/*/reviews/*.md` as the cheapest first step,
with the direction "internal tool → review / CI gate."

Narrowed by the user during this session: the felt pain is **trust** — *"I can't tell what a PASS
verdict is claiming, so I re-read the diff myself anyway."* Not noise, not misses, not lessons.

## Hypotheses

- **User/role**: solo developer running the 10x skill workflow (`/10x-plan-review`,
  `/10x-impl-review`) on NextSlope, with more slices coming — the DDD work in `context/domain/`
  (invariant/aggregate refactor, anti-corruption layer).
- **Friction**: a review verdict is not decision-grade. A PASS does not license shipping, so the
  review is added effort rather than substituted effort.
- **Current workaround**: read the whole diff personally after the review, regardless of verdict.
  Also: multi-tier CI (`test`, `pitest`, Playwright e2e) as the only mechanical gate, plus manual
  exploratory testing, which is what actually caught most escapes.
- **Proposed solution**: a scope contract — a written statement of what a review is responsible for
  catching, drawn from PRD guardrails, `lessons.md`, and `test-plan.md` risks.
- **Risky assumptions**:
  1. That re-reading after a PASS is *caused* by missing scope, rather than being a habit that would
     persist under any verdict.
  2. That re-reading finds things. If it reliably finds nothing, the review is already adequate and
     only its *claim* is missing — a paragraph, not a tool.
  3. That what you look for on re-read is expressible as a checklist at all, rather than being
     holistic judgement.
  4. That the corpus's problem classes carry over to the upcoming work. The next two changes are
     behavior-preserving refactors, a different risk profile from the feature slices that produced
     all 40 review artifacts.

### Evidence already present

Measured from the repo on 2026-08-06, not recalled:

| Fact | Value |
|---|---|
| Review artifacts | 40 across 12 archived changes |
| Findings carrying an explicit `Decision:` | 142 |
| FIXED / ACCEPTED | 104 / 11 — **81% acted on** |
| SKIPPED / DISMISSED | 19 / 1 — **14% rejected** |
| PENDING (never resolved) | 5, all in the final change, likely superseded |
| Reviews finding **nothing at all** | 3 of 40 |
| Defects that escaped a passing review | 4, across 34 merged PRs |
| Entries in `lessons.md` | 3 |

The four escapes, classified by whether a diff review could plausibly have caught them:

| Escape | Commit | Diff-visible? |
|---|---|---|
| htmx read `evt.detail.target` on an `outerHTML` swap | `e01e605` | Only with deep htmx semantics; found by manual testing |
| Session outlived a deleted user | `333574d` | No — emergent across auth and S-07, in no single diff |
| No "Back to resorts" link on the profile page | `d6018cb` | No — found by manual testing |
| Corrupted diacritics in the seed CSV | `ffb114d` | Yes, but data review, not code review |

## Critique

**Two of the three original complaints are contradicted by the record.**

*"Too noisy"* assumed a high rejection rate. The rate is 14%. The opportunity map also assumed this
had to be *inferred* from gaps in the `F1, F2, F4` sequences in fix-commit messages; it doesn't — every
finding carries an explicit `Decision:` field. And the skips are not noise: they are reasoned
deferrals with named causes (`mark-visited` F3 — *"known scope boundary, tracked for a later slice"*;
F4 — *"plan-approved decision; revisit at S-07"*). A back-test that scored SKIPPED as noise would have
manufactured the problem it was built to measure.

*"Misses real problems"* — four escapes across 12 changes, of which three were runtime-discovered or
emergent rather than diff-visible. That meets the opportunity map's own stated off-ramp: *"If the
misses turn out to be mostly runtime-only... no review tool should be built."* Also worth noting that
the loop already closed once — the "Back to resorts" escape became `lessons.md` entry #2, *"Plan
navigation to every new screen"*, which now fires at plan time, upstream of where it escaped. Three
lessons in 12 changes may be the right rate, not a shortfall.

**The surviving complaint is the honest one, and it is about a claim, not a capability.** "I can't
tell what a PASS is asserting" is unaffected by all the evidence above, because none of the evidence
tells you what an approval means. Note the shape of the cheapest possible fix: one paragraph at the
top of the review template stating what the review took responsibility for. That is not a tool, not
a CI gate, and not a corpus mining pass. If the paragraph works, the entire proposed direction is
unnecessary.

**Where the Mom Test bites hardest.** The complaint is a feeling about a process, reported by the one
person who also designed the process — the textbook conditions for a polite false positive with
yourself. The falsifiable core is the behavioral claim *"I re-read the diff myself anyway."* That is
testable against the last five changes, and it is what the guide below interrogates. If re-reading
finds nothing, the review is fine and only its contract is missing. If re-reading finds things, name
them — that list *is* the scope contract, already written, no mining required.

**One thing already argues against the tool direction.** `context/domain/03-anti-corruption-layer.md`
contains grep-based isolation proofs with deliberate `\b` anchoring to avoid a false positive on
`VisitedResort.builder()`. You are already reaching for mechanical, executable checks over review
prose when a property genuinely matters. That instinct is the end state the opportunity map describes
reaching *after* building a review tool — so consider whether the tool is a detour.

## Interview Guide

Self-interrogation, 20–30 minutes, against the record rather than memory. Open the artifacts as you
go; the point is to be contradicted by them where possible.

### 1. Context warm-up

1. Over the last five changes, at what point in a change did you actually open `impl-review.md` —
   before your own diff read, after it, or instead of it? Pick a specific change and reconstruct the
   order.
2. How long does reading one review take, and how long does your own diff read take? Rough is fine,
   but give numbers per change, not an average feeling.

### 2. Recent story — the trust claim

3. **Walk through the last time a review came back PASS.** What did you do in the next ten minutes?
   Follow-up: did you find anything on that read? If yes, what — and was it in the diff or only
   visible when you ran the app?
4. Three of your 40 reviews raised **zero findings** — `account-deletion/impl-review.md`,
   `admin-resort-management/plan-review.md`, `testing-recommender-correctness/impl-review-phase-1.md`.
   Take each one: did you ship on it, or go re-read? Do you remember any of them feeling like an
   all-clear? Follow-up: what would have had to be in that file for you to skip your own read?
5. `account-deletion` has the only second-round review in the corpus
   (`impl-review-phase-1-r2.md`). What made you send that one back? Follow-up: was it a finding you
   disagreed with, or a verdict you didn't believe?

### 3. Current workaround

6. When you re-read a diff after a PASS, **what are you actually looking for?** Try to name three
   specific things. Follow-up: could each one be stated as a check a reviewer could run, or does it
   only exist as judgement?
7. Of the 19 SKIPPED findings, pick two and reconstruct the skip. Was the reasoning already in the
   review, or did you supply it? Follow-up: would a review that pre-empted your reason have saved
   you anything, or is deciding the skip the cheap part?

### 4. Cost of the pain

8. Has re-reading after a PASS ever caught something that would have reached `main`? Name the change
   and the thing. Follow-up: if you can't name one across 12 changes, what is the re-read buying?
9. Which of the four escapes actually cost you something — time, a broken deploy, rework — versus
   was merely annoying to discover? Follow-up: for the ones that cost, would *any* diff review have
   caught them, or was manual testing always going to be the discovery route?

### 5. Existing alternatives

10. Bugbot is available and unused on your PRs. What stopped you from turning it on? Follow-up: is
    the objection about its findings, or about it having the same unstated-scope problem?
11. You already wrote grep-based isolation proofs in `03-anti-corruption-layer.md`. What made those
    worth writing when a review finding on the same property wasn't? Follow-up: does the answer
    generalize — is a check you can run always worth more to you than a finding you have to trust?

### 6. Decision signal

12. The next two changes are behavior-preserving refactors. For the aggregate refactor specifically,
    write down now — in one sentence — what you would need a review to take responsibility for.
    Follow-up: if a PASS asserted exactly that sentence, would you still re-read the diff? Answer
    honestly; that answer is the go/no-go.

### 7. Closing

13. Before the aggregate refactor's review, put that sentence at the top of the review template as a
    scope line. Run the change. Record whether you re-read the diff after the PASS. That single
    observation is worth more than the whole 41-artifact back-test.

## Survey

Not generated — this validation is n=1 by the user's choice, and a survey of one respondent produces
no signal a direct question doesn't. If the cohort or other repos running the 10x skills become a
population worth sampling, revisit; the screener would be *"in the last month, how many times did you
re-read a diff after your AI reviewer returned a passing verdict?"* with frequency bands, not an
opinion scale.

## Decision Criteria

Thresholds are stated against the record and the one live experiment (question 13), not against
respondent percentages.

- **Proceed** — build the scope contract, and mine the corpus for the classes that populate it — if
  re-reading after a PASS has caught **at least two nameable things across the last five changes**,
  *and* you can name what you were looking for in question 6. Both halves are required: things found
  without a nameable target means the value is in judgement, which a contract can't transfer.

- **Narrow scope** — write the scope paragraph into the review template and stop there — if
  re-reading reliably finds **nothing** but you keep doing it. The review is already adequate; only
  its claim is missing. Cost: one paragraph. Verify with question 13 on the aggregate refactor before
  writing a single line of tooling.

- **Do not build yet** if you cannot reconstruct a concrete re-read from the last five changes, or if
  question 13's honest answer is "I'd re-read anyway." A contract that doesn't change your behavior
  is a document nobody reads, and re-reading that survives any possible verdict is a habit, not a
  scope problem.

- **Try existing tool / process first** if the three things from question 6 are already expressible
  mechanically. Then the next move is an ArchUnit rule, a test, or a committed grep proof in the
  style you already wrote in `03-anti-corruption-layer.md` — plus turning Bugbot on for one PR to see
  whether a second opinion changes the trust calculus. Reviews should be the fallback for what can't
  be checked, not the primary gate.

- **Explicitly do not build** the review *tool* or CI gate on the strength of the miss evidence
  alone. Three of four escapes were runtime-only or emergent; by the opportunity map's own criterion
  that gap belongs in `test-plan.md` and the test tiers, not in review.
