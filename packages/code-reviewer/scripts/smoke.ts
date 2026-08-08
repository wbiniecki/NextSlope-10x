/**
 * Connectivity proof for the Claude Agent SDK.
 *
 * Runs the smallest possible session and prints the `system`/`init` fields plus the terminal
 * result. Its whole purpose is to separate three failure modes that look identical from a
 * distance: a missing or invalid API key, an unresolved per-platform native binary, and a
 * network failure.
 *
 * Deliberately does not set `options.env` — that option replaces the subprocess environment
 * rather than merging into it, which would drop PATH and whatever credential is in scope.
 *
 * There is no `apiKey` option: the subprocess resolves its own credential, from
 * `ANTHROPIC_API_KEY` if exported or from a Claude Code CLI login otherwise. Which one won is
 * reported by `apiKeySource` below, which is the field to read when several are in scope.
 */
import { query } from "@anthropic-ai/claude-agent-sdk";

const SMOKE_PROMPT = "Reply with exactly the word: pong";

async function main(): Promise<number> {
	console.log(
		"env.ANTHROPIC_API_KEY:",
		process.env.ANTHROPIC_API_KEY ? "set" : "unset (expecting a CLI login credential)",
	);

	let sawInit = false;
	let sawResult = false;

	try {
		for await (const message of query({
			prompt: SMOKE_PROMPT,
			options: {
				settingSources: [],
				maxTurns: 1,
				permissionMode: "dontAsk",
			},
		})) {
			if (message.type === "system" && message.subtype === "init") {
				sawInit = true;
				console.log("init.claude_code_version:", message.claude_code_version);
				console.log("init.apiKeySource:", message.apiKeySource);
				console.log("init.model:", message.model);
				console.log("init.permissionMode:", message.permissionMode);
				console.log("init.tools:", message.tools.join(", "));
				continue;
			}

			if (message.type === "result") {
				console.log("result.subtype:", message.subtype);
				console.log("result.num_turns:", message.num_turns);
				console.log("result.total_cost_usd:", message.total_cost_usd);

				if (message.subtype !== "success") {
					console.error("result.errors:", message.errors.join(" | "));
					console.error("result.terminal_reason:", message.terminal_reason ?? "(none)");
					return 1;
				}

				console.log("result.result:", message.result.trim());
				sawResult = true;
			}
		}
	} catch (error) {
		console.error("Session threw:", error instanceof Error ? error.message : String(error));
		return 1;
	}

	if (!sawInit) {
		console.error("No system/init message was received — the session never started.");
		return 1;
	}

	if (!sawResult) {
		console.error("The stream ended without a terminal result — the session did not answer.");
		return 1;
	}

	return 0;
}

process.exitCode = await main();
