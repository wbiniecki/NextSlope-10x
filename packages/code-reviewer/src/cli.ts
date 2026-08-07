/**
 * The CLI: everything between the vendor boundary and the user. Parses arguments, reads the diff,
 * drives one review session, writes both artifacts, and exits with the contract code.
 *
 * The exit codes and `review.json` are a cross-change contract consumed by Linear 10X-19
 * (`ci-cd-code-review`), which is why the mapping lives in an exported pure function with tests
 * rather than in scattered `process.exit` calls:
 *
 * | Code | Meaning                                                                      |
 * |------|------------------------------------------------------------------------------|
 * | 0    | Run completed, no blocking findings                                          |
 * | 1    | Invalid invocation or input, or a startup/auth failure before a session ran   |
 * | 2    | A session started but produced no usable result                              |
 * | 3    | Run completed, but findings at or above `--fail-on` blocked it               |
 *
 * `run()` takes its side effects as injected dependencies so the whole surface — argument
 * validation, input rejection, exit-code mapping, artifact contents — is testable without a
 * network call or a real session.
 */
import { randomBytes } from "node:crypto";
import { mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import {
	DEFAULT_MAX_BUDGET_USD,
	DEFAULT_MAX_TURNS,
	DEFAULT_MODEL,
	runStructuredSession,
	type AgentFailureKind,
	type StructuredSessionRequest,
	type StructuredSessionResult,
} from "./agent.ts";
import { buildReviewPrompt } from "./prompt.ts";
import { renderReport } from "./render.ts";
import {
	reviewReportSchema,
	verdictJsonSchema,
	verdictSchema,
	type ReviewReport,
	type Severity,
	type Verdict,
} from "./schema.ts";
import { DEFAULT_FAIL_ON, computeGate, isSeverity } from "./verdict.ts";

/**
 * Rejected before prompt assembly or session startup. A diff this large is a runaway input, and
 * `maxTurns` does not cap the cost of a single oversized request — only refusing to send it does.
 */
export const MAX_DIFF_BYTES = 200_000;

export const REVIEW_JSON_FILENAME = "review.json";
export const REVIEW_MARKDOWN_FILENAME = "review.md";

export const EXIT_OK = 0;
export const EXIT_INVALID_INPUT = 1;
export const EXIT_NO_USABLE_RESULT = 2;
export const EXIT_BLOCKED = 3;

const CRITERIA_PATH = fileURLToPath(new URL("../prompts/criteria.md", import.meta.url));
/**
 * The only tree the session may read, deliberately the application sources rather than the repo
 * root. Findings are free text that lands verbatim in `review.md`, which 10X-19 will post as a PR
 * comment, so a session that can read `.env`, `.claude/settings.local.json`, `data/`, or `.neon` is
 * one prompt injection away from publishing them. `src/` still covers everything the criteria cite:
 * migrations and properties under `main/resources`, production code under `main/java`, and the e2e
 * suite under `e2eTest/`.
 *
 * Consequence worth knowing: diff paths are repo-relative (`src/main/java/...`) while reads resolve
 * against this root, so a cross-reference read needs the path without the `src/` prefix. Reads are
 * optional enrichment for the rare ambiguous case — every criterion is answerable from the diff — so
 * a mistaken path degrades to "no extra evidence" rather than a failed review.
 */
const REVIEW_ROOT = fileURLToPath(new URL("../../../src/", import.meta.url));

export const USAGE = [
	"Usage: npm run review -- --diff-file <path> [options]",
	"",
	"Options:",
	"  --diff-file <path>       Unified diff to review (required)",
	`  --model <id>             Model to review with (default: ${DEFAULT_MODEL})`,
	`  --max-budget-usd <n>     Per-run cost ceiling (default: ${DEFAULT_MAX_BUDGET_USD})`,
	`  --fail-on <severity>     low | medium | high | critical (default: ${DEFAULT_FAIL_ON})`,
	`  --out <dir>              Where to write ${REVIEW_JSON_FILENAME} and ${REVIEW_MARKDOWN_FILENAME} (default: cwd)`,
	"  --verbose                Log resolved model, turns, and per-run cost",
	"  --help                   Show this message",
].join("\n");

export type CliOptions = {
	diffFile: string;
	model: string;
	maxBudgetUsd: number;
	failOn: Severity;
	outDir: string;
	verbose: boolean;
	help: boolean;
};

export type ParseResult = { ok: true; value: CliOptions } | { ok: false; error: string };

/** The one dependency that costs money, injected so tests can supply a canned result. */
export type SessionRunner = <T>(
	request: StructuredSessionRequest<T>,
) => Promise<StructuredSessionResult<T>>;

export type CliDeps = {
	cwd: string;
	runSession: SessionRunner;
	log: (line: string) => void;
	logError: (line: string) => void;
};

/**
 * A startup failure means no session ever ran, so it is an invocation-level problem like a bad
 * flag; everything else means a session ran and came back empty-handed. The split is what lets
 * 10X-19 tell "this repo is misconfigured" apart from "this review needs a retry".
 */
export function exitCodeForFailure(kind: AgentFailureKind): number {
	return kind === "startup_failure" ? EXIT_INVALID_INPUT : EXIT_NO_USABLE_RESULT;
}

export function parseArgs(argv: string[], cwd: string): ParseResult {
	const options: CliOptions = {
		diffFile: "",
		model: DEFAULT_MODEL,
		maxBudgetUsd: DEFAULT_MAX_BUDGET_USD,
		failOn: DEFAULT_FAIL_ON,
		outDir: cwd,
		verbose: false,
		help: false,
	};

	for (let index = 0; index < argv.length; index += 1) {
		const argument = argv[index] as string;
		const separator = argument.indexOf("=");
		const flag = separator === -1 ? argument : argument.slice(0, separator);
		const inlineValue = separator === -1 ? undefined : argument.slice(separator + 1);

		// `--flag value` and `--flag=value` are both common enough that supporting only one of them
		// turns into a confusing failure at the worst moment, inside a CI step.
		const takeValue = (): string | undefined => {
			if (inlineValue !== undefined) {
				return inlineValue;
			}
			index += 1;
			return argv[index];
		};

		switch (flag) {
			case "--help":
			case "-h":
				options.help = true;
				break;
			case "--verbose":
				options.verbose = true;
				break;
			case "--diff-file": {
				const value = takeValue();
				if (value === undefined) return { ok: false, error: "--diff-file requires a path" };
				options.diffFile = value;
				break;
			}
			case "--model": {
				const value = takeValue();
				if (value === undefined) return { ok: false, error: "--model requires a model id" };
				options.model = value;
				break;
			}
			case "--max-budget-usd": {
				const value = takeValue();
				if (value === undefined) return { ok: false, error: "--max-budget-usd requires a number" };
				const parsed = Number(value);
				if (!Number.isFinite(parsed) || parsed <= 0) {
					return { ok: false, error: `--max-budget-usd must be a positive number, got "${value}"` };
				}
				options.maxBudgetUsd = parsed;
				break;
			}
			case "--fail-on": {
				const value = takeValue();
				if (value === undefined) return { ok: false, error: "--fail-on requires a severity" };
				if (!isSeverity(value)) {
					return {
						ok: false,
						error: `--fail-on must be one of low, medium, high, critical, got "${value}"`,
					};
				}
				options.failOn = value;
				break;
			}
			case "--out": {
				const value = takeValue();
				if (value === undefined) return { ok: false, error: "--out requires a directory" };
				options.outDir = resolve(cwd, value);
				break;
			}
			default:
				return { ok: false, error: `Unknown argument "${argument}"` };
		}
	}

	if (!options.help && options.diffFile === "") {
		return { ok: false, error: "--diff-file is required" };
	}

	return { ok: true, value: options };
}

type DiffReadResult = { ok: true; text: string; bytes: number } | { ok: false; error: string };

function readDiff(path: string): DiffReadResult {
	let bytes: number;
	try {
		const stats = statSync(path);
		if (!stats.isFile()) {
			return { ok: false, error: `--diff-file is not a regular file: ${path}` };
		}
		bytes = stats.size;
	} catch (error) {
		return { ok: false, error: `Cannot read --diff-file ${path}: ${describe(error)}` };
	}

	// Checked from the stat, before the bytes are ever pulled into memory.
	if (bytes > MAX_DIFF_BYTES) {
		return {
			ok: false,
			error: `Diff is ${bytes} bytes, over the ${MAX_DIFF_BYTES}-byte limit. Review it in smaller pieces.`,
		};
	}

	try {
		return { ok: true, text: readFileSync(path, "utf8"), bytes };
	} catch (error) {
		return { ok: false, error: `Cannot read --diff-file ${path}: ${describe(error)}` };
	}
}

export async function run(argv: string[], deps: CliDeps): Promise<number> {
	const parsed = parseArgs(argv, deps.cwd);
	if (!parsed.ok) {
		deps.logError(parsed.error);
		deps.logError(USAGE);
		return EXIT_INVALID_INPUT;
	}

	const options = parsed.value;
	if (options.help) {
		deps.log(USAGE);
		return EXIT_OK;
	}

	const diffPath = resolve(deps.cwd, options.diffFile);
	const diff = readDiff(diffPath);
	if (!diff.ok) {
		deps.logError(diff.error);
		return EXIT_INVALID_INPUT;
	}

	let criteriaMarkdown: string;
	try {
		criteriaMarkdown = readFileSync(CRITERIA_PATH, "utf8");
	} catch (error) {
		deps.logError(`Cannot read the criteria document at ${CRITERIA_PATH}: ${describe(error)}`);
		return EXIT_INVALID_INPUT;
	}

	if (options.verbose) {
		deps.log(`diff: ${diffPath} (${diff.bytes} bytes, limit ${MAX_DIFF_BYTES})`);
		deps.log(
			`configured model: ${options.model}, budget: $${options.maxBudgetUsd.toFixed(2)}, max turns: ${DEFAULT_MAX_TURNS}`,
		);
		deps.log(`fail-on: ${options.failOn}`);
	}

	const result = await deps.runSession<Verdict>({
		// Fresh per run: a delimiter the diff's author could predict is not a boundary, since a diff
		// carrying the closing marker would smuggle its own text into instruction position.
		prompt: buildReviewPrompt({
			criteriaMarkdown,
			diffText: diff.text,
			nonce: randomBytes(12).toString("hex"),
		}),
		jsonSchema: verdictJsonSchema as Record<string, unknown>,
		validate: validateVerdict,
		model: options.model,
		maxBudgetUsd: options.maxBudgetUsd,
		cwd: REVIEW_ROOT,
	});

	if (!result.ok) {
		deps.logError(`Review did not complete (${result.kind}): ${result.diagnostic}`);
		return exitCodeForFailure(result.kind);
	}

	if (options.verbose) {
		deps.log(`resolved model: ${result.session.resolvedModel} (${result.session.claudeCodeVersion})`);
		deps.log(`turns: ${result.numTurns}, total cost: $${result.totalCostUsd.toFixed(4)}`);
		// `usage` covers only the main agent loop; `modelUsage` covers the whole pipeline, so it is
		// the field to log when the question is what the run actually cost.
		deps.log(`modelUsage: ${JSON.stringify(result.modelUsage)}`);
	}

	const gate = computeGate(result.value, options.failOn);
	const candidate: ReviewReport = { ...result.value, ...gate };
	const report = reviewReportSchema.safeParse(candidate);
	if (!report.success) {
		// Unreachable short of a bug in this file: the verdict is already validated and the gate
		// fields are typed. Surfacing it as a failed run beats writing an invalid `review.json`.
		deps.logError(`Assembled report failed its own schema: ${formatIssues(report.error)}`);
		return EXIT_NO_USABLE_RESULT;
	}

	const jsonPath = join(options.outDir, REVIEW_JSON_FILENAME);
	const markdownPath = join(options.outDir, REVIEW_MARKDOWN_FILENAME);
	try {
		mkdirSync(options.outDir, { recursive: true });
		writeFileSync(jsonPath, `${JSON.stringify(report.data, null, 2)}\n`, "utf8");
		writeFileSync(
			markdownPath,
			renderReport(report.data, { failOn: options.failOn }),
			"utf8",
		);
	} catch (error) {
		deps.logError(`Cannot write review artifacts to ${options.outDir}: ${describe(error)}`);
		return EXIT_NO_USABLE_RESULT;
	}

	deps.log(`Wrote ${jsonPath} and ${markdownPath}`);

	if (report.data.passed) {
		deps.log(`Passed — no findings at or above ${options.failOn}.`);
		return EXIT_OK;
	}

	deps.logError(`Blocked — ${report.data.reasons.length} finding(s) at or above ${options.failOn}:`);
	for (const reason of report.data.reasons) {
		deps.logError(`  - ${reason}`);
	}
	return EXIT_BLOCKED;
}

function validateVerdict(raw: unknown): { ok: true; value: Verdict } | { ok: false; error: string } {
	const parsed = verdictSchema.safeParse(raw);
	return parsed.success
		? { ok: true, value: parsed.data }
		: { ok: false, error: formatIssues(parsed.error) };
}

function formatIssues(error: { issues: { path: PropertyKey[]; message: string }[] }): string {
	return error.issues
		.map((issue) => `${issue.path.map(String).join(".") || "<root>"}: ${issue.message}`)
		.join("; ");
}

function describe(error: unknown): string {
	return error instanceof Error ? error.message : String(error);
}

const invokedDirectly =
	process.argv[1] !== undefined &&
	pathToFileURL(resolve(process.argv[1])).href === import.meta.url;

if (invokedDirectly) {
	process.exitCode = await run(process.argv.slice(2), {
		cwd: process.cwd(),
		runSession: runStructuredSession,
		log: (line) => console.log(line),
		logError: (line) => console.error(line),
	});
}
