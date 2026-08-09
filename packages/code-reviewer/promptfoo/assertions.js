/**
 * The score-shape assertion. `scripts/verify.ts` already checks *which* criteria get findings; this
 * checks that the diagnostic scores agree with the findings, which is the part a reviewer can get
 * wrong while still looking right — reporting the planted defect and then scoring the criterion 9/10
 * makes the score column noise.
 *
 * Both thresholds are diagnostic and deliberately named, mirroring `DEFAULT_FAIL_ON`'s role in
 * `verdict.ts`: they gate this eval's opinion of review quality, never the CLI's own pass/fail.
 * Neither is calibrated against real PR history — they encode "a violated criterion should not score
 * in the top half, and an unviolated one should not score in the bottom half", nothing stronger.
 *
 * Both thresholds skip criteria the model marked `applicable: false`, because that score is declared
 * meaningless by the schema. Judging it would fail the suite for a non-defect: on a diff that touches
 * no test source, `test-verifies-behavior` is legitimately inapplicable, and whatever integer it
 * carries is filler.
 */
export const PLANTED_DEFECT_MAX_SCORE = 5;
export const UNVIOLATED_CRITERION_MIN_SCORE = 6;

function inRange(line, range) {
	return Array.isArray(range) && line >= range[0] && line <= range[1];
}

function describeRange(target) {
	return `${target.criterionId} @ ${target.file}:${target.lineRange?.[0]}-${target.lineRange?.[1]}`;
}

export default function assertCriterionScores(output, context) {
	let verdict;
	try {
		verdict = typeof output === "string" ? JSON.parse(output) : output;
	} catch {
		return { pass: false, score: 0, reason: "Output was not JSON, so no scores could be read." };
	}

	if (!Array.isArray(verdict?.criteria)) {
		return { pass: false, score: 0, reason: "Output carried no criteria array." };
	}

	// The whole entry, not just the score: `applicable` decides whether the score means anything.
	const criterionById = new Map(verdict.criteria.map((entry) => [entry.id, entry]));
	const findings = Array.isArray(verdict?.findings) ? verdict.findings : [];
	const fixture = context?.vars?.fixture;
	const expected = fixture?.expectedCriteria;
	const forbidden = fixture?.forbiddenCriteria;
	const expectedNotApplicable = fixture?.expectedNotApplicable ?? [];
	const expectedFindings = fixture?.expectedFindings ?? [];
	const forbiddenFindingRanges = fixture?.forbiddenFindingRanges ?? [];

	// Loud rather than silent: if promptfoo expanded the vars (see tests.yaml's trap comment), these
	// arrive as bare strings, and iterating one walks it character by character — every lookup misses
	// and the whole suite fails for a reason that looks like a review defect.
	if (
		!Array.isArray(expected) ||
		!Array.isArray(forbidden) ||
		!Array.isArray(expectedNotApplicable) ||
		!Array.isArray(expectedFindings) ||
		!Array.isArray(forbiddenFindingRanges)
	) {
		return {
			pass: false,
			score: 0,
			reason:
				"vars.fixture's expectation lists are not all arrays — the test vars were expanded or " +
				"misshapen, so this assertion cannot judge the output.",
		};
	}

	const problems = [];

	for (const id of expected) {
		const entry = criterionById.get(id);
		if (entry === undefined) {
			problems.push(`${id} was not scored at all`);
			continue;
		}
		// The escape-hatch guard. A criterion this fixture plants a defect against cannot honestly be
		// inapplicable, so `applicable: false` here is the model dodging a judgement rather than
		// reporting one — and it would silently exempt the entry from the ceiling below.
		if (entry.applicable === false) {
			problems.push(`${id} came back not applicable on a fixture that plants a defect against it`);
		} else if (typeof entry.score !== "number") {
			// Explicit, because the comparisons below would pass vacuously: `undefined > 5` and
			// `undefined < 6` are both false. Unlike verify.ts this reads raw model output, not
			// zod-validated output, so a missing score is reachable here.
			problems.push(`${id} carries no numeric score`);
		} else if (entry.score > PLANTED_DEFECT_MAX_SCORE) {
			problems.push(`${id} scored ${entry.score}, above the ${PLANTED_DEFECT_MAX_SCORE} ceiling for a criterion this fixture plants a defect against`);
		}
	}

	// The false-positive control: a criterion the fixture guarantees is unviolated should not be
	// scored down. Without this the suite would reward a reviewer that scores everything low.
	for (const id of forbidden) {
		const entry = criterionById.get(id);
		if (entry === undefined || entry.applicable === false) {
			continue;
		}
		if (typeof entry.score !== "number") {
			problems.push(`${id} carries no numeric score`);
		} else if (entry.score < UNVIOLATED_CRITERION_MIN_SCORE) {
			problems.push(`${id} scored ${entry.score}, below the ${UNVIOLATED_CRITERION_MIN_SCORE} floor for a criterion this fixture does not violate`);
		}
	}

	for (const id of expectedNotApplicable) {
		const entry = criterionById.get(id);
		if (entry === undefined) {
			problems.push(`${id} was not scored at all`);
		} else if (entry.applicable !== false) {
			problems.push(`${id} was reported applicable on a fixture that gives it nothing to judge`);
		}
	}

	// One-to-one so several expected findings cannot all be satisfied by a single reported one. The
	// fixtures keep their ranges non-overlapping, which makes greedy consumption exact. Ranges rather
	// than message text: a probabilistic reviewer rewords a finding every run, but the line it
	// anchors to is fixed by the static patch.
	const unconsumed = new Set(findings.keys());
	for (const wanted of expectedFindings) {
		const hit = [...unconsumed].find((index) => {
			const finding = findings[index];
			return (
				finding?.criterionId === wanted.criterionId &&
				finding?.severity === wanted.severity &&
				finding?.file === wanted.file &&
				inRange(finding?.line, wanted.lineRange)
			);
		});

		if (hit === undefined) {
			problems.push(`no ${wanted.severity} finding for ${describeRange(wanted)}`);
		} else {
			unconsumed.delete(hit);
		}
	}

	for (const banned of forbiddenFindingRanges) {
		for (const finding of findings) {
			if (
				finding?.criterionId === banned.criterionId &&
				finding?.file === banned.file &&
				inRange(finding?.line, banned.lineRange)
			) {
				problems.push(`${describeRange(banned)} is a false positive, reported at line ${finding.line}`);
			}
		}
	}

	return problems.length === 0
		? { pass: true, score: 1, reason: "Criterion scores agree with the fixture's planted defects." }
		: { pass: false, score: 0, reason: problems.join("; ") };
}
