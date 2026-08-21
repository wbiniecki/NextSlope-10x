---
title: "Module 4 Architectural Report — 10xArchitect track"
created: 2026-08-05
type: summary-report
repository: NextSlope
branch: main
git_commit: 25dfbc9a2b49e86eff331e92651114c37fa7535d  # report itself authored at this HEAD; context/ artifacts untracked
inputs:
  - layer: L2
    artifact: stirling-pdf/context/map/repo-map.md
    repository: stirling-pdf
    pinned_state: "main @ 5bbe2607 (structure); activity window Aug 2025 – Aug 2026"
    created: "not recorded in the artifact (predates L3, 2026-08-03)"
  - layer: L3
    artifact: stirling-pdf/context/changes/compress-flow-deep-dive/research.md
    repository: stirling-pdf
    branch: main
    git_commit: 5bbe26072fa89030c3f6fcdc188fe7581a2b4e31
    created: 2026-08-03T21:58:23+02:00
  - layer: L4
    artifact: stirling-pdf/context/changes/compress-refactor-opportunities/plan.md
    repository: stirling-pdf
    pinned_state: "evidence base at 5bbe26072, re-confirmed against the working tree during planning; censuses dated 2026-08-04"
    created: 2026-08-04
  - layer: L5
    artifact: NextSlope/context/domain/01-domain-distillation.md + 02-invariant-aggregate-refactor.md + 03-anti-corruption-layer.md
    repository: NextSlope
    branch: main
    git_commit: 25dfbc9a2b49e86eff331e92651114c37fa7535d  # HEAD on creation date; the notes themselves are untracked
    created: 2026-08-04
---

# Module 4 Architectural Report

Every claim below is sourced from the four listed artifacts; nothing is re-derived from memory of the code.

## 1. Projects covered

| Repo | Stack | Scale (from the artifacts) | Artifacts |
|---|---|---|---|
| **[stirling-pdf](https://github.com/Stirling-Tools/stirling-pdf)** (fork of Stirling-Tools/Stirling-PDF), `main @ 5bbe2607` | Monorepo: Java/Spring Boot backend (4 Gradle modules = product tiers), React/TypeScript frontend with an `@app/*` build-flavor cascade, Python FastAPI AI engine, Rust/Tauri desktop shell, Docker infra | ~1,220 backend Java files across 4 modules (574+297+183+168); 8,583 `@Test` in 806 files; ~1,700 commits in the 12-month analysis window; 72 `*Controller.java` in `app/core` | **L2, L3, L4** |
| **[NextSlope](https://github.com/wbiniecki/NextSlope-10x)**, `main @ 25dfbc9` | Spring Boot 4 + Thymeleaf server-rendered app (Bootstrap 5 + HTMX via CDN, single tier, no JS build), H2 locally / Neon Postgres in prod, Flyway V1–V5 | Solo-developer 3-week MVP; 6 domain packages under `com.nextslope`; 150-row CSV resort seed | **L5** |

## 2. Project map (L2 — stirling-pdf)

1. **Hard module boundaries hold; the pain is inside them.** Zero cross-module violations (verified from bytecode), but `app/proprietary` hides a **20-package cycle** fusing policy + SSO + audit + licensing + billing, with one edge masked by `@Lazy` so it surfaces as runtime initialization bugs. Two boundary directions (proprietary→core, saas→core) have no ArchUnit rule — held by luck.
2. **The frontend flavor cascade silently changes what code runs.** 317 `core/` import sites (13% of core files) resolve to a different implementation per build flavor; 39% of those are behavioural (apiClient, auth, entitlement). A fix verified on one flavor is unverified on the other three, and no local tool says which case you're in.
3. **Local centers / entry points:** `useToolOperation.ts` (the tool-execution spine, 58 direct dependents), `FileContext.tsx` (file-state hub, fan-in 64), `ApplicationProperties.java` (fan-in 136, one corner of the settings triangle), and the fat PDFBox controllers — 52 of 72 `app/core` controllers call PDFBox directly in the HTTP layer, with 2 interfaces in the whole module.
4. **Key unknowns:** no dependency graph exists for `src-tauri` (Rust), Docker, CI workflows, E2E suites, or any cross-language edge — those areas are *unknown*, not clean. The Java→TS/Python contract chain is CI-enforced but drift is invisible locally (`SwaggerDoc.json` is gitignored).

## 3. Feature analysis (L3 — Compress flow, stirling-pdf)

**Why this flow.** Compress was chosen because it crosses three of the map's risk zones at once: zone 2 (flavor-shadowed frontend), zone 4 (fat untested controllers), zone 6 (contract-chain local blindness).

**Feature overview.** Input comes from the user's FileContext selection; `useCompressOperation` maps UI params against the *generated* `toolApiTypes.ts` contract and hands off to the shared `useToolOperation` orchestrator, which posts multipart `POST /api/v1/misc/compress-pdf` through a flavor-resolved `apiClient`. The 1,429-line `CompressController` (largest `*Controller.java` in `app/core`) loops three engines by level — Ghostscript (≥6), qpdf (always if enabled), PDFBox image recompression (≥4 when gs didn't run) — with a safety net returning the original if output grows. The blob response re-enters `FileContext` as a new version of the same file (`consumeFiles` → reducer → IndexedDB), with undo support. State changes live entirely in FileContext on the client; the backend is stateless per request.

**Technical debt (top risks):**

1. **Unvalidated contract, ast-grep-confirmed.** The documented 1–9 compression level is never enforced server-side — no `@Min`/`@Max`, no `@Valid`, only a null check; `optimizeLevel=10` returns 200 with an untouched file. Verification also showed the `@Schema` requiredness lies are laundered into **three divergent generated contracts** (TS, Python, Java runtime each disagree), and Ghostscript's `case 10` is dead code (confirmed with a `switch_label` AST rule).
2. **Test gap on the actual runtime path.** The `singleFile` orchestrator branch compress rides (36 of 42 tools) has zero vitest; no HTTP-layer test exists for the endpoint (all Java tests invoke the method directly — the ast-grep MockMvc census also corrected the map's stale "zero MockMvc" claim); cancellation exists in tests only as `vi.fn()` stubs — worse than absent, because the surface looks covered.
3. **Blast radius and fragile seams.** A compress change touches up to 17 coupling categories held by one CI-enforced generation chain, and **11 `@app/*` seams on this exact path resolve differently per flavor** (measured structurally; the worst is desktop's per-request local↔SaaS routing). Three of the 11 were missed on a first manual read — the seams are invisible at the import site.

## 4. Refactoring plan (L4 — stirling-pdf)

**What we refactor.** The quick-win pair, not the top-ranked item: **C4** — carry the first failure's error as `cause` across the `processFiles` boundary so the backend's RFC-7807 ProblemDetail message reaches the UI for all 36 `defineSingleFileTool` tools; **C6 (freeze only)** — turn the prose-only FileContext/blob-URL ownership rule into ESLint bans with pinned offender exemption lists; plus an enabling `.gitignore` anchor fix. End state: real backend error messages instead of a filename list, and CI-proven non-regrowth of the storage/blob-URL leak.

**Deliberately NOT doing:** the #1-ranked CompressController extraction (deferred), deleting vestigial `useCreditCheck` (C5), draining any exemption entries, partial-failure behavior changes, doc-drift fixes, and all product-behavior stops (`normalize`, grayscale guard, out-of-range 400).

**Phases (one line each; verification auto/manual):**

1. C4 characterization tests pinning today's `processFiles` error paths — auto: `task frontend:test`/`check`; manual: mocked error shape reviewed against a real backend failure.
2. C4 fix: attach `cause`, normalize the Blob body at the catch site, flip the pinned assertions — auto: full frontend gate; manual: browser check that a corrupted PDF shows the ProblemDetail message; success and cancellation paths unchanged.
3. Anchor `.gitignore` `watchedFolders/` → `/watchedFolders/` — auto: `git check-ignore` probes + `git status` clean.
4. ESLint ban on direct `fileStorage` imports with frozen offender census — auto: lint/check green with zero source edits; manual: scratch violation fails lint, `@supabase/*` ban not clobbered.
5. ESLint `no-restricted-properties` ban on `URL.createObjectURL` (both spellings) — auto: lint/check green; manual: scratch violations fail lint.

## 5. Domain per DDD (L5 — NextSlope)

**Ubiquitous language (key terms):** *Recommendation* — exactly three ranked resorts or an explicit explanation, never padded; *hard filter* — region/novelty constraints dropping candidates before scoring; *truthful rationale* — the one-line "why this matched you" that must reflect actual matching logic; *difficulty mix* — easy/medium/hard percentages summing to 100; *novelty preference* — new-only vs revisit-okay. Biggest model-vs-code gaps: the PRD promises **percentage entry with sum-to-100 rejection**, but the code takes slope *counts* and derives the mix (D1); "difficulty preference" is three preset bands, not a free mix (D4); the Non-Goals presuppose a signup confirmation email that does not exist anywhere in the code (D7); only 2 of the broadly advertised resort facts actually affect ranking (D5).

**Invariant #1 and its aggregate.** *Resort difficulty-facts coherence*: `total_slopes` must equal the sum of the three slope counts from which the displayed and scored `DifficultyMix` derives. It belongs to the **`Resort` aggregate**, guarded by a new `SlopeCounts` value object — today it is enforced on only one of two write paths (admin derives; CSV seed/resync trusts the file verbatim), the entity has open setters, and the schema has zero CHECK constraints, while the mix is the engine's sole scoring input. The plan: all writes via `Resort.applySlopeCounts(SlopeCounts)`, fail-fast `IncoherentResortFactsException` rolling back the whole seed, and a V6 migration adding NOT NULL + CHECK backstops.

**Anti-Corruption Layer.** The leaking dependency is not a library but the **upstream resort-dataset contract** (CSV; commons-csv is just the carrier). It leaks through **four layers**: schema (V2 mirrors all 24 CSV columns, `BIGINT external_id`), domain (entity mirror, loader, service, a domain exception named after the foreign key), web (the admin form carries `externalId`), and UI (`detail.html` renders 22 raw columns, guessing units) — 10 `src/main` files plus the schema, against three documented promises that the source is a swappable detail. The ACL: `ResortFacts` + `DatasetKey` value objects, a narrow `ResortDatasetSource` port, and `CsvResortDatasetAdapter` as the sole owner of format knowledge.

## 6. Decisions that were mine

The AI produced the rankings and candidate lists, but the calls that shaped the module were mine. I chose Compress as the L3 target because it was the one flow crossing three map risk zones at once, and I insisted on the ast-grep verification pass after the first draft — which corrected real errors (seam count 8→11, the stale "zero MockMvc" claim) and is why the debt items in section 3 are measured, not remembered. Against the AI's #1-ranked recommendation (extracting the 1,429-line CompressController), I scoped the L4 plan down to the reversible quick-win pair, judging a large extraction too risky without the HTTP-layer tests that don't yet exist. On NextSlope I picked the difficulty-facts invariant over the engine invariants precisely because the engine is the *best*-enforced code in the repo — high value × weak enforcement mattered more than raw importance — and chose the dataset contract over Spring Security for the ACL because only the dataset had an explicit, thrice-documented replaceability promise the code breaks.
