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
	writeSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { reviewReportSchema, type CriterionId, type Severity } from "../src/schema.ts";

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
type LineRange = [number, number];

type ExpectedFinding = {
	criterionId: CriterionId;
	severity: Severity;
	lineRange: LineRange;
	note?: string;
};

type ForbiddenFindingRange = {
	criterionId: CriterionId;
	lineRange: LineRange;
	note?: string;
};

type FixtureExpectation = {
	name: string;
	patch: string;
	description?: string;
	expectedCriteria: CriterionId[];
	forbiddenCriteria: CriterionId[];
	/** Optional. A criterion listed here must come back `applicable: false`. */
	expectedNotApplicable?: CriterionId[];
	/** Optional. Each entry must match exactly one finding, one-to-one. */
	expectedFindings?: ExpectedFinding[];
	/** Optional. No finding for the named criterion may fall inside the range. */
	forbiddenFindingRanges?: ForbiddenFindingRange[];
};

type FixtureOutcome = {
	name: string;
	exitCode: number;
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
	costUsd: number;
	failure?: string;
};

function loadExpectations(): FixtureExpectation[] {
	const raw = JSON.parse(readFileSync(EXPECTATIONS_PATH, "utf8")) as {
		fixtures?: FixtureExpectation[];
	};

	if (!Array.isArray(raw.fixtures) || raw.fixtures.length === 0) {
		throw new Error(`${EXPECTATIONS_PATH} declares no fixtures.`);
	}

	return raw.fixtures;
}

/**
 * `--artifacts-dir <path>`, or undefined for today's discard-the-output-directory behavior. A
 * destination that already holds files is refused rather than merged into: a half-overwritten
 * artifact directory silently mixes two runs, and these directories exist to be compared against a
 * baseline.
 */
function parseArtifactsDir(argv: string[]): string | undefined {
	const flagIndex = argv.indexOf("--artifacts-dir");
	if (flagIndex === -1) {
		return undefined;
	}

	const value = argv[flagIndex + 1];
	if (value === undefined || value.startsWith("--")) {
		throw new Error("--artifacts-dir requires a path.");
	}

	const dir = resolve(process.cwd(), value);
	if (existsSync(dir) && readdirSync(dir).length > 0) {
		throw new Error(`--artifacts-dir ${dir} is not empty; point it at a fresh destination.`);
	}

	mkdirSync(dir, { recursive: true });
	return dir;
}

function inRange(line: number, [start, end]: LineRange): boolean {
	return line >= start && line <= end;
}

function describeRange(criterionId: CriterionId, [start, end]: LineRange): string {
	return `${criterionId} @ ${start}-${end}`;
}

/**
 * One-to-one so three expected findings cannot all be satisfied by a single reported one. The
 * fixture keeps its ranges non-overlapping, which makes greedy consumption in declaration order
 * exact rather than merely close.
 */
function matchFindings(
	fixture: FixtureExpectation,
	findings: { criterionId: CriterionId; severity: Severity; line: number }[],
): Pick<FixtureOutcome, "unmatchedFindings" | "forbiddenFindings"> {
	const unconsumed = new Set(findings.keys());
	const unmatchedFindings: string[] = [];

	for (const expected of fixture.expectedFindings ?? []) {
		const hit = [...unconsumed].find((index) => {
			const finding = findings[index];
			return (
				finding !== undefined &&
				finding.criterionId === expected.criterionId &&
				finding.severity === expected.severity &&
				inRange(finding.line, expected.lineRange)
			);
		});

		if (hit === undefined) {
			unmatchedFindings.push(
				`no ${expected.severity} ${describeRange(expected.criterionId, expected.lineRange)}`,
			);
		} else {
			unconsumed.delete(hit);
		}
	}

	const forbiddenFindings = (fixture.forbiddenFindingRanges ?? []).flatMap((forbidden) =>
		findings
			.filter(
				(finding) =>
					finding.criterionId === forbidden.criterionId &&
					inRange(finding.line, forbidden.lineRange),
			)
			.map(
				(finding) =>
					`${describeRange(forbidden.criterionId, forbidden.lineRange)} hit at line ${finding.line}`,
			),
	);

	return { unmatchedFindings, forbiddenFindings };
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
		unmatchedFindings: (fixture.expectedFindings ?? []).map((expected) =>
			`no ${expected.severity} ${describeRange(expected.criterionId, expected.lineRange)}`,
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

	try {
		// Both streams are handed the same descriptor, so they share one file offset and the log is
		// genuinely interleaved — the same ordering a shell `2>&1` produces, which is what the Phase 1
		// baseline logs were captured with and therefore what they have to be compared against.
		// spawnSync's own `stdout`/`stderr` are null under this stdio, so the run output is read back
		// from the file. Written unconditionally: when nothing is retained the whole directory goes.
		const logPath = join(outDir, "run.log");
		const logFd = openSync(logPath, "w");
		let exitCode: number;
		try {
			writeSync(logFd, `$ code-reviewer --diff-file ${fixture.patch} --verbose\n\n`);
			const result = spawnSync(
				process.execPath,
				[
					"--import",
					"tsx/esm",
					CLI_PATH,
					"--diff-file",
					join(FIXTURES_DIR, fixture.patch),
					"--out",
					outDir,
					"--verbose",
				],
				{ cwd: PACKAGE_ROOT, stdio: ["ignore", logFd, logFd] },
			);
			exitCode = result.status ?? -1;
		} finally {
			closeSync(logFd);
		}

		const runLog = readFileSync(logPath, "utf8");

		// The CLI reports cost only under --verbose, and only on a completed run. Parsed rather than
		// read from review.json because cost is a run fact, not part of the report contract 10X-19
		// consumes.
		const costUsd = Number(/total cost: \$([0-9.]+)/.exec(runLog)?.[1] ?? 0);

		if (!COMPLETED_EXIT_CODES.has(exitCode)) {
			return failedOutcome(
				fixture,
				exitCode,
				costUsd,
				`the CLI exited ${exitCode}, so no report was produced: ${runLog.trim()}`,
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

		const observed = [...new Set(report.data.findings.map((finding) => finding.criterionId))];
		const missing = fixture.expectedCriteria.filter((id) => !observed.includes(id));
		const unexpected = fixture.forbiddenCriteria.filter((id) => observed.includes(id));

		// Read from `criteria`, not `findings`: not-applicable is a property of the score entry, and
		// a criterion can be scored without producing a finding.
		const notApplicable = new Set(
			report.data.criteria.filter((entry) => !entry.applicable).map((entry) => entry.id),
		);
		const stillApplicable = (fixture.expectedNotApplicable ?? []).filter(
			(id) => !notApplicable.has(id),
		);

		// The escape-hatch guard, and the reason `applicable` cannot quietly weaken every fixture: a
		// criterion this patch plants a defect against cannot honestly be inapplicable. Checked
		// independently of `missing`, because a model can mark a criterion N/A and still report a
		// finding against it — that report is self-contradictory and should not pass.
		const dodged = fixture.expectedCriteria.filter((id) => notApplicable.has(id));

		const { unmatchedFindings, forbiddenFindings } = matchFindings(fixture, report.data.findings);

		return {
			name: fixture.name,
			exitCode,
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
			costUsd,
		};
	} catch (error) {
		return failedOutcome(
			fixture,
			-1,
			0,
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
	const fixtures = loadExpectations();
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

// A mistyped `--artifacts-dir`, a destination that already holds a previous run, or an unreadable
// expectations file are all operator errors. Reported as a one-line message rather than a stack
// trace, since none of them is a harness bug.
process.exitCode = await main().catch((error: unknown) => {
	console.error(error instanceof Error ? error.message : String(error));
	return 1;
});
