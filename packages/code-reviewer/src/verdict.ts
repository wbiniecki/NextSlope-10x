/**
 * The gate. The model reports facts — per-criterion scores and findings — and this module decides
 * pass or fail from them, deterministically. Pure: no I/O, no clock, no SDK.
 *
 * Keeping the decision here rather than in the prompt or the schema means retightening CI later is
 * a constant edit with a unit test behind it, not a prompt change carrying its own regression risk.
 *
 * Criterion scores are deliberately not consulted. They are diagnostic in this change; Linear
 * 10X-19 consumes them for promptfoo quality assertions and can calibrate a score gate separately,
 * against real PRs rather than against guesses.
 */
import { SEVERITIES, type Finding, type Severity, type Verdict } from "./schema.ts";

/** Blocks on `high` and `critical`, lets `low` and `medium` through as advisory. */
export const DEFAULT_FAIL_ON: Severity = "high";

export type Gate = {
	passed: boolean;
	/** One entry per blocking finding, ordered most severe first. Empty when `passed`. */
	reasons: string[];
};

export function isSeverity(value: string): value is Severity {
	return (SEVERITIES as readonly string[]).includes(value);
}

/** Position in the ascending `SEVERITIES` order — the comparison the threshold is built on. */
export function severityRank(severity: Severity): number {
	return SEVERITIES.indexOf(severity);
}

/** At or above the threshold blocks; strictly below it does not. */
export function isBlocking(finding: Finding, failOn: Severity): boolean {
	return severityRank(finding.severity) >= severityRank(failOn);
}

/**
 * Computes pass/fail plus the reasons a human needs to act on it.
 *
 * Reasons are sorted most-severe-first and then by location, so the same verdict always renders
 * the same way — a report that reshuffles between runs is hard to diff and easy to distrust.
 */
export function computeGate(verdict: Verdict, failOn: Severity = DEFAULT_FAIL_ON): Gate {
	const blocking = verdict.findings
		.filter((finding) => isBlocking(finding, failOn))
		.sort(
			(a, b) =>
				severityRank(b.severity) - severityRank(a.severity) ||
				a.file.localeCompare(b.file) ||
				a.line - b.line,
		);

	return {
		passed: blocking.length === 0,
		reasons: blocking.map(
			(finding) =>
				`${finding.severity}: ${finding.criterionId} at ${finding.file}:${finding.line} — ${finding.message}`,
		),
	};
}
