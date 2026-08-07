import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
	CRITERION_IDS,
	DRAFT_07_SCHEMA_URI,
	SEVERITIES,
	reviewReportSchema,
	verdictJsonSchema,
	verdictSchema,
	type Verdict,
} from "../src/schema.ts";

function wellFormedVerdict(): Verdict {
	return {
		criteria: CRITERION_IDS.map((id, index) => ({
			id,
			score: index + 5,
			justification: `Scored from the diff for ${id}.`,
		})),
		findings: [
			{
				file: "src/main/resources/db/migration/V3__create_preference_profiles.sql",
				line: 4,
				criterionId: "flyway-forward-only",
				severity: "critical",
				message: "Edits an already-applied migration instead of adding a new V6__ file.",
			},
		],
	};
}

describe("verdictJsonSchema", () => {
	// The regression guard for the trap. Zod's target type ends in `({} & string)`, so `"draft-7"`
	// — the literal the official docs page shows — typechecks and silently emits no `$schema` at
	// all. Only a runtime assertion can catch it.
	it("declares draft-07, the only draft the SDK accepts", () => {
		assert.equal(verdictJsonSchema.$schema, DRAFT_07_SCHEMA_URI);
	});

	it("describes an object requiring both criteria and findings", () => {
		assert.equal(verdictJsonSchema.type, "object");
		assert.deepEqual(verdictJsonSchema.required, ["criteria", "findings"]);
	});
});

describe("verdictSchema", () => {
	it("accepts a well-formed verdict", () => {
		assert.equal(verdictSchema.safeParse(wellFormedVerdict()).success, true);
	});

	it("accepts a verdict with no findings", () => {
		const clean = { ...wellFormedVerdict(), findings: [] };
		assert.equal(verdictSchema.safeParse(clean).success, true);
	});

	it("rejects a verdict scoring fewer than every criterion", () => {
		const partial = wellFormedVerdict();
		partial.criteria = partial.criteria.slice(1);
		assert.equal(verdictSchema.safeParse(partial).success, false);
	});

	// The array length alone would let this through, and nothing downstream would notice: the
	// markdown table would repeat one criterion and silently drop four, while `passed` and the exit
	// code stayed normal.
	it("rejects a verdict that scores one criterion several times", () => {
		const duplicated = wellFormedVerdict();
		const first = duplicated.criteria[0];
		assert.ok(first !== undefined);
		duplicated.criteria = duplicated.criteria.map(() => ({ ...first }));

		assert.equal(duplicated.criteria.length, CRITERION_IDS.length);
		assert.equal(verdictSchema.safeParse(duplicated).success, false);
	});

	it("rejects an unknown criterion id", () => {
		const verdict = wellFormedVerdict();
		const raw = JSON.parse(JSON.stringify(verdict));
		raw.criteria[0].id = "no-such-criterion";
		assert.equal(verdictSchema.safeParse(raw).success, false);
	});

	it("rejects a non-integer or out-of-range score", () => {
		for (const score of [0, 11, 7.5]) {
			const raw = JSON.parse(JSON.stringify(wellFormedVerdict()));
			raw.criteria[0].score = score;
			assert.equal(
				verdictSchema.safeParse(raw).success,
				false,
				`score ${score} should be rejected`,
			);
		}
	});

	it("rejects an unknown finding severity", () => {
		const raw = JSON.parse(JSON.stringify(wellFormedVerdict()));
		raw.findings[0].severity = "blocker";
		assert.equal(verdictSchema.safeParse(raw).success, false);
	});

	it("rejects a finding with no file anchor", () => {
		const raw = JSON.parse(JSON.stringify(wellFormedVerdict()));
		raw.findings[0].file = "";
		assert.equal(verdictSchema.safeParse(raw).success, false);
	});

	// The gate is computed, never reported. A model that volunteers `passed: true` must not be able
	// to smuggle it into `review.json`, so the parse strips it rather than carrying it through.
	it("strips a model-supplied gate field instead of carrying it through", () => {
		const raw = JSON.parse(JSON.stringify(wellFormedVerdict()));
		raw.passed = true;
		const parsed = verdictSchema.safeParse(raw);

		assert.ok(parsed.success);
		assert.equal("passed" in parsed.data, false);
	});
});

describe("reviewReportSchema", () => {
	it("accepts a verdict enriched with the computed gate", () => {
		const report = { ...wellFormedVerdict(), passed: false, reasons: ["1 critical finding"] };
		assert.equal(reviewReportSchema.safeParse(report).success, true);
	});

	it("rejects a report missing its computed gate fields", () => {
		assert.equal(reviewReportSchema.safeParse(wellFormedVerdict()).success, false);
	});

	it("rejects a report whose passed field is not a boolean", () => {
		const report = { ...wellFormedVerdict(), passed: "false", reasons: [] };
		assert.equal(reviewReportSchema.safeParse(report).success, false);
	});
});

describe("criterion and severity vocabularies", () => {
	it("enumerates five distinct criteria", () => {
		assert.equal(CRITERION_IDS.length, 5);
		assert.equal(new Set(CRITERION_IDS).size, 5);
	});

	// `verdict.ts` compares severities by index against `--fail-on`, so the order is behavior.
	it("orders severities ascending", () => {
		assert.deepEqual([...SEVERITIES], ["low", "medium", "high", "critical"]);
	});
});
