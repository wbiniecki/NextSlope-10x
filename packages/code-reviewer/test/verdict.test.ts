import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { CRITERION_IDS, type Finding, type Severity, type Verdict } from "../src/schema.ts";
import { DEFAULT_FAIL_ON, computeGate, isBlocking, isSeverity } from "../src/verdict.ts";

function finding(severity: Severity, overrides: Partial<Finding> = {}): Finding {
	return {
		file: "src/main/java/com/nextslope/web/ProfileController.java",
		line: 42,
		criterionId: "access-control-scoping",
		severity,
		message: "Loads the profile by a path id instead of the authenticated principal.",
		...overrides,
	};
}

/** Perfect scores everywhere, so any gate movement in a test is attributable to findings alone. */
function verdictWith(findings: Finding[], score = 10): Verdict {
	return {
		criteria: CRITERION_IDS.map((id) => ({
			id,
			applicable: true,
			score,
			justification: `Nothing to flag for ${id}.`,
		})),
		findings,
	};
}

describe("computeGate", () => {
	it("passes a verdict with no findings", () => {
		assert.deepEqual(computeGate(verdictWith([]), "high"), { passed: true, reasons: [] });
	});

	it("blocks on a finding at the threshold", () => {
		const gate = computeGate(verdictWith([finding("high")]), "high");

		assert.equal(gate.passed, false);
		assert.equal(gate.reasons.length, 1);
	});

	it("blocks on a finding above the threshold", () => {
		assert.equal(computeGate(verdictWith([finding("critical")]), "high").passed, false);
	});

	it("passes findings below the threshold, reporting no reasons for them", () => {
		const gate = computeGate(verdictWith([finding("low"), finding("medium")]), "high");

		assert.deepEqual(gate, { passed: true, reasons: [] });
	});

	it("reports only the blocking findings when severities are mixed", () => {
		const gate = computeGate(
			verdictWith([finding("low"), finding("critical"), finding("medium"), finding("high")]),
			"high",
		);

		assert.equal(gate.passed, false);
		assert.equal(gate.reasons.length, 2);
		assert.ok(gate.reasons.every((reason) => !reason.startsWith("low")));
		assert.ok(gate.reasons.every((reason) => !reason.startsWith("medium")));
	});

	it("moves the same verdict from passing to blocked as the threshold tightens", () => {
		const verdict = verdictWith([finding("medium")]);

		assert.equal(computeGate(verdict, "high").passed, true);
		assert.equal(computeGate(verdict, "medium").passed, false);
		assert.equal(computeGate(verdict, "low").passed, false);
	});

	it("defaults to failing on high", () => {
		assert.equal(DEFAULT_FAIL_ON, "high");
		assert.equal(computeGate(verdictWith([finding("medium")])).passed, true);
		assert.equal(computeGate(verdictWith([finding("high")])).passed, false);
	});

	// The whole point of keeping the gate out of the schema: what the model scores is diagnostic,
	// what it locates is what blocks. A reviewer having a bad day about style cannot fail the build.
	it("ignores criterion scores entirely", () => {
		const allOnes = computeGate(verdictWith([], 1), "high");
		const allTens = computeGate(verdictWith([], 10), "high");

		assert.deepEqual(allOnes, allTens);
		assert.equal(allOnes.passed, true);
	});

	it("still blocks on a blocking finding when every criterion scores a perfect ten", () => {
		assert.equal(computeGate(verdictWith([finding("critical")], 10), "high").passed, false);
	});

	it("names the severity, criterion, and location in each reason", () => {
		const gate = computeGate(
			verdictWith([finding("critical", { file: "V3__create_preference_profiles.sql", line: 7 })]),
			"high",
		);

		assert.match(gate.reasons[0] as string, /^critical: access-control-scoping/);
		assert.match(gate.reasons[0] as string, /V3__create_preference_profiles\.sql:7/);
	});

	// A report that reshuffles between runs is hard to diff and easy to distrust.
	it("orders reasons most severe first, then by location", () => {
		const gate = computeGate(
			verdictWith([
				finding("high", { file: "b.java", line: 1 }),
				finding("critical", { file: "z.java", line: 9 }),
				finding("high", { file: "a.java", line: 5 }),
			]),
			"high",
		);

		assert.deepEqual(
			gate.reasons.map((reason) => reason.split(" at ")[1]?.split(" —")[0]),
			["z.java:9", "a.java:5", "b.java:1"],
		);
	});
});

describe("isBlocking", () => {
	it("compares by severity order, not alphabetically", () => {
		assert.equal(isBlocking(finding("critical"), "high"), true);
		assert.equal(isBlocking(finding("low"), "critical"), false);
		assert.equal(isBlocking(finding("medium"), "medium"), true);
	});
});

describe("isSeverity", () => {
	it("accepts the four known severities and nothing else", () => {
		for (const severity of ["low", "medium", "high", "critical"]) {
			assert.equal(isSeverity(severity), true);
		}
		for (const other of ["blocker", "HIGH", "", "criticall"]) {
			assert.equal(isSeverity(other), false);
		}
	});
});
