/**
 * The vendor boundary. This is the only module under `src/` that imports
 * `@anthropic-ai/claude-agent-sdk` (`scripts/smoke.ts` is the one deliberate exception outside
 * `src/`, since its whole job is to prove the SDK connects). Everything else — criteria, prompt
 * assembly, the verdict gate, rendering, exit codes, the CLI — stays vendor-agnostic and unit
 * testable with no network call, so swapping providers costs this file and nothing else.
 *
 * The module knows about prompts and JSON Schemas. It does not know what a review is: the caller
 * supplies the schema and a validator, and gets back typed data or a typed failure.
 */
import { query, type ModelUsage } from "@anthropic-ai/claude-agent-sdk";

/** Read-only by construction. `Read`/`Glob`/`Grep` cover optional repo cross-referencing. */
export const READ_ONLY_TOOLS = ["Read", "Glob", "Grep"];

/** Removed from the model's context as defense in depth. Bare names, not patterns. */
export const MUTATION_TOOLS = ["Write", "Edit", "Bash"];

export const DEFAULT_MODEL = "claude-sonnet-5";
export const DEFAULT_MAX_TURNS = 3;
export const DEFAULT_MAX_BUDGET_USD = 0.5;

/**
 * Replaces the default Claude Code system prompt entirely — a plain string leaves no way to append
 * to it (`append` exists only on the `{ type: 'preset' }` form). Kept generic on purpose: review
 * wording belongs to `prompt.ts`, not to the vendor boundary.
 */
export const DEFAULT_SYSTEM_PROMPT =
	"You are a non-interactive analysis agent. Work only from the material in the user message, " +
	"and return a single result that conforms to the supplied JSON schema.";

/** Result of the caller's validator, which turns the SDK's `unknown` payload into typed data. */
export type ValidationOutcome<T> = { ok: true; value: T } | { ok: false; error: string };

/**
 * Distinct failure shapes, deliberately not collapsed into one catch-all — the subtype is exactly
 * the diagnostic that makes a failed run debuggable.
 *
 * `cli.ts` (Phase 4) maps `startup_failure` to exit `1` (no valid session result was ever produced)
 * and every other kind to exit `2` (a session ran but produced nothing usable).
 */
export type AgentFailureKind =
	/** The session never started: bad or missing credential, unresolved native binary, network. */
	| "startup_failure"
	/** `error_max_turns` — the turn budget was spent before a final answer. */
	| "max_turns"
	/** `error_max_budget_usd` — the cost ceiling was hit. */
	| "max_budget_usd"
	/** `error_max_structured_output_retries` — the model never matched the schema. */
	| "structured_output_retries_exhausted"
	/** `error_during_execution` — the session failed for some other reason. */
	| "execution_error"
	/** `subtype: "success"` with no `structured_output`. Documented, and it means failure. */
	| "missing_structured_output"
	/** `structured_output` was present but the caller's validator rejected it. */
	| "invalid_structured_output"
	/** The stream ended after startup without ever yielding a terminal result. */
	| "no_result";

/** Session facts off the `system`/`init` message. `resolvedModel` is what actually ran. */
export type SessionMetadata = {
	claudeCodeVersion: string;
	resolvedModel: string;
	availableTools: string[];
	permissionMode: string;
};

export type StructuredSessionSuccess<T> = {
	ok: true;
	value: T;
	/** Cumulative estimate for the call. Read this one, not a sum across results. */
	totalCostUsd: number;
	numTurns: number;
	/**
	 * Per-model totals across the whole query pipeline. `usage` covers only the main agent loop, so
	 * `modelUsage` is the field to log for cost accounting.
	 */
	modelUsage: Record<string, ModelUsage>;
	session: SessionMetadata;
};

export type StructuredSessionFailure = {
	ok: false;
	kind: AgentFailureKind;
	/** Human-readable, and for the limit kinds it names the configured limit that was hit. */
	diagnostic: string;
	totalCostUsd?: number;
	numTurns?: number;
	/** Absent when the failure happened before `system`/`init` arrived. */
	session?: SessionMetadata;
};

export type StructuredSessionResult<T> = StructuredSessionSuccess<T> | StructuredSessionFailure;

export type StructuredSessionRequest<T> = {
	prompt: string;
	/** A draft-07 JSON Schema. See `schema.ts` for why the draft matters. */
	jsonSchema: Record<string, unknown>;
	validate: (raw: unknown) => ValidationOutcome<T>;
	systemPrompt?: string;
	model?: string;
	maxTurns?: number;
	maxBudgetUsd?: number;
	/** Repo root, enabling the optional read-only cross-referencing the tools allow. */
	cwd?: string;
};

/**
 * Runs one single-shot session against `prompt`, constrained to `jsonSchema`, and returns validated
 * data or a typed failure.
 */
export async function runStructuredSession<T>(
	request: StructuredSessionRequest<T>,
): Promise<StructuredSessionResult<T>> {
	const {
		prompt,
		jsonSchema,
		validate,
		systemPrompt = DEFAULT_SYSTEM_PROMPT,
		model = DEFAULT_MODEL,
		maxTurns = DEFAULT_MAX_TURNS,
		maxBudgetUsd = DEFAULT_MAX_BUDGET_USD,
		cwd,
	} = request;

	let session: SessionMetadata | undefined;

	try {
		for await (const message of query({
			prompt,
			options: {
				outputFormat: { type: "json_schema", schema: jsonSchema },
				// Mandatory. The default loads `user`, `project`, and `local`, and this repo has a
				// `.claude/settings.local.json` — omitting this would make a verdict depend on
				// whose machine produced it.
				settingSources: [],
				// The restrictive allowlist. `allowedTools` alone would not restrict anything.
				tools: READ_ONLY_TOOLS,
				allowedTools: READ_ONLY_TOOLS,
				disallowedTools: MUTATION_TOOLS,
				permissionMode: "dontAsk",
				systemPrompt,
				model,
				maxTurns,
				maxBudgetUsd,
				...(cwd === undefined ? {} : { cwd }),
				// `options.env` is deliberately never set: it replaces the subprocess environment
				// rather than merging into it, which would drop PATH and the credential.
			},
		})) {
			if (message.type === "system" && message.subtype === "init") {
				session = {
					claudeCodeVersion: message.claude_code_version,
					resolvedModel: message.model,
					availableTools: message.tools,
					permissionMode: message.permissionMode,
				};
				continue;
			}

			if (message.type !== "result") {
				continue;
			}

			if (message.subtype !== "success") {
				return {
					ok: false,
					kind: failureKindForSubtype(message.subtype),
					diagnostic: describeErrorResult(message.subtype, message.errors, {
						maxTurns,
						maxBudgetUsd,
						terminalReason: message.terminal_reason,
					}),
					totalCostUsd: message.total_cost_usd,
					numTurns: message.num_turns,
					...(session === undefined ? {} : { session }),
				};
			}

			const cost = { totalCostUsd: message.total_cost_usd, numTurns: message.num_turns };

			// Docs-confirmed shape: a run can succeed and still carry no structured output. Treating
			// it as success would hand the caller an empty review that looks like a clean one.
			if (message.structured_output === undefined) {
				return {
					ok: false,
					kind: "missing_structured_output",
					diagnostic:
						"Session succeeded but carried no structured_output. Check that the schema " +
						`passed to outputFormat is valid draft-07. Model reply was: ${truncate(message.result)}`,
					...cost,
					...(session === undefined ? {} : { session }),
				};
			}

			// `structured_output` is typed `unknown` even though the SDK validated it, so it is
			// re-validated here at the boundary with the caller's own schema.
			const validated = validate(message.structured_output);
			if (!validated.ok) {
				return {
					ok: false,
					kind: "invalid_structured_output",
					diagnostic: `structured_output failed caller validation: ${validated.error}`,
					...cost,
					...(session === undefined ? {} : { session }),
				};
			}

			if (session === undefined) {
				return {
					ok: false,
					kind: "no_result",
					diagnostic: "A terminal result arrived without a preceding system/init message.",
					...cost,
				};
			}

			return {
				ok: true,
				value: validated.value,
				modelUsage: message.modelUsage,
				session,
				...cost,
			};
		}
	} catch (error) {
		// A single-shot `query()` throws after yielding an error result, but the subtype branch above
		// returns first, so anything landing here is a throw with no terminal result behind it.
		const detail = error instanceof Error ? error.message : String(error);
		return session === undefined
			? {
					ok: false,
					kind: "startup_failure",
					diagnostic: `The session never started: ${detail}`,
				}
			: {
					ok: false,
					kind: "execution_error",
					diagnostic: `The session threw after starting: ${detail}`,
					session,
				};
	}

	// Phase 1's smoke script learned this the hard way: without an explicit guard, a stream that
	// closes after init but before a terminal result reads as success.
	return session === undefined
		? {
				ok: false,
				kind: "startup_failure",
				diagnostic: "The stream closed without a system/init message — the session never started.",
			}
		: {
				ok: false,
				kind: "no_result",
				diagnostic: "The stream closed after startup without a terminal result message.",
				session,
			};
}

type ErrorSubtype =
	| "error_during_execution"
	| "error_max_turns"
	| "error_max_budget_usd"
	| "error_max_structured_output_retries";

function failureKindForSubtype(subtype: ErrorSubtype): AgentFailureKind {
	switch (subtype) {
		case "error_max_turns":
			return "max_turns";
		case "error_max_budget_usd":
			return "max_budget_usd";
		case "error_max_structured_output_retries":
			return "structured_output_retries_exhausted";
		case "error_during_execution":
			return "execution_error";
	}
}

function describeErrorResult(
	subtype: ErrorSubtype,
	errors: string[],
	limits: { maxTurns: number; maxBudgetUsd: number; terminalReason?: string },
): string {
	const suffix = [
		errors.length > 0 ? errors.join(" | ") : undefined,
		limits.terminalReason === undefined ? undefined : `terminal_reason: ${limits.terminalReason}`,
	]
		.filter((part): part is string => part !== undefined)
		.join(" — ");
	const tail = suffix.length > 0 ? ` (${suffix})` : "";

	switch (subtype) {
		case "error_max_turns":
			// Named explicitly because with read-only repo access this is a likely outcome, and a
			// generic "run failed" would send the reader looking in the wrong place.
			return `Exhausted the ${limits.maxTurns}-turn budget before producing a result${tail}`;
		case "error_max_budget_usd":
			return `Hit the configured $${limits.maxBudgetUsd.toFixed(2)} budget before producing a result${tail}`;
		case "error_max_structured_output_retries":
			return `The model never produced output matching the schema; retries were exhausted${tail}`;
		case "error_during_execution":
			return `The session failed during execution${tail}`;
	}
}

function truncate(text: string, limit = 300): string {
	const collapsed = text.trim();
	return collapsed.length <= limit ? collapsed : `${collapsed.slice(0, limit)}…`;
}
