/**
 * Prompt construction for the eval, delegating to the same `buildReviewPrompt` the CLI uses so the
 * agent provider sees a byte-identical prompt to the one that runs on pull requests — including the
 * per-run nonce on the untrusted-diff delimiters.
 *
 * Two exports because the two providers are not comparable on one prompt. The agent provider gets
 * schema-shaped output enforced by the SDK's `outputFormat`; a bare `anthropic:messages:` call has
 * no such enforcement, so without an explicit instruction the raw-model row would fail every
 * assertion on output formatting and tell us nothing about review quality.
 */
import { randomBytes } from "node:crypto";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { buildReviewPrompt } from "../src/prompt.ts";

const CRITERIA_PATH = fileURLToPath(new URL("../prompts/criteria.md", import.meta.url));

// Every var lives under `fixture` because promptfoo expands a top-level array var into separate
// test cases — see the trap comment in tests.yaml.
function reviewPrompt({ fixture }) {
	return buildReviewPrompt({
		criteriaMarkdown: readFileSync(CRITERIA_PATH, "utf8"),
		diffText: readFileSync(
			fileURLToPath(new URL(`../fixtures/${fixture.patch}`, import.meta.url)),
			"utf8",
		),
		nonce: randomBytes(12).toString("hex"),
	});
}

export function agentPrompt({ vars }) {
	return reviewPrompt(vars);
}

export function rawModelPrompt({ vars }) {
	return [
		reviewPrompt(vars),
		"",
		"Return only a single JSON object and nothing else — no prose before or after it, no markdown",
		'code fence. It must have exactly two keys: "criteria" (one entry per criterion, each with',
		'"id", a boolean "applicable", an integer "score" from 1 to 10, and a "justification") and',
		'"findings" (possibly empty, each entry with "file", an integer "line", "criterionId",',
		'"severity", and "message").',
	].join("\n");
}
