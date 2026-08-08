/**
 * Writes `verdictJsonSchema` to a gitignored file so promptfoo's `is-json` assertion can reference
 * it with `file://`.
 *
 * Generated on every `npm run promptfoo` rather than checked in: `src/schema.ts` is the single
 * source of truth, and its `draft-07` emission is guarded by a dedicated regression test (trap 1 in
 * this package's AGENTS.md). A committed second copy would escape that guard and drift silently as
 * the schema evolves.
 */
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { fileURLToPath } from "node:url";

import { verdictJsonSchema } from "../src/schema.ts";

const OUTPUT_PATH = fileURLToPath(
	new URL("../promptfoo/generated/verdict.schema.json", import.meta.url),
);

mkdirSync(dirname(OUTPUT_PATH), { recursive: true });
writeFileSync(OUTPUT_PATH, `${JSON.stringify(verdictJsonSchema, null, 2)}\n`, "utf8");

console.log(`Wrote ${OUTPUT_PATH}`);
