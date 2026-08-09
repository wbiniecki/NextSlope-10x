/**
 * promptfoo custom provider wrapping the real review agent.
 *
 * The point of it: promptfoo's native `anthropic:` provider measures the raw model, while the thing
 * that actually reviews pull requests is a Claude Agent SDK session with a restricted tool set, a
 * turn budget, and a scoped read root. Comparing the two only means something if this side is the
 * production path, so the session options below mirror `src/cli.ts` exactly — same `cwd`, and
 * `model` / `maxTurns` / `maxBudgetUsd` left unset so `agent.ts`'s defaults apply, matching the
 * workflow's "pass no overrides" posture.
 *
 * `cwd` is the one that would go wrong quietly: `agent.ts` omits the option entirely when it is
 * `undefined`, so forgetting it here hands the eval agent the whole repository instead of `src/`,
 * and the eval would be measuring something other than what ships.
 *
 * Module loading: promptfoo imports this file with plain Node, which has no TypeScript loader, so
 * the `.ts` imports below would throw ERR_UNKNOWN_FILE_EXTENSION on their own. The working form is
 * the `promptfoo` npm script's `NODE_OPTIONS=--import tsx/esm`, which installs the same loader the
 * rest of this package runs under. Keep this file `.js`: promptfoo resolves `file://` providers
 * itself, and a `.ts` provider would depend on promptfoo's bundled transpiler rather than ours.
 */
import { runStructuredSession } from "../src/agent.ts";
import { REVIEW_ROOT } from "../src/cli.ts";
import { verdictJsonSchema, verdictSchema } from "../src/schema.ts";

export default class ReviewAgentProvider {
	id() {
		return "nextslope:review-agent";
	}

	async callApi(prompt) {
		const result = await runStructuredSession({
			prompt,
			jsonSchema: verdictJsonSchema,
			validate: (raw) => {
				const parsed = verdictSchema.safeParse(raw);
				return parsed.success
					? { ok: true, value: parsed.data }
					: {
							ok: false,
							error: parsed.error.issues
								.map((issue) => `${issue.path.map(String).join(".") || "<root>"}: ${issue.message}`)
								.join("; "),
						};
			},
			cwd: REVIEW_ROOT,
		});

		if (!result.ok) {
			return { error: `${result.kind}: ${result.diagnostic}` };
		}

		// The raw JSON string, not the object: `is-json` validates a string against the schema, and
		// the javascript assertion parses it back. Cost and turns come straight off the session
		// result — review.json carries no run provenance, so this is the only place they surface.
		return {
			output: JSON.stringify(result.value),
			cost: result.totalCostUsd,
			metadata: {
				numTurns: result.numTurns,
				resolvedModel: result.session.resolvedModel,
			},
		};
	}
}
