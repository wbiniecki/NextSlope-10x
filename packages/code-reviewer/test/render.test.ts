import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { renderReport } from "../src/render.ts";
import {
	CRITERION_IDS,
	type CriterionScore,
	type Finding,
	type ReviewReport,
} from "../src/schema.ts";
import { computeGate } from "../src/verdict.ts";

function reportWith(findings: Finding[]): ReviewReport {
	const verdict = {
		criteria: CRITERION_IDS.map((id, index) => ({
			id,
			applicable: true,
			score: 10 - index,
			justification: `Assessed ${id} from the diff.`,
		})),
		findings,
	};

	return { ...verdict, ...computeGate(verdict, "high") };
}

const BLOCKING_FINDING: Finding = {
	file: "src/main/resources/db/migration/V3__create_preference_profiles.sql",
	line: 4,
	criterionId: "flyway-forward-only",
	severity: "critical",
	message: "Edits an already-applied migration instead of adding a new V6__ file.",
};

describe("renderReport", () => {
	it("renders a clean report as passed with no findings", () => {
		const markdown = renderReport(reportWith([]), { failOn: "high" });

		assert.match(markdown, /^# NextSlope code review$/m);
		assert.match(markdown, /\*\*Passed\*\* — no findings\./);
		assert.match(markdown, /## Findings\n\nNone\./);
		assert.doesNotMatch(markdown, /## Blocking reasons/);
	});

	it("renders a blocked report with its reasons and the threshold that blocked it", () => {
		const markdown = renderReport(reportWith([BLOCKING_FINDING]), { failOn: "high" });

		assert.match(markdown, /\*\*Blocked\*\* — 1 blocking finding at or above `high`/);
		assert.match(markdown, /## Blocking reasons/);
		assert.match(markdown, /- critical: flyway-forward-only at .*V3__create_preference_profiles/);
	});

	it("reports advisory findings as passed rather than hiding them", () => {
		const advisory: Finding = { ...BLOCKING_FINDING, severity: "low" };
		const markdown = renderReport(reportWith([advisory]), { failOn: "high" });

		assert.match(markdown, /\*\*Passed\*\* — 1 finding, all below `high`\./);
		assert.match(markdown, /line 4.*low.*flyway-forward-only/);
	});

	// A criterion the diff gives nothing to judge used to be forced into a 10/10, which reads as
	// "audited and compliant" in the one place a reviewer actually looks.
	it("renders an em dash instead of a score for a not-applicable criterion", () => {
		const report = reportWith([]);
		const first = report.criteria[0] as CriterionScore;
		report.criteria[0] = { ...first, applicable: false, justification: "No test file is touched." };

		const markdown = renderReport(report, { failOn: "high" });
		const row = markdown
			.split("\n")
			.find((line) => line.startsWith(`| \`${first.id}\``)) as string;

		assert.match(row, /\| — \|/);
		assert.doesNotMatch(row, /\/10/);
		assert.ok(row.includes("No test file is touched."));
	});

	// A model can mark a criterion `applicable: false` while still reporting a finding against it.
	// The em dash must not survive that contradiction — the Blocking reasons section already names
	// the criterion as the cause, so the score row should not read as "not assessed".
	it("scores a criterion that carries a finding even when the model marked it not applicable", () => {
		const report = reportWith([BLOCKING_FINDING]);
		const scored = report.criteria.find(
			(criterion) => criterion.id === BLOCKING_FINDING.criterionId,
		) as CriterionScore;
		const index = report.criteria.indexOf(scored);
		report.criteria[index] = { ...scored, applicable: false };

		const markdown = renderReport(report, { failOn: "high" });
		const row = markdown
			.split("\n")
			.find((line) => line.startsWith(`| \`${scored.id}\``)) as string;

		assert.doesNotMatch(row, /\| — \|/);
		assert.match(row, /\/10/);
	});

	it("does not claim a clean sweep when no criterion applied at all", () => {
		const report = reportWith([]);
		report.criteria = report.criteria.map((criterion) => ({ ...criterion, applicable: false }));

		const markdown = renderReport(report, { failOn: "high" });

		assert.match(markdown, /\*\*Passed\*\* — no criterion applied to this diff\./);
		assert.doesNotMatch(markdown, /no findings/);
	});

	it("scores every criterion in the canonical order regardless of model ordering", () => {
		const report = reportWith([]);
		report.criteria.reverse();
		const markdown = renderReport(report, { failOn: "high" });

		const positions = CRITERION_IDS.map((id) => markdown.indexOf(`| \`${id}\``));
		assert.ok(positions.every((position) => position !== -1));
		assert.deepEqual([...positions].sort((a, b) => a - b), positions);
	});

	it("groups findings by file with line anchors", () => {
		const markdown = renderReport(
			reportWith([
				{ ...BLOCKING_FINDING, file: "b.java", line: 20 },
				{ ...BLOCKING_FINDING, file: "a.java", line: 9 },
				{ ...BLOCKING_FINDING, file: "a.java", line: 2 },
			]),
			{ failOn: "high" },
		);

		assert.equal(markdown.match(/^### `a\.java`$/gm)?.length, 1);
		assert.equal(markdown.match(/^### `b\.java`$/gm)?.length, 1);
		assert.ok(markdown.indexOf("### `a.java`") < markdown.indexOf("### `b.java`"));
		assert.ok(markdown.indexOf("**line 2**") < markdown.indexOf("**line 9**"));
	});

	// Model prose is free text. A pipe or a newline in a justification would silently break the
	// table it lands in, which is the kind of defect nobody notices until it is in a PR comment.
	it("keeps a table row intact when prose contains pipes or newlines", () => {
		const report = reportWith([]);
		report.criteria[0] = {
			id: CRITERION_IDS[0],
			applicable: true,
			score: 3,
			justification: "Uses SERIAL | AUTO_INCREMENT\ninstead of the portable idiom.",
		};

		const markdown = renderReport(report, { failOn: "high" });
		const row = markdown
			.split("\n")
			.find((line) => line.startsWith(`| \`${CRITERION_IDS[0]}\``)) as string;

		assert.ok(row.includes("\\|"));
		assert.ok(row.includes("AUTO_INCREMENT instead of the portable idiom."));
		assert.equal(row.split(/(?<!\\)\|/).length - 1, 4);
	});

	it("ends with exactly one trailing newline so it pastes cleanly as a comment body", () => {
		const markdown = renderReport(reportWith([BLOCKING_FINDING]), { failOn: "high" });

		assert.ok(markdown.endsWith("\n"));
		assert.ok(!markdown.endsWith("\n\n"));
	});

	it("falls back to generic threshold wording when no fail-on is supplied", () => {
		const markdown = renderReport(reportWith([]));

		assert.match(markdown, /\*\*Passed\*\* — no findings\./);
		assert.doesNotMatch(markdown, /undefined/);
	});
});
