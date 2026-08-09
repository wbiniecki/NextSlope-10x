/**
 * The review contract, in three layers.
 *
 * - `verdictSchema` validates what the model is asked to produce.
 * - `verdictJsonSchema` is that same shape as a draft-07 JSON Schema, handed to the SDK's
 *   `outputFormat`.
 * - `reviewReportSchema` validates `review.json`, which is `verdictSchema` plus the gate fields
 *   this package computes rather than asks for.
 *
 * The split matters because the model reports facts (per-criterion scores and findings) while the
 * pass/fail decision is deterministic and lives in `verdict.ts`. Retightening the gate later is
 * then a constant edit rather than a prompt change with its own regression risk.
 *
 * `review.json` and the process exit codes are a cross-change contract consumed by Linear 10X-19
 * (`ci-cd-code-review`); changing either shape breaks that consumer.
 */
import { z } from "zod";

/**
 * Criterion identifiers, kept in lockstep with the `id` of each criterion in
 * `prompts/criteria.md`. A unit test asserts both directions of that correspondence, because a
 * drifted id would silently make a criterion unscoreable.
 */
export const CRITERION_IDS = [
	"flyway-forward-only",
	"ddl-auto-validate",
	"constructor-injection",
	"access-control-scoping",
	"e2e-conventions",
] as const;

/** Ascending order is load-bearing: `verdict.ts` compares by index against `--fail-on`. */
export const SEVERITIES = ["low", "medium", "high", "critical"] as const;

export type CriterionId = (typeof CRITERION_IDS)[number];
export type Severity = (typeof SEVERITIES)[number];

/**
 * Deliberately no format refinements (`.email()` and friends) anywhere in this file: the SDK
 * passes `format` through as an annotation without enforcing it, so a refinement here would
 * validate on the way out of `safeParse` but not on the way out of the model, which reads as
 * stronger validation than it is.
 */
export const criterionScoreSchema = z.object({
	id: z.enum(CRITERION_IDS).describe("Identifier of the criterion being scored."),
	applicable: z
		.boolean()
		.describe(
			"False only when the diff contains nothing this criterion governs. A criterion that " +
				"governs anything the diff touches is scored normally, even when unviolated.",
		),
	// No refinement ties `score` to `applicable`. A refinement would not survive into
	// `verdictJsonSchema`, so the model would never see the constraint and it would surface only
	// as a failed review at the `cli.ts` parse boundary — a run that cost money and produced
	// nothing. The score stays a plain 1..10 integer and is simply meaningless when not applicable.
	score: z
		.int()
		.min(1)
		.max(10)
		.describe(
			"1 = severe non-compliance, 10 = full compliance. Diagnostic only, and carries no " +
				"meaning when applicable is false.",
		),
	justification: z
		.string()
		.min(1)
		.describe("Why this score, citing what the diff does or does not do."),
});

export const findingSchema = z.object({
	file: z.string().min(1).describe("Repository-relative path, exactly as it appears in the diff."),
	line: z.int().min(1).describe("Line number in the post-change file, drawn from the diff hunk."),
	criterionId: z.enum(CRITERION_IDS).describe("The criterion this finding violates."),
	severity: z.enum(SEVERITIES).describe("Impact of the violation."),
	message: z.string().min(1).describe("What is wrong and what the diff should do instead."),
});

/**
 * What the model returns. There is no overall verdict field on purpose — asking the model to
 * decide pass/fail would make the gate probabilistic.
 */
export const verdictSchema = z.object({
	criteria: z
		.array(criterionScoreSchema)
		.length(CRITERION_IDS.length)
		// Length alone would accept five copies of one criterion, and the failure would be silent:
		// `render.ts` orders by `CRITERION_IDS.indexOf`, so the table would repeat one row and drop
		// four while `passed` and the exit code looked entirely normal. The refinement does not
		// survive into `verdictJsonSchema` — it belongs at the `safeParse` boundary in `cli.ts`,
		// which is where model output is re-checked anyway.
		.refine(
			(criteria) => new Set(criteria.map((entry) => entry.id)).size === CRITERION_IDS.length,
			{ error: "each criterion must be scored exactly once" },
		)
		.describe("One entry per criterion, scored even when the criterion is not violated."),
	findings: z
		.array(findingSchema)
		.describe("Every violation found, anchored to a file and line from the diff. May be empty."),
});

/**
 * What `review.json` contains: the model's report plus the deterministic gate. A consumer reads
 * `passed` rather than re-deriving it from `findings` and a threshold it would have to duplicate.
 */
export const reviewReportSchema = verdictSchema.extend({
	passed: z.boolean().describe("Deterministic gate result computed from findings and --fail-on."),
	reasons: z
		.array(z.string())
		.describe("Human-readable reasons the gate failed. Empty when passed is true."),
});

export type CriterionScore = z.infer<typeof criterionScoreSchema>;
export type Finding = z.infer<typeof findingSchema>;
export type Verdict = z.infer<typeof verdictSchema>;
export type ReviewReport = z.infer<typeof reviewReportSchema>;

/** The `$schema` value the SDK requires, asserted by a unit test rather than trusted. */
export const DRAFT_07_SCHEMA_URI = "http://json-schema.org/draft-07/schema#";

/**
 * The trap. The target literal is `"draft-07"`, not the `"draft-7"` the official Zod docs page
 * shows, and Zod's target type ends in `({} & string)` — so a typo typechecks cleanly and silently
 * emits no `$schema` at all. From Claude Code 2.1.205 onward the SDK fails the run at startup on an
 * invalid schema rather than ignoring `outputFormat` in silence, but it still neither strips nor
 * converts `$schema` for us. `test/schema.test.ts` is the regression guard, and it needs no API
 * call.
 */
export const verdictJsonSchema = z.toJSONSchema(verdictSchema, { target: "draft-07" });
