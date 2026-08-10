/**
 * The review-quality harness. Runs every fixture in `fixtures/expectations.json` through the real
 * CLI — real API calls, real model — and diffs what it reported against what the fixture declares.
 * One API run per fixture per pass, so this is a deliberate action, not something to wire into a
 * file watcher.
 *
 * What it proves, taken together: the planted defects are actually found, removing one makes it
 * disappear (the deliberate break), a clean diff produces nothing (the false-positive control), and
 * a criterion the diff gives nothing to judge comes back not applicable rather than a flattering
 * 10/10. Any one of those alone is satisfiable by a degenerate reviewer.
 *
 * Matching is on criterion ids, severities, and diff line ranges — never on prose. A probabilistic
 * reviewer words the same finding differently every run, so prose matching would fail for reasons
 * that have nothing to do with review quality. The line a finding anchors to, by contrast, is fixed
 * by the static patch.
 *
 * Pass `--artifacts-dir <path>` to retain each run's `review.json`, `review.md`, and `run.log`
 * instead of discarding the temporary output directory. That changes evidence retention only: same
 * runs, same prompt, no extra model call.
 */
import { spawnSync } from "node:child_process";
import {
	closeSync,
	existsSync,
	mkdirSync,
	mkdtempSync,
	openSync,
	readdirSync,
	readFileSync,
	rmSync,
	statSync,
	writeSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import { z } from "zod";

import {
	CRITERION_IDS,
	SEVERITIES,
	reviewReportSchema,
	type CriterionId,
	type ReviewReport,
} from "../src/schema.ts";

const PACKAGE_ROOT = fileURLToPath(new URL("..", import.meta.url));
const FIXTURES_DIR = join(PACKAGE_ROOT, "fixtures");
const EXPECTATIONS_PATH = join(FIXTURES_DIR, "expectations.json");
const CLI_PATH = join(PACKAGE_ROOT, "src", "cli.ts");

/**
 * `0` (clean) and `3` (blocked) both mean the review ran and produced a report — which of the two
 * appears is itself part of what the expectations describe. `1` and `2` mean no report exists to
 * check, so they are harness failures rather than expectation failures.
 */
const COMPLETED_EXIT_CODES = new Set([0, 3]);

/** Inclusive `[start, end]` post-change line numbers, as they appear in the fixture's hunks. */
const lineRangeSchema = z
	.tuple([z.int().min(1), z.int().min(1)])
	.refine((range) => range[0] <= range[1], { error: "lineRange must be [start, end], start <= end" });

/**
 * `file` is required, not optional. A line number means nothing without one: on a multi-file patch,
 * a finding at line 35 of a properties file would otherwise satisfy an expectation written about
 * line 35 of a Java test, and the fixture would pass on a reviewer that anchored to the wrong file
 * entirely.
 */
const expectedFindingSchema = z.strictObject({
	criterionId: z.enum(CRITERION_IDS),
	severity: z.enum(SEVERITIES),
	file: z.string().min(1),
	lineRange: lineRangeSchema,
	note: z.string().optional(),
});

const forbiddenFindingRangeSchema = z.strictObject({
	criterionId: z.enum(CRITERION_IDS),
	file: z.string().min(1),
	lineRange: lineRangeSchema,
	note: z.string().optional(),
});

/**
 * `matchFindings` consumes greedily in declaration order, which is exact only while two entries for
 * the same criterion and file do not overlap — an invariant its docblock states and the fixture
 * currently honors by hand. Enforced here so the next entry someone adds cannot quietly break it:
 * overlap cannot produce a false pass (consumption is one-to-one regardless), but it can produce a
 * spurious failure, and this harness only fails after four paid runs.
 */
const expectedFindingsSchema = z.array(expectedFindingSchema).refine(
	(findings) =>
		findings.every((finding, index) =>
			findings.slice(index + 1).every(
				(other) =>
					other.criterionId !== finding.criterionId ||
					other.file !== finding.file ||
					other.lineRange[1] < finding.lineRange[0] ||
					other.lineRange[0] > finding.lineRange[1],
			),
		),
	{ error: "expectedFindings ranges must not overlap for the same criterion and file" },
);

/**
 * `strictObject`, not a plain cast. Every field below the required four is optional and read through
 * `?? []`, so a mistyped key — `expectedFinding`, `forbiddenFindingRange`, or one nested a level too
 * deep — would silently disable that assertion and let the fixture pass on criterion ids alone,
 * which is the weaker gate the structured expectations exist to replace. These keys are spelled once
 * here and re-spelled by hand in `promptfoo/tests.yaml`, so the typo is a question of when.
 */
const fixtureExpectationSchema = z.strictObject({
	// The name becomes a path segment under `--artifacts-dir`, so it is constrained rather than
	// trusted: `../` would escape the artifacts directory, and the uniqueness check below matters
	// more — two fixtures sharing a name would silently overwrite each other's retained evidence
	// while the run still reported a pass.
	name: z
		.string()
		.regex(/^[a-z0-9-]+$/, { error: "name must be lowercase letters, digits, and hyphens" }),
	// Constrained for the same reason `name` is: it becomes a path segment, at
	// `join(FIXTURES_DIR, fixture.patch)`. Leaving one of the two unvalidated invites a reader to
	// assume both are.
	patch: z
		.string()
		.regex(/^[a-z0-9-]+\.patch$/, { error: "patch must be a lowercase-hyphenated .patch filename" }),
	description: z.string().optional(),
	expectedCriteria: z.array(z.enum(CRITERION_IDS)),
	forbiddenCriteria: z.array(z.enum(CRITERION_IDS)),
	/** Optional. A criterion listed here must come back `applicable: false`. */
	expectedNotApplicable: z.array(z.enum(CRITERION_IDS)).optional(),
	/** Optional. Each entry must match exactly one finding, one-to-one. */
	expectedFindings: expectedFindingsSchema.optional(),
	/** Optional. No finding for the named criterion may fall inside the range. */
	forbiddenFindingRanges: z.array(forbiddenFindingRangeSchema).optional(),
});

const expectationsFileSchema = z.strictObject({
	$comment: z.array(z.string()).optional(),
	fixtures: z
		.array(fixtureExpectationSchema)
		.min(1)
		.refine(
			(fixtures) => new Set(fixtures.map((fixture) => fixture.name)).size === fixtures.length,
			{ error: "fixture names must be unique — they are artifact directory names" },
		),
});

type LineRange = z.infer<typeof lineRangeSchema>;
export type FixtureExpectation = z.infer<typeof fixtureExpectationSchema>;

/** Everything derivable from a fixture plus its report, with no run facts mixed in. */
export type ReportEvaluation = {
	passed: boolean;
	observed: CriterionId[];
	missing: CriterionId[];
	unexpected: CriterionId[];
	/** Declared not applicable but reported as applicable, or not scored at all. */
	stillApplicable: CriterionId[];
	/** Expected to be violated, but the model declared the criterion inapplicable instead. */
	dodged: CriterionId[];
	/** Expected findings that matched nothing, rendered for the report. */
	unmatchedFindings: string[];
	/** Findings that landed inside a forbidden range, rendered for the report. */
	forbiddenFindings: string[];
};

type FixtureOutcome = ReportEvaluation & {
	name: string;
	exitCode: number;
	costUsd: number;
	failure?: string;
};

export function loadExpectations(path = EXPECTATIONS_PATH): FixtureExpectation[] {
	const parsed = expectationsFileSchema.safeParse(JSON.parse(readFileSync(path, "utf8")));

	if (!parsed.success) {
		throw new Error(
			`${path} is not a valid expectations file: ${parsed.error.issues
				.map((issue) => `${issue.path.map(String).join(".") || "<root>"}: ${issue.message}`)
				.join("; ")}`,
		);
	}

	return parsed.data.fixtures;
}

const USAGE = `Usage: npm run verify [-- --artifacts-dir <path>]

  --artifacts-dir <path>  Retain each fixture's review.json, review.md, and run.log under
                          <path>/<fixture-name>/ instead of discarding a temporary directory.
                          The destination must be an empty or non-existent directory.`;

/**
 * An argv mistake, as opposed to a bad expectations file. Only these print `USAGE` — an operator who
 * mistypes the flag needs to be told what the flag is, and they find out after kicking off a run
 * they expected to pay for.
 */
class UsageError extends Error {}

/**
 * `--artifacts-dir <path>` resolved against the cwd, or undefined for the
 * discard-the-output-directory default. Pure, like `src/cli.ts`'s `parseArgs`: the freshness check
 * and the `mkdir` live in `prepareArtifactsDir`, so an invalid expectations file cannot leave an
 * empty artifacts directory behind.
 */
export function parseArtifactsDir(argv: string[]): string | undefined {
	let value: string | undefined;

	// Shaped like `src/cli.ts`'s parser, for the reason recorded there: supporting only one of
	// `--flag value` / `--flag=value` "turns into a confusing failure at the worst moment". The worst
	// moment here is after four paid fixture runs have already discarded their output. An unknown
	// argument is rejected rather than ignored, so a typo cannot quietly mean "retain nothing".
	for (let index = 0; index < argv.length; index += 1) {
		const argument = argv[index] as string;
		const separator = argument.indexOf("=");
		const flag = separator === -1 ? argument : argument.slice(0, separator);

		if (flag !== "--artifacts-dir") {
			throw new UsageError(`Unknown argument "${argument}"`);
		}

		if (separator === -1) {
			index += 1;
			value = argv[index];
		} else {
			value = argument.slice(separator + 1);
		}

		if (value === undefined || value === "" || value.startsWith("--")) {
			throw new UsageError("--artifacts-dir requires a path.");
		}
	}

	return value === undefined ? undefined : resolve(process.cwd(), value);
}

/**
 * A destination that already holds files is refused rather than merged into: a half-overwritten
 * artifact directory silently mixes two runs, and these directories exist to be compared against a
 * baseline.
 */
export function prepareArtifactsDir(dir: string): void {
	if (existsSync(dir)) {
		// Without this, pointing the flag at a regular file reaches `readdirSync` and surfaces a bare
		// `ENOTDIR` scandir errno. `src/cli.ts` shapes the same mistake for `--diff-file`.
		if (!statSync(dir).isDirectory()) {
			throw new UsageError(`--artifacts-dir ${dir} is not a directory.`);
		}
		if (readdirSync(dir).length > 0) {
			throw new UsageError(`--artifacts-dir ${dir} is not empty; point it at a fresh destination.`);
		}
	}

	mkdirSync(dir, { recursive: true });
}

export function inRange(line: number, [start, end]: LineRange): boolean {
	return line >= start && line <= end;
}

function describeRange(target: { criterionId: CriterionId; file: string; lineRange: LineRange }): string {
	return `${target.criterionId} @ ${target.file}:${target.lineRange[0]}-${target.lineRange[1]}`;
}

/**
 * One-to-one so three expected findings cannot all be satisfied by a single reported one. The
 * fixture keeps its ranges non-overlapping, which makes greedy consumption in declaration order
 * exact rather than merely close.
 */
export function matchFindings(
	fixture: FixtureExpectation,
	findings: ReviewReport["findings"],
): Pick<ReportEvaluation, "unmatchedFindings" | "forbiddenFindings"> {
	const unconsumed = new Set(findings.keys());
	const unmatchedFindings: string[] = [];

	for (const expected of fixture.expectedFindings ?? []) {
		const hit = [...unconsumed].find((index) => {
			const finding = findings[index];
			return (
				finding !== undefined &&
				finding.criterionId === expected.criterionId &&
				finding.severity === expected.severity &&
				finding.file === expected.file &&
				inRange(finding.line, expected.lineRange)
			);
		});

		if (hit === undefined) {
			unmatchedFindings.push(`no ${expected.severity} ${describeRange(expected)}`);
		} else {
			unconsumed.delete(hit);
		}
	}

	const forbiddenFindings = (fixture.forbiddenFindingRanges ?? []).flatMap((forbidden) =>
		findings
			.filter(
				(finding) =>
					finding.criterionId === forbidden.criterionId &&
					finding.file === forbidden.file &&
					inRange(finding.line, forbidden.lineRange),
			)
			.map((finding) => `${describeRange(forbidden)} hit at line ${finding.line}`),
	);

	return { unmatchedFindings, forbiddenFindings };
}

/**
 * The whole comparison, with no I/O and no run facts — this is the gate, so it is the part that
 * needs a test rather than a paid run to exercise.
 */
export function evaluateReport(
	fixture: FixtureExpectation,
	report: ReviewReport,
): ReportEvaluation {
	const observed = [...new Set(report.findings.map((finding) => finding.criterionId))];
	const missing = fixture.expectedCriteria.filter((id) => !observed.includes(id));
	const unexpected = fixture.forbiddenCriteria.filter((id) => observed.includes(id));

	// Read from `criteria`, not `findings`: not-applicable is a property of the score entry, and a
	// criterion can be scored without producing a finding.
	const notApplicable = new Set(
		report.criteria.filter((entry) => !entry.applicable).map((entry) => entry.id),
	);
	const stillApplicable = (fixture.expectedNotApplicable ?? []).filter(
		(id) => !notApplicable.has(id),
	);

	// The escape-hatch guard, and the reason `applicable` cannot quietly weaken every fixture: a
	// criterion this patch plants a defect against cannot honestly be inapplicable. Checked
	// independently of `missing`, because a model can mark a criterion N/A and still report a
	// finding against it — that report is self-contradictory and should not pass.
	const dodged = fixture.expectedCriteria.filter((id) => notApplicable.has(id));

	const { unmatchedFindings, forbiddenFindings } = matchFindings(fixture, report.findings);

	return {
		passed:
			missing.length === 0 &&
			unexpected.length === 0 &&
			stillApplicable.length === 0 &&
			dodged.length === 0 &&
			unmatchedFindings.length === 0 &&
			forbiddenFindings.length === 0,
		observed,
		missing,
		unexpected,
		stillApplicable,
		dodged,
		unmatchedFindings,
		forbiddenFindings,
	};
}

function failedOutcome(
	fixture: FixtureExpectation,
	exitCode: number,
	costUsd: number,
	failure: string,
): FixtureOutcome {
	return {
		name: fixture.name,
		exitCode,
		passed: false,
		observed: [],
		missing: fixture.expectedCriteria,
		unexpected: [],
		stillApplicable: fixture.expectedNotApplicable ?? [],
		dodged: [],
		unmatchedFindings: (fixture.expectedFindings ?? []).map(
			(expected) => `no ${expected.severity} ${describeRange(expected)}`,
		),
		forbiddenFindings: [],
		costUsd,
		failure,
	};
}

function runFixture(fixture: FixtureExpectation, artifactsDir: string | undefined): FixtureOutcome {
	const retained = artifactsDir !== undefined;
	const outDir = retained
		? join(artifactsDir, fixture.name)
		: mkdtempSync(join(tmpdir(), `code-reviewer-verify-${fixture.name}-`));

	if (retained) {
		mkdirSync(outDir, { recursive: true });
	}

	// Hoisted out of the try so a throw after the run still reports the exit code and the money
	// actually spent, instead of booking a completed session as exit -1 and $0.0000.
	let exitCode = -1;
	let costUsd = 0;

	try {
		// Both streams are handed the same descriptor, so they share one file offset and the log is
		// genuinely interleaved — the same ordering a shell `2>&1` produces, which is what the Phase 1
		// baseline logs were captured with and therefore what they have to be compared against.
		// spawnSync's own `stdout`/`stderr` are null under this stdio, so the run output is read back
		// from the file. Written unconditionally: when nothing is retained the whole directory goes.
		const logPath = join(outDir, "run.log");
		const logFd = openSync(logPath, "w");
		const argv = [
			"--import",
			"tsx/esm",
			CLI_PATH,
			"--diff-file",
			join(FIXTURES_DIR, fixture.patch),
			"--out",
			outDir,
			"--verbose",
		];
		let spawnError: Error | undefined;
		try {
			// The real invocation, not a prettified stand-in: this log is read side by side with the
			// Phase 1 baseline's, and a header nobody can paste back into a shell is worth nothing.
			writeSync(logFd, `$ ${process.execPath} ${argv.join(" ")}\n\n`);
			const result = spawnSync(process.execPath, argv, {
				cwd: PACKAGE_ROOT,
				stdio: ["ignore", logFd, logFd],
			});
			exitCode = result.status ?? -1;
			spawnError = result.error;
		} finally {
			closeSync(logFd);
		}

		const runLog = readFileSync(logPath, "utf8");

		// The CLI reports cost only under --verbose, and only on a completed run. Parsed rather than
		// read from review.json because cost is a run fact, not part of the report contract 10X-19
		// consumes.
		costUsd = Number(/total cost: \$([0-9.]+)/.exec(runLog)?.[1] ?? 0);

		if (!COMPLETED_EXIT_CODES.has(exitCode)) {
			// `spawnError` is the whole story when the process never started (ENOENT, EMFILE) — the
			// log holds only the header in that case, so without it the operator sees "exited -1"
			// and nothing else.
			const cause = spawnError !== undefined ? `${spawnError.message}: ` : "";
			return failedOutcome(
				fixture,
				exitCode,
				costUsd,
				`the CLI exited ${exitCode}, so no report was produced: ${cause}${runLog.trim()}`,
			);
		}

		const report = reviewReportSchema.safeParse(
			JSON.parse(readFileSync(join(outDir, "review.json"), "utf8")),
		);
		if (!report.success) {
			return failedOutcome(
				fixture,
				exitCode,
				costUsd,
				"review.json did not match reviewReportSchema",
			);
		}

		return {
			name: fixture.name,
			exitCode,
			costUsd,
			...evaluateReport(fixture, report.data),
		};
	} catch (error) {
		return failedOutcome(
			fixture,
			exitCode,
			costUsd,
			error instanceof Error ? error.message : String(error),
		);
	} finally {
		if (!retained) {
			rmSync(outDir, { recursive: true, force: true });
		}
	}
}

function report(outcome: FixtureOutcome, fixture: FixtureExpectation): void {
	console.log(`\n${outcome.passed ? "PASS" : "FAIL"}  ${outcome.name}  (cli exit ${outcome.exitCode})`);
	console.log(`  expected   ${format(fixture.expectedCriteria)}`);
	console.log(`  forbidden  ${format(fixture.forbiddenCriteria)}`);
	console.log(`  observed   ${format(outcome.observed)}`);

	if (fixture.expectedNotApplicable !== undefined) {
		console.log(`  n/a        ${format(fixture.expectedNotApplicable)}`);
	}

	// Printed on a pass too, not just a failure. Without it a run that matched three range
	// assertions and a run that declared none produce identical output, and Phase 6 reads the
	// "4/4 fixtures passed" line as proof that all of them ran.
	const expectedFindings = fixture.expectedFindings?.length ?? 0;
	const forbiddenRanges = fixture.forbiddenFindingRanges?.length ?? 0;
	if (expectedFindings > 0 || forbiddenRanges > 0) {
		console.log(
			`  findings   ${expectedFindings - outcome.unmatchedFindings.length}/${expectedFindings} matched, ` +
				`${outcome.forbiddenFindings.length} hit in ${forbiddenRanges} forbidden range(s)`,
		);
	}

	if (outcome.missing.length > 0) {
		console.log(`  MISSING    ${format(outcome.missing)}`);
	}
	if (outcome.unexpected.length > 0) {
		console.log(`  UNEXPECTED ${format(outcome.unexpected)}`);
	}
	if (outcome.stillApplicable.length > 0) {
		console.log(`  APPLICABLE ${format(outcome.stillApplicable)}`);
	}
	if (outcome.dodged.length > 0) {
		console.log(`  DODGED     ${format(outcome.dodged)}`);
	}
	if (outcome.unmatchedFindings.length > 0) {
		console.log(`  UNMATCHED  ${format(outcome.unmatchedFindings)}`);
	}
	if (outcome.forbiddenFindings.length > 0) {
		console.log(`  FALSE POS  ${format(outcome.forbiddenFindings)}`);
	}
	if (outcome.failure !== undefined) {
		console.log(`  ERROR      ${outcome.failure}`);
	}
}

function format(ids: string[]): string {
	return ids.length === 0 ? "(none)" : ids.join(", ");
}

async function main(): Promise<number> {
	const artifactsDir = parseArtifactsDir(process.argv.slice(2));
	// Before the directory is created, so an invalid expectations file fails without leaving an
	// empty artifacts directory that the next run would then refuse as "not empty".
	const fixtures = loadExpectations();
	if (artifactsDir !== undefined) {
		prepareArtifactsDir(artifactsDir);
	}

	console.log(`Running ${fixtures.length} fixture(s) through the review CLI — this makes real API calls.`);
	if (artifactsDir !== undefined) {
		console.log(`Retaining review.json, review.md, and run.log under ${artifactsDir}`);
	}

	const outcomes: FixtureOutcome[] = [];
	for (const fixture of fixtures) {
		// Sequential on purpose: concurrent sessions would race on the cost ceiling and make the
		// total cost line meaningless.
		const outcome = runFixture(fixture, artifactsDir);
		outcomes.push(outcome);
		report(outcome, fixture);
	}

	const failed = outcomes.filter((outcome) => !outcome.passed);
	const totalCost = outcomes.reduce((sum, outcome) => sum + outcome.costUsd, 0);

	console.log(`\n${outcomes.length - failed.length}/${outcomes.length} fixtures passed.`);
	console.log(`Total cost: $${totalCost.toFixed(4)}`);

	if (failed.length > 0) {
		console.error(`\nFailed: ${failed.map((outcome) => outcome.name).join(", ")}`);
		return 1;
	}

	return 0;
}

// Guarded so `test/verify.test.ts` can import the pure gate logic above without spawning four paid
// review sessions on import. Same check `src/cli.ts` uses for the same reason.
const invokedDirectly =
	process.argv[1] !== undefined &&
	pathToFileURL(resolve(process.argv[1])).href === import.meta.url;

if (invokedDirectly) {
	// An unknown or mistyped argument, a `--artifacts-dir` with no path, a destination that already
	// holds a previous run, and an invalid expectations file are all operator errors. Reported as a
	// one-line message rather than a stack trace, since none of them is a harness bug.
	process.exitCode = await main().catch((error: unknown) => {
		console.error(error instanceof Error ? error.message : String(error));
		if (error instanceof UsageError) {
			console.error(`\n${USAGE}`);
		}
		return 1;
	});
}
