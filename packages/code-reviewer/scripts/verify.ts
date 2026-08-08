/**
 * The review-quality harness. Runs every fixture in `fixtures/expectations.json` through the real
 * CLI — real API calls, real model — and diffs the criterion ids it reported against what the
 * fixture declares. Three API runs per pass, so this is a deliberate action, not something to wire
 * into a file watcher.
 *
 * What it proves, taken together: the planted defects are actually found, removing one makes it
 * disappear (the deliberate break), and a clean diff produces nothing (the false-positive control).
 * Any one of those alone is satisfiable by a degenerate reviewer.
 *
 * Matching is on criterion ids present in `findings`, never on prose. A probabilistic reviewer
 * words the same finding differently every run, so prose matching would fail for reasons that have
 * nothing to do with review quality.
 */
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

import { reviewReportSchema, type CriterionId } from "../src/schema.ts";

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

type FixtureExpectation = {
	name: string;
	patch: string;
	description?: string;
	expectedCriteria: CriterionId[];
	forbiddenCriteria: CriterionId[];
};

type FixtureOutcome = {
	name: string;
	exitCode: number;
	passed: boolean;
	observed: CriterionId[];
	missing: CriterionId[];
	unexpected: CriterionId[];
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

function runFixture(fixture: FixtureExpectation): FixtureOutcome {
	const outDir = mkdtempSync(join(tmpdir(), `code-reviewer-verify-${fixture.name}-`));

	try {
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
			{ cwd: PACKAGE_ROOT, encoding: "utf8" },
		);

		const exitCode = result.status ?? -1;
		const stdout = result.stdout ?? "";
		const stderr = result.stderr ?? "";
		// The CLI reports cost only under --verbose, and only on a completed run. Parsed rather than
		// read from review.json because cost is a run fact, not part of the report contract 10X-19
		// consumes.
		const costUsd = Number(/total cost: \$([0-9.]+)/.exec(stdout)?.[1] ?? 0);

		if (!COMPLETED_EXIT_CODES.has(exitCode)) {
			return {
				name: fixture.name,
				exitCode,
				passed: false,
				observed: [],
				missing: fixture.expectedCriteria,
				unexpected: [],
				costUsd,
				failure: `the CLI exited ${exitCode}, so no report was produced: ${stderr.trim() || stdout.trim()}`,
			};
		}

		const report = reviewReportSchema.safeParse(
			JSON.parse(readFileSync(join(outDir, "review.json"), "utf8")),
		);
		if (!report.success) {
			return {
				name: fixture.name,
				exitCode,
				passed: false,
				observed: [],
				missing: fixture.expectedCriteria,
				unexpected: [],
				costUsd,
				failure: "review.json did not match reviewReportSchema",
			};
		}

		const observed = [...new Set(report.data.findings.map((finding) => finding.criterionId))];
		const missing = fixture.expectedCriteria.filter((id) => !observed.includes(id));
		const unexpected = fixture.forbiddenCriteria.filter((id) => observed.includes(id));

		return {
			name: fixture.name,
			exitCode,
			passed: missing.length === 0 && unexpected.length === 0,
			observed,
			missing,
			unexpected,
			costUsd,
		};
	} catch (error) {
		return {
			name: fixture.name,
			exitCode: -1,
			passed: false,
			observed: [],
			missing: fixture.expectedCriteria,
			unexpected: [],
			costUsd: 0,
			failure: error instanceof Error ? error.message : String(error),
		};
	} finally {
		rmSync(outDir, { recursive: true, force: true });
	}
}

function report(outcome: FixtureOutcome, fixture: FixtureExpectation): void {
	console.log(`\n${outcome.passed ? "PASS" : "FAIL"}  ${outcome.name}  (cli exit ${outcome.exitCode})`);
	console.log(`  expected   ${format(fixture.expectedCriteria)}`);
	console.log(`  forbidden  ${format(fixture.forbiddenCriteria)}`);
	console.log(`  observed   ${format(outcome.observed)}`);

	if (outcome.missing.length > 0) {
		console.log(`  MISSING    ${format(outcome.missing)}`);
	}
	if (outcome.unexpected.length > 0) {
		console.log(`  UNEXPECTED ${format(outcome.unexpected)}`);
	}
	if (outcome.failure !== undefined) {
		console.log(`  ERROR      ${outcome.failure}`);
	}
}

function format(ids: string[]): string {
	return ids.length === 0 ? "(none)" : ids.join(", ");
}

async function main(): Promise<number> {
	const fixtures = loadExpectations();
	console.log(`Running ${fixtures.length} fixture(s) through the review CLI — this makes real API calls.`);

	const outcomes: FixtureOutcome[] = [];
	for (const fixture of fixtures) {
		// Sequential on purpose: three concurrent sessions would race on the cost ceiling and make
		// the total cost line meaningless.
		const outcome = runFixture(fixture);
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

process.exitCode = await main();
