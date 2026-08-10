/**
 * The fixture harness is a gate, and `verdict.ts` — the other gate in this package — has tests for
 * exactly that reason. Everything here is free and offline: the paid part of `scripts/verify.ts` is
 * the CLI spawn, and none of these tests touch it.
 *
 * What these cover is the failure mode that would be most expensive to discover: a harness that
 * reports 4/4 when an expectation was not actually checked. Phase 6 of the
 * `test-verifies-behavior` plan reads that line as proof.
 */
import assert from "node:assert/strict";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";

import { load as loadYaml } from "js-yaml";

import {
	evaluateReport,
	inRange,
	loadExpectations,
	matchFindings,
	parseArtifactsDir,
	prepareArtifactsDir,
	type FixtureExpectation,
} from "../scripts/verify.ts";
import { CRITERION_IDS, type Finding, type ReviewReport } from "../src/schema.ts";

const TARGET = "test-verifies-behavior";
const FILE = "src/test/java/com/nextslope/recommendation/WeightedDistanceScorerTests.java";

function finding(line: number, overrides: Partial<Finding> = {}): Finding {
	return {
		file: FILE,
		line,
		criterionId: TARGET,
		severity: "medium",
		message: "This test cannot fail.",
		...overrides,
	};
}

/** Every criterion applicable and scored, so any evaluation movement is attributable to the test. */
function reportWith(findings: Finding[], notApplicable: string[] = []): ReviewReport {
	return {
		criteria: CRITERION_IDS.map((id) => ({
			id,
			applicable: !notApplicable.includes(id),
			score: 8,
			justification: `Nothing to flag for ${id}.`,
		})),
		findings,
		passed: findings.length === 0,
		reasons: [],
	};
}

function fixtureWith(overrides: Partial<FixtureExpectation> = {}): FixtureExpectation {
	return {
		name: "assertion-free-tests",
		patch: "assertion-free-tests.patch",
		expectedCriteria: [TARGET],
		forbiddenCriteria: [],
		...overrides,
	};
}

describe("inRange", () => {
	it("is inclusive at both ends", () => {
		assert.equal(inRange(29, [29, 36]), true);
		assert.equal(inRange(36, [29, 36]), true);
	});

	it("rejects a line one outside either end", () => {
		assert.equal(inRange(28, [29, 36]), false);
		assert.equal(inRange(37, [29, 36]), false);
	});
});

describe("matchFindings", () => {
	it("does not let one reported finding satisfy two expectations", () => {
		const fixture = fixtureWith({
			expectedFindings: [
				{ criterionId: TARGET, severity: "medium", file: FILE, lineRange: [29, 40] },
				{ criterionId: TARGET, severity: "medium", file: FILE, lineRange: [30, 41] },
			],
		});

		// One finding, inside both declared ranges. Consuming it for the first expectation must
		// leave the second unmatched — otherwise three planted violations could all be "proved" by
		// a reviewer that found one.
		const matched = matchFindings(fixture, [finding(35)]);

		assert.equal(matched.unmatchedFindings.length, 1);
	});

	it("matches distinct findings one-to-one across disjoint ranges", () => {
		const fixture = fixtureWith({
			expectedFindings: [
				{ criterionId: TARGET, severity: "medium", file: FILE, lineRange: [29, 36] },
				{ criterionId: TARGET, severity: "medium", file: FILE, lineRange: [37, 41] },
			],
		});

		const matched = matchFindings(fixture, [finding(38), finding(35)]);

		assert.deepEqual(matched.unmatchedFindings, []);
	});

	it("does not match a finding one line outside its range", () => {
		const fixture = fixtureWith({
			expectedFindings: [{ criterionId: TARGET, severity: "medium", file: FILE, lineRange: [81, 90] }],
		});

		const matched = matchFindings(fixture, [finding(91)]);

		assert.equal(matched.unmatchedFindings.length, 1);
	});

	it("does not match the right line in the wrong file", () => {
		const fixture = fixtureWith({
			expectedFindings: [{ criterionId: TARGET, severity: "medium", file: FILE, lineRange: [29, 36] }],
		});

		// A line number is meaningless without its file. On a multi-file patch this is the difference
		// between proving a planted defect and rubber-stamping a reviewer that anchored elsewhere.
		const matched = matchFindings(fixture, [
			finding(35, { file: "src/main/resources/application.properties" }),
		]);

		assert.equal(matched.unmatchedFindings.length, 1);
	});

	it("requires the declared severity, so the rollout cap is actually tested", () => {
		const fixture = fixtureWith({
			expectedFindings: [{ criterionId: TARGET, severity: "medium", file: FILE, lineRange: [29, 36] }],
		});

		const matched = matchFindings(fixture, [finding(35, { severity: "high" })]);

		assert.equal(matched.unmatchedFindings.length, 1);
	});

	it("reports a finding inside a forbidden range", () => {
		const fixture = fixtureWith({
			forbiddenFindingRanges: [{ criterionId: TARGET, file: FILE, lineRange: [91, 97] }],
		});

		const matched = matchFindings(fixture, [finding(93)]);

		assert.equal(matched.forbiddenFindings.length, 1);
	});

	it("leaves a fixture that declares neither list unasserted", () => {
		const matched = matchFindings(fixtureWith(), [finding(35)]);

		assert.deepEqual(matched, { unmatchedFindings: [], forbiddenFindings: [] });
	});
});

describe("evaluateReport", () => {
	it("passes when every expectation is met", () => {
		const fixture = fixtureWith({
			expectedFindings: [{ criterionId: TARGET, severity: "medium", file: FILE, lineRange: [29, 36] }],
			forbiddenFindingRanges: [{ criterionId: TARGET, file: FILE, lineRange: [91, 97] }],
		});

		assert.equal(evaluateReport(fixture, reportWith([finding(35)])).passed, true);
	});

	it("fails when an expected criterion produced no finding", () => {
		const evaluation = evaluateReport(fixtureWith(), reportWith([]));

		assert.equal(evaluation.passed, false);
		assert.deepEqual(evaluation.missing, [TARGET]);
	});

	it("fails when a forbidden criterion produced a finding", () => {
		const fixture = fixtureWith({ expectedCriteria: [], forbiddenCriteria: [TARGET] });
		const evaluation = evaluateReport(fixture, reportWith([finding(35)]));

		assert.equal(evaluation.passed, false);
		assert.deepEqual(evaluation.unexpected, [TARGET]);
	});

	it("reads not-applicable from criteria rather than findings", () => {
		const fixture = fixtureWith({ expectedCriteria: [], expectedNotApplicable: [TARGET] });

		assert.equal(evaluateReport(fixture, reportWith([], [TARGET])).passed, true);
		// Scored as applicable with no finding against it: `findings` alone cannot tell these apart.
		assert.equal(evaluateReport(fixture, reportWith([])).passed, false);
	});

	it("fails a criterion the fixture plants a defect against that came back not applicable", () => {
		// The escape-hatch guard. Without it, `applicable: false` is the cheapest way for a model to
		// avoid a hard judgement, and every fixture's expectedCriteria silently weakens.
		const evaluation = evaluateReport(fixtureWith(), reportWith([finding(35)], [TARGET]));

		assert.equal(evaluation.passed, false);
		assert.deepEqual(evaluation.dodged, [TARGET]);
	});

	it("fails on a finding inside a forbidden range even when everything else matches", () => {
		const fixture = fixtureWith({
			forbiddenFindingRanges: [{ criterionId: TARGET, file: FILE, lineRange: [91, 97] }],
		});

		assert.equal(evaluateReport(fixture, reportWith([finding(93)])).passed, false);
	});
});

describe("loadExpectations", () => {
	it("accepts the shipped fixtures file", () => {
		const fixtures = loadExpectations();

		assert.equal(fixtures.length, 4);
		assert.deepEqual(
			fixtures.map((fixture) => fixture.name).sort(),
			["assertion-free-tests", "clean-diff", "sample-diff", "sample-diff-broken"],
		);
	});

	it("rejects a mistyped key instead of silently skipping the assertion", () => {
		const dir = mkdtempSync(join(tmpdir(), "verify-expectations-"));
		const path = join(dir, "expectations.json");
		writeFileSync(
			path,
			JSON.stringify({
				fixtures: [
					{
						name: "x",
						patch: "x.patch",
						expectedCriteria: [],
						forbiddenCriteria: [],
						expectedFinding: [{ criterionId: TARGET, severity: "medium", file: FILE, lineRange: [1, 2] }],
					},
				],
			}),
			"utf8",
		);

		try {
			assert.throws(() => loadExpectations(path), /expectedFinding/);
		} finally {
			rmSync(dir, { recursive: true, force: true });
		}
	});

	it("rejects overlapping ranges, the precondition greedy matching depends on", () => {
		const dir = mkdtempSync(join(tmpdir(), "verify-expectations-"));
		const path = join(dir, "expectations.json");
		writeFileSync(
			path,
			JSON.stringify({
				fixtures: [
					{
						name: "x",
						patch: "x.patch",
						expectedCriteria: [],
						forbiddenCriteria: [],
						expectedFindings: [
							{ criterionId: TARGET, severity: "medium", file: FILE, lineRange: [29, 36] },
							{ criterionId: TARGET, severity: "medium", file: FILE, lineRange: [36, 41] },
						],
					},
				],
			}),
			"utf8",
		);

		try {
			assert.throws(() => loadExpectations(path), /must not overlap/);
		} finally {
			rmSync(dir, { recursive: true, force: true });
		}
	});

	it("rejects a patch filename that would escape the fixtures directory", () => {
		const dir = mkdtempSync(join(tmpdir(), "verify-expectations-"));
		const path = join(dir, "expectations.json");
		writeFileSync(
			path,
			JSON.stringify({
				fixtures: [
					{
						name: "x",
						patch: "../../../etc/passwd",
						expectedCriteria: [],
						forbiddenCriteria: [],
					},
				],
			}),
			"utf8",
		);

		try {
			assert.throws(() => loadExpectations(path), /patch must be/);
		} finally {
			rmSync(dir, { recursive: true, force: true });
		}
	});

	it("rejects a backwards line range", () => {
		const dir = mkdtempSync(join(tmpdir(), "verify-expectations-"));
		const path = join(dir, "expectations.json");
		writeFileSync(
			path,
			JSON.stringify({
				fixtures: [
					{
						name: "x",
						patch: "x.patch",
						expectedCriteria: [],
						forbiddenCriteria: [],
						expectedFindings: [{ criterionId: TARGET, severity: "medium", file: FILE, lineRange: [90, 81] }],
					},
				],
			}),
			"utf8",
		);

		try {
			assert.throws(() => loadExpectations(path), /start <= end/);
		} finally {
			rmSync(dir, { recursive: true, force: true });
		}
	});
});

/**
 * `promptfoo/tests.yaml` deliberately restates `fixtures/expectations.json` rather than importing
 * it, because promptfoo needs the lists interpolated into rubric text. That was two flat string
 * arrays once; it is now three nested structures maintained by hand in two syntaxes, and a drift
 * would surface as a promptfoo failure with no obvious cause — after a paid run.
 */
describe("tests.yaml mirrors expectations.json", () => {
	const TESTS_YAML = fileURLToPath(new URL("../promptfoo/tests.yaml", import.meta.url));

	type PromptfooCase = { description?: string; vars: { fixture: Record<string, unknown> } };

	const cases = loadYaml(readFileSync(TESTS_YAML, "utf8")) as PromptfooCase[];
	const fixtures = loadExpectations();

	it("declares one case per fixture, each carrying a single object var", () => {
		// The var-expansion trap: promptfoo turns a top-level array var into one case per element,
		// so four fixtures silently becoming nineteen cases is the failure to catch here.
		assert.equal(cases.length, fixtures.length);
		for (const testCase of cases) {
			assert.deepEqual(Object.keys(testCase.vars), ["fixture"]);
			assert.equal(Array.isArray(testCase.vars.fixture), false);
		}
	});

	it("agrees with expectations.json on every list, for every fixture", () => {
		for (const testCase of cases) {
			const fixture = fixtures.find((entry) => entry.patch === testCase.vars.fixture["patch"]);
			assert.ok(fixture, `no expectations.json entry for ${String(testCase.vars.fixture["patch"])}`);

			for (const key of ["expectedCriteria", "forbiddenCriteria", "expectedNotApplicable"]) {
				assert.deepEqual(
					testCase.vars.fixture[key] ?? [],
					fixture[key as "expectedCriteria"] ?? [],
					`${fixture.name}.${key} drifted`,
				);
			}

			// `note` is documentation and lives only in the JSON, so compare the matched fields.
			const strip = (entries: unknown): unknown =>
				((entries ?? []) as Record<string, unknown>[]).map(
					({ criterionId, severity, file, lineRange }) => ({ criterionId, severity, file, lineRange }),
				);
			for (const key of ["expectedFindings", "forbiddenFindingRanges"]) {
				assert.deepEqual(
					strip(testCase.vars.fixture[key]),
					strip(fixture[key as "expectedFindings"]),
					`${fixture.name}.${key} drifted`,
				);
			}
		}
	});
});

describe("parseArtifactsDir", () => {
	it("returns undefined when the flag is absent", () => {
		assert.equal(parseArtifactsDir([]), undefined);
	});

	it("accepts both --flag value and --flag=value", () => {
		const base = resolve(tmpdir(), "verify-artifacts-parse");

		assert.equal(parseArtifactsDir(["--artifacts-dir", join(base, "a")]), join(base, "a"));
		assert.equal(parseArtifactsDir([`--artifacts-dir=${join(base, "b")}`]), join(base, "b"));
	});

	it("touches no filesystem, so an invalid expectations file leaves no directory behind", () => {
		const absent = join(tmpdir(), `verify-artifacts-untouched-${process.pid}`);

		assert.equal(parseArtifactsDir(["--artifacts-dir", absent]), absent);
		assert.equal(existsSync(absent), false);
	});

	it("rejects an unknown argument rather than silently retaining nothing", () => {
		assert.throws(() => parseArtifactsDir(["--artifact-dir", "/tmp/x"]), /Unknown argument/);
	});

	it("rejects the flag with no path", () => {
		assert.throws(() => parseArtifactsDir(["--artifacts-dir"]), /requires a path/);
	});
});

describe("prepareArtifactsDir", () => {
	it("creates a destination that does not exist yet", () => {
		const base = mkdtempSync(join(tmpdir(), "verify-artifacts-"));
		const target = join(base, "nested", "run");

		try {
			prepareArtifactsDir(target);
			assert.equal(existsSync(target), true);
		} finally {
			rmSync(base, { recursive: true, force: true });
		}
	});

	it("refuses a destination that already holds a previous run", () => {
		const base = mkdtempSync(join(tmpdir(), "verify-artifacts-"));
		mkdirSync(join(base, "clean-diff"));

		try {
			assert.throws(() => prepareArtifactsDir(base), /not empty/);
		} finally {
			rmSync(base, { recursive: true, force: true });
		}
	});

	it("refuses a regular file with a shaped message rather than a bare ENOTDIR", () => {
		const base = mkdtempSync(join(tmpdir(), "verify-artifacts-"));
		const file = join(base, "not-a-dir");
		writeFileSync(file, "x", "utf8");

		try {
			assert.throws(
				() => prepareArtifactsDir(file),
				(error: Error) =>
					/is not a directory/.test(error.message) && !/ENOTDIR|scandir/.test(error.message),
			);
		} finally {
			rmSync(base, { recursive: true, force: true });
		}
	});
});
