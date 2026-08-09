/**
 * The human-facing half of the output. Pure function, no I/O — `cli.ts` decides where the string
 * goes.
 *
 * The target reader is someone glancing at a pull request, so the shape is verdict first, then the
 * reasons they have to act on, then the diagnostic score table, then the findings themselves
 * grouped by file. Linear 10X-19 posts this string as a PR comment body verbatim, which is why it
 * stays plain GitHub-flavored markdown with no HTML and no emoji.
 */
import {
	CRITERION_IDS,
	type CriterionScore,
	type Finding,
	type ReviewReport,
	type Severity,
} from "./schema.ts";

export type RenderOptions = {
	/** Named in the summary line so the reader knows what "blocking" meant for this run. */
	failOn?: Severity;
};

export function renderReport(report: ReviewReport, options: RenderOptions = {}): string {
	const sections = [
		"# NextSlope code review",
		"",
		summaryLine(report, options.failOn),
		"",
		...blockingReasonsSection(report),
		...criterionScoresSection(report.criteria),
		...findingsSection(report.findings),
	];

	return `${sections.join("\n").trimEnd()}\n`;
}

function summaryLine(report: ReviewReport, failOn: Severity | undefined): string {
	const threshold = failOn === undefined ? "the configured threshold" : `\`${failOn}\``;
	const total = plural(report.findings.length, "finding");

	if (!report.passed) {
		return `**Blocked** — ${plural(report.reasons.length, "blocking finding")} at or above ${threshold}, out of ${total}.`;
	}

	if (report.findings.length > 0) {
		return `**Passed** — ${total}, all below ${threshold}.`;
	}

	// "No findings" on an all-not-applicable run would read as a clean sweep of compliant criteria
	// when in fact nothing was assessed. The bold word stays `Passed` either way: it is tied to the
	// exit code that drives the PR label, and a third word would make the comment contradict it.
	return noCriterionApplied(report.criteria)
		? "**Passed** — no criterion applied to this diff."
		: "**Passed** — no findings.";
}

function noCriterionApplied(criteria: CriterionScore[]): boolean {
	return criteria.length > 0 && criteria.every((criterion) => !criterion.applicable);
}

function blockingReasonsSection(report: ReviewReport): string[] {
	if (report.reasons.length === 0) {
		return [];
	}

	return ["## Blocking reasons", "", ...report.reasons.map((reason) => `- ${reason}`), ""];
}

function criterionScoresSection(criteria: CriterionScore[]): string[] {
	// Rendered in the canonical criterion order rather than the order the model happened to emit,
	// so two reports of the same diff are diffable line by line.
	const ordered = [...criteria].sort(
		(a, b) => CRITERION_IDS.indexOf(a.id) - CRITERION_IDS.indexOf(b.id),
	);

	return [
		"## Criterion scores",
		"",
		"| Criterion | Score | Justification |",
		"| --- | --- | --- |",
		...ordered.map(
			(criterion) =>
				`| \`${criterion.id}\` | ${scoreCell(criterion)} | ${cell(criterion.justification)} |`,
		),
		"",
		"Scores are diagnostic. Only findings at or above the fail-on severity block the change.",
		"",
	];
}

function findingsSection(findings: Finding[]): string[] {
	if (findings.length === 0) {
		return ["## Findings", "", "None.", ""];
	}

	const byFile = new Map<string, Finding[]>();
	for (const finding of findings) {
		const bucket = byFile.get(finding.file);
		if (bucket === undefined) {
			byFile.set(finding.file, [finding]);
		} else {
			bucket.push(finding);
		}
	}

	const lines = ["## Findings", ""];
	for (const file of [...byFile.keys()].sort()) {
		const forFile = (byFile.get(file) ?? []).sort((a, b) => a.line - b.line);
		lines.push(`### \`${file}\``, "");
		for (const finding of forFile) {
			lines.push(
				`- **line ${finding.line}** · ${finding.severity} · \`${finding.criterionId}\` — ${inline(finding.message)}`,
			);
		}
		lines.push("");
	}

	return lines;
}

/** An em dash rather than a number, so "not applicable" cannot be misread as full compliance. */
function scoreCell(criterion: CriterionScore): string {
	return criterion.applicable ? `${criterion.score}/10` : "—";
}

/** Model prose can contain newlines and pipes, either of which would break a table row. */
function cell(text: string): string {
	return inline(text).replaceAll("|", "\\|");
}

function inline(text: string): string {
	return text.replace(/\s*\n\s*/g, " ").trim();
}

function plural(count: number, noun: string): string {
	return `${count} ${noun}${count === 1 ? "" : "s"}`;
}
