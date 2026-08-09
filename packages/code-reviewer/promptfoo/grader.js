/**
 * The `llm-rubric` grader, routed through the Claude Agent SDK instead of promptfoo's built-in
 * graders.
 *
 * Why it exists: every built-in grader is a plain API call needing ANTHROPIC_API_KEY (or an OpenAI
 * key), and this project authenticates with a subscription OAuth token — there is no Console org to
 * mint an API key from. Without this file, `llm-rubric` is simply unavailable here, and prose
 * quality goes unmeasured: `scripts/verify.ts` matches on criterion ids only, deliberately, so
 * "the justification actually describes the defect" has no other check anywhere in this package.
 *
 * promptfoo hands a grading prompt to `callApi` and expects JSON carrying `pass`, `score`, and
 * `reason` back. The schema below forces exactly that shape, so a grader that rambles fails loudly
 * rather than being silently scored as a pass.
 *
 * Deliberately no `cwd`: unlike the review provider, the grader judges the text it is handed and has
 * no reason to go looking at the repository. Note what that does and does not buy — `agent.ts` omits
 * the option entirely when it is undefined, so the session falls back to the process cwd
 * (`packages/code-reviewer/`) while `Read`/`Glob`/`Grep` stay granted. This narrows the read root
 * away from the repo tree; it does not deny reads. If the grader ever needs to be genuinely
 * contained, that is a `tools` change, not a `cwd` one.
 */
import { z } from "zod";

import { runStructuredSession } from "../src/agent.ts";

const gradingResultSchema = z.object({
	pass: z.boolean().describe("Whether the output satisfies the rubric."),
	score: z.number().min(0).max(1).describe("0 = fails the rubric entirely, 1 = satisfies it fully."),
	reason: z.string().min(1).describe("Why, citing what the output did or did not do."),
});

// `draft-07`, never `draft-7` — see trap 1 in this package's AGENTS.md. A typo here typechecks
// cleanly and emits no $schema at all, which the SDK rejects at session startup.
const gradingResultJsonSchema = z.toJSONSchema(gradingResultSchema, { target: "draft-07" });

export default class AgentSdkGrader {
	id() {
		return "nextslope:agent-sdk-grader";
	}

	async callApi(prompt) {
		const result = await runStructuredSession({
			prompt,
			jsonSchema: gradingResultJsonSchema,
			validate: (raw) => {
				const parsed = gradingResultSchema.safeParse(raw);
				return parsed.success
					? { ok: true, value: parsed.data }
					: {
							ok: false,
							error: parsed.error.issues
								.map((issue) => `${issue.path.map(String).join(".") || "<root>"}: ${issue.message}`)
								.join("; "),
						};
			},
		});

		if (!result.ok) {
			return { error: `${result.kind}: ${result.diagnostic}` };
		}

		return {
			output: JSON.stringify(result.value),
			cost: result.totalCostUsd,
		};
	}
}
