/**
 * Every test here runs against an injected session runner, so nothing in this file touches the
 * network or the Claude Agent SDK. The runner used by the input-rejection tests throws if it is
 * called at all, which is how "exits 1 without attempting a session" is actually asserted rather
 * than assumed.
 */
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, it } from "node:test";

import type { AgentFailureKind, StructuredSessionRequest } from "../src/agent.ts";
import {
	EXIT_BLOCKED,
	EXIT_INVALID_INPUT,
	EXIT_NO_USABLE_RESULT,
	EXIT_OK,
	MAX_DIFF_BYTES,
	REVIEW_JSON_FILENAME,
	REVIEW_MARKDOWN_FILENAME,
	exitCodeForFailure,
	parseArgs,
	run,
	type CliDeps,
	type SessionRunner,
} from "../src/cli.ts";
import {
	CRITERION_IDS,
	reviewReportSchema,
	type Finding,
	type Severity,
	type Verdict,
} from "../src/schema.ts";
import { DEFAULT_MAX_BUDGET_USD, DEFAULT_MODEL } from "../src/agent.ts";

const SAMPLE_DIFF = [
	"diff --git a/src/main/resources/db/migration/V3__create_preference_profiles.sql b/src/main/resources/db/migration/V3__create_preference_profiles.sql",
	"--- a/src/main/resources/db/migration/V3__create_preference_profiles.sql",
	"+++ b/src/main/resources/db/migration/V3__create_preference_profiles.sql",
	"@@ -4,6 +4,7 @@ CREATE TABLE preference_profiles (",
	"+    nickname VARCHAR(64),",
	"",
].join("\n");

function verdictWith(findings: Finding[]): Verdict {
	return {
		criteria: CRITERION_IDS.map((id) => ({ id, score: 8, justification: `Scored ${id}.` })),
		findings,
	};
}

const CRITICAL_FINDING: Finding = {
	file: "src/main/resources/db/migration/V3__create_preference_profiles.sql",
	line: 4,
	criterionId: "flyway-forward-only",
	severity: "critical",
	message: "Edits an already-applied migration.",
};

/**
 * Fails the test if a session is ever started, and counts attempts so the caller can assert the
 * negative directly. The throw alone would be a fragile guarantee: it is observable only because
 * `run()` happens not to wrap `deps.runSession` in a `try`, so adding one — a reasonable-looking
 * hardening change — would swallow it and leave these tests green with the property gone.
 */
function forbiddenRunner(): { runner: SessionRunner; calls: number } {
	const state = {
		runner: (() => {}) as unknown as SessionRunner,
		calls: 0,
	};
	state.runner = (() => {
		state.calls += 1;
		throw new Error("A session was started when none should have been");
	}) as SessionRunner;
	return state;
}

function runnerReturning(verdict: Verdict): { runner: SessionRunner; calls: number } {
	const state = { runner: (() => {}) as unknown as SessionRunner, calls: 0 };
	state.runner = (async <T>(request: StructuredSessionRequest<T>) => {
		state.calls += 1;
		const validated = request.validate(verdict);
		assert.ok(validated.ok, "the CLI validator should accept a well-formed verdict");
		return {
			ok: true as const,
			value: validated.value,
			totalCostUsd: 0.0123,
			numTurns: 2,
			modelUsage: {},
			session: {
				claudeCodeVersion: "2.1.224",
				resolvedModel: "claude-sonnet-5-20260101",
				availableTools: ["Read", "Glob", "Grep"],
				permissionMode: "dontAsk",
			},
		};
	}) as SessionRunner;
	return state;
}

function runnerFailing(kind: AgentFailureKind): SessionRunner {
	return (async () => ({
		ok: false as const,
		kind,
		diagnostic: `simulated ${kind}`,
	})) as SessionRunner;
}

describe("parseArgs", () => {
	const cwd = "/repo";

	it("requires --diff-file", () => {
		const parsed = parseArgs([], cwd);

		assert.equal(parsed.ok, false);
		assert.match(parsed.ok ? "" : parsed.error, /--diff-file is required/);
	});

	it("applies the documented defaults", () => {
		const parsed = parseArgs(["--diff-file", "d.patch"], cwd);

		assert.ok(parsed.ok);
		assert.deepEqual(parsed.value, {
			diffFile: "d.patch",
			model: DEFAULT_MODEL,
			maxBudgetUsd: DEFAULT_MAX_BUDGET_USD,
			failOn: "high",
			outDir: cwd,
			verbose: false,
			help: false,
		});
	});

	it("accepts every flag in both --flag value and --flag=value form", () => {
		const spaced = parseArgs(
			[
				"--diff-file",
				"d.patch",
				"--model",
				"claude-opus-5",
				"--max-budget-usd",
				"1.25",
				"--fail-on",
				"medium",
				"--out",
				"out",
				"--verbose",
			],
			cwd,
		);
		const joined = parseArgs(
			[
				"--diff-file=d.patch",
				"--model=claude-opus-5",
				"--max-budget-usd=1.25",
				"--fail-on=medium",
				"--out=out",
				"--verbose",
			],
			cwd,
		);

		assert.ok(spaced.ok && joined.ok);
		assert.deepEqual(spaced.value, joined.value);
		assert.equal(spaced.value.maxBudgetUsd, 1.25);
		assert.equal(spaced.value.failOn, "medium");
		assert.equal(spaced.value.outDir, join(cwd, "out"));
		assert.equal(spaced.value.verbose, true);
	});

	it("rejects an unknown flag rather than ignoring it", () => {
		const parsed = parseArgs(["--diff-file", "d.patch", "--fail-fast"], cwd);

		assert.equal(parsed.ok, false);
		assert.match(parsed.ok ? "" : parsed.error, /Unknown argument "--fail-fast"/);
	});

	it("rejects a flag whose value is missing", () => {
		const parsed = parseArgs(["--diff-file"], cwd);

		assert.equal(parsed.ok, false);
		assert.match(parsed.ok ? "" : parsed.error, /--diff-file requires a path/);
	});

	it("rejects a severity outside the enumeration", () => {
		const parsed = parseArgs(["--diff-file", "d.patch", "--fail-on", "blocker"], cwd);

		assert.equal(parsed.ok, false);
		assert.match(parsed.ok ? "" : parsed.error, /must be one of low, medium, high, critical/);
	});

	it("rejects a non-positive or non-numeric budget", () => {
		for (const value of ["0", "-1", "cheap"]) {
			const parsed = parseArgs(["--diff-file", "d.patch", "--max-budget-usd", value], cwd);
			assert.equal(parsed.ok, false, `budget "${value}" should be rejected`);
		}
	});

	it("allows --help without --diff-file", () => {
		const parsed = parseArgs(["--help"], cwd);

		assert.ok(parsed.ok);
		assert.equal(parsed.value.help, true);
	});
});

describe("exitCodeForFailure", () => {
	// The contract 10X-19 branches on: 1 means the run never got going, 2 means it did and came back
	// empty-handed. Enumerated exhaustively so a new failure kind cannot default into the wrong arm.
	it("maps a startup failure to the invalid-input code", () => {
		assert.equal(exitCodeForFailure("startup_failure"), EXIT_INVALID_INPUT);
	});

	it("maps every post-startup failure to the no-usable-result code", () => {
		const postStartup: AgentFailureKind[] = [
			"max_turns",
			"max_budget_usd",
			"structured_output_retries_exhausted",
			"execution_error",
			"missing_structured_output",
			"invalid_structured_output",
			"no_result",
		];

		for (const kind of postStartup) {
			assert.equal(exitCodeForFailure(kind), EXIT_NO_USABLE_RESULT, `${kind} should exit 2`);
		}
	});
});

describe("run", () => {
	let workspace: string;
	let logs: string[];
	let errors: string[];

	function depsWith(runner: SessionRunner): CliDeps {
		return {
			cwd: workspace,
			runSession: runner,
			log: (line) => logs.push(line),
			logError: (line) => errors.push(line),
		};
	}

	beforeEach(() => {
		workspace = mkdtempSync(join(tmpdir(), "code-reviewer-"));
		logs = [];
		errors = [];
	});

	afterEach(() => {
		rmSync(workspace, { recursive: true, force: true });
	});

	describe("input rejection", () => {
		it("exits 1 for a missing diff file without starting a session", async () => {
			const forbidden = forbiddenRunner();

			const code = await run(["--diff-file", "nope.patch"], depsWith(forbidden.runner));

			assert.equal(code, EXIT_INVALID_INPUT);
			assert.equal(forbidden.calls, 0);
			assert.match(errors.join("\n"), /Cannot read --diff-file/);
		});

		it("exits 1 for an unreadable diff path without starting a session", async () => {
			const forbidden = forbiddenRunner();

			const code = await run(["--diff-file", "."], depsWith(forbidden.runner));

			assert.equal(code, EXIT_INVALID_INPUT);
			assert.equal(forbidden.calls, 0);
			assert.match(errors.join("\n"), /not a regular file/);
		});

		it("exits 1 for an oversized diff, naming the actual and maximum byte counts", async () => {
			const path = join(workspace, "huge.patch");
			const bytes = MAX_DIFF_BYTES + 1;
			writeFileSync(path, "x".repeat(bytes), "utf8");
			const forbidden = forbiddenRunner();

			const code = await run(["--diff-file", path], depsWith(forbidden.runner));

			assert.equal(code, EXIT_INVALID_INPUT);
			assert.equal(forbidden.calls, 0);
			assert.match(errors.join("\n"), new RegExp(`${bytes} bytes`));
			assert.match(errors.join("\n"), new RegExp(`${MAX_DIFF_BYTES}-byte limit`));
		});

		it("exits 1 for an invalid invocation and prints usage", async () => {
			const forbidden = forbiddenRunner();

			const code = await run(["--fail-on", "blocker"], depsWith(forbidden.runner));

			assert.equal(code, EXIT_INVALID_INPUT);
			assert.equal(forbidden.calls, 0);
			assert.match(errors.join("\n"), /Usage: npm run review/);
		});

		it("accepts a diff exactly at the byte limit", async () => {
			const path = join(workspace, "limit.patch");
			writeFileSync(path, "x".repeat(MAX_DIFF_BYTES), "utf8");
			const runner = runnerReturning(verdictWith([]));

			const code = await run(["--diff-file", path], depsWith(runner.runner));

			assert.equal(code, EXIT_OK);
			assert.equal(runner.calls, 1);
		});
	});

	describe("completed runs", () => {
		let diffPath: string;

		beforeEach(() => {
			diffPath = join(workspace, "sample.patch");
			writeFileSync(diffPath, SAMPLE_DIFF, "utf8");
		});

		it("exits 0 and writes both artifacts when nothing blocks", async () => {
			const runner = runnerReturning(verdictWith([]));

			const code = await run(["--diff-file", diffPath], depsWith(runner.runner));

			assert.equal(code, EXIT_OK);
			const report = JSON.parse(readFileSync(join(workspace, REVIEW_JSON_FILENAME), "utf8"));
			assert.ok(reviewReportSchema.safeParse(report).success);
			assert.equal(report.passed, true);
			assert.deepEqual(report.reasons, []);
			assert.match(
				readFileSync(join(workspace, REVIEW_MARKDOWN_FILENAME), "utf8"),
				/\*\*Passed\*\*/,
			);
		});

		it("exits 3 when a finding is at or above the threshold", async () => {
			const runner = runnerReturning(verdictWith([CRITICAL_FINDING]));

			const code = await run(["--diff-file", diffPath], depsWith(runner.runner));

			assert.equal(code, EXIT_BLOCKED);
			const report = JSON.parse(readFileSync(join(workspace, REVIEW_JSON_FILENAME), "utf8"));
			assert.equal(report.passed, false);
			assert.equal(report.reasons.length, 1);
			assert.match(readFileSync(join(workspace, REVIEW_MARKDOWN_FILENAME), "utf8"), /\*\*Blocked\*\*/);
		});

		// Exit 3 has to follow the threshold, not the raw presence of findings — otherwise `--fail-on`
		// is decorative and CI cannot be loosened or tightened without a code change.
		it("lets --fail-on move the same verdict between exit 0 and exit 3", async () => {
			const advisory: Finding = { ...CRITICAL_FINDING, severity: "medium" };

			for (const [failOn, expected] of [
				["high", EXIT_OK],
				["medium", EXIT_BLOCKED],
				["low", EXIT_BLOCKED],
			] as [Severity, number][]) {
				const runner = runnerReturning(verdictWith([advisory]));
				const code = await run(
					["--diff-file", diffPath, "--fail-on", failOn],
					depsWith(runner.runner),
				);

				assert.equal(code, expected, `--fail-on ${failOn} should exit ${expected}`);
			}
		});

		it("writes artifacts into --out, creating the directory", async () => {
			const outDir = join(workspace, "nested", "reports");
			const runner = runnerReturning(verdictWith([]));

			const code = await run(["--diff-file", diffPath, "--out", outDir], depsWith(runner.runner));

			assert.equal(code, EXIT_OK);
			assert.ok(readFileSync(join(outDir, REVIEW_JSON_FILENAME), "utf8").length > 0);
			assert.ok(readFileSync(join(outDir, REVIEW_MARKDOWN_FILENAME), "utf8").length > 0);
		});

		it("passes the configured model, budget, and delimited diff into the session", async () => {
			let seen: StructuredSessionRequest<Verdict> | undefined;
			const runner: SessionRunner = (async <T>(request: StructuredSessionRequest<T>) => {
				seen = request as unknown as StructuredSessionRequest<Verdict>;
				return {
					ok: false as const,
					kind: "execution_error" as const,
					diagnostic: "captured",
				};
			}) as SessionRunner;

			await run(
				["--diff-file", diffPath, "--model", "claude-opus-5", "--max-budget-usd", "0.10"],
				depsWith(runner),
			);

			assert.equal(seen?.model, "claude-opus-5");
			assert.equal(seen?.maxBudgetUsd, 0.1);
			assert.ok(seen?.prompt.includes("nickname VARCHAR(64)"));
			assert.ok(seen?.prompt.includes("UNTRUSTED DIFF DATA"));
			assert.equal(seen?.jsonSchema.$schema, "http://json-schema.org/draft-07/schema#");
		});

		it("logs the cost signals under --verbose and stays quiet without it", async () => {
			const quiet = runnerReturning(verdictWith([]));
			await run(["--diff-file", diffPath], depsWith(quiet.runner));
			assert.equal(
				logs.some((line) => line.includes("total cost")),
				false,
			);

			logs = [];
			const loud = runnerReturning(verdictWith([]));
			await run(["--diff-file", diffPath, "--verbose"], depsWith(loud.runner));

			const output = logs.join("\n");
			assert.match(output, /configured model: claude-sonnet-5/);
			assert.match(output, /resolved model: claude-sonnet-5-20260101/);
			assert.match(output, /turns: 2, total cost: \$0\.0123/);
			assert.match(output, /modelUsage:/);
			assert.match(output, new RegExp(`${SAMPLE_DIFF.length} bytes`));
		});
	});

	describe("failed runs", () => {
		let diffPath: string;

		beforeEach(() => {
			diffPath = join(workspace, "sample.patch");
			writeFileSync(diffPath, SAMPLE_DIFF, "utf8");
		});

		it("maps a startup failure to exit 1", async () => {
			const code = await run(
				["--diff-file", diffPath],
				depsWith(runnerFailing("startup_failure")),
			);

			assert.equal(code, EXIT_INVALID_INPUT);
			assert.match(errors.join("\n"), /startup_failure/);
		});

		it("maps each post-startup failure to exit 2 and surfaces its diagnostic", async () => {
			const kinds: AgentFailureKind[] = [
				"max_turns",
				"max_budget_usd",
				"structured_output_retries_exhausted",
				"execution_error",
				"missing_structured_output",
				"invalid_structured_output",
				"no_result",
			];

			for (const kind of kinds) {
				errors = [];
				const code = await run(["--diff-file", diffPath], depsWith(runnerFailing(kind)));

				assert.equal(code, EXIT_NO_USABLE_RESULT, `${kind} should exit 2`);
				assert.match(errors.join("\n"), new RegExp(`simulated ${kind}`));
			}
		});
	});
});
