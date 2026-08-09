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
 */
export const PLANTED_DEFECT_MAX_SCORE = 5;
export const UNVIOLATED_CRITERION_MIN_SCORE = 6;

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

	const scoreById = new Map(verdict.criteria.map((entry) => [entry.id, entry.score]));
	const fixture = context?.vars?.fixture;
	const expected = fixture?.expectedCriteria;
	const forbidden = fixture?.forbiddenCriteria;

	// Loud rather than silent: if promptfoo expanded the vars (see tests.yaml's trap comment), these
	// arrive as bare strings, and iterating one walks it character by character — every lookup misses
	// and the whole suite fails for a reason that looks like a review defect.
	if (!Array.isArray(expected) || !Array.isArray(forbidden)) {
		return {
			pass: false,
			score: 0,
			reason:
				"vars.fixture.expectedCriteria/forbiddenCriteria are not arrays — the test vars were " +
				"expanded or misshapen, so this assertion cannot judge the output.",
		};
	}

	const problems = [];

	for (const id of expected) {
		const score = scoreById.get(id);
		if (score === undefined) {
			problems.push(`${id} was not scored at all`);
		} else if (score > PLANTED_DEFECT_MAX_SCORE) {
			problems.push(`${id} scored ${score}, above the ${PLANTED_DEFECT_MAX_SCORE} ceiling for a criterion this fixture plants a defect against`);
		}
	}

	// The false-positive control: a criterion the fixture guarantees is unviolated should not be
	// scored down. Without this the suite would reward a reviewer that scores everything low.
	for (const id of forbidden) {
		const score = scoreById.get(id);
		if (score !== undefined && score < UNVIOLATED_CRITERION_MIN_SCORE) {
			problems.push(`${id} scored ${score}, below the ${UNVIOLATED_CRITERION_MIN_SCORE} floor for a criterion this fixture does not violate`);
		}
	}

	return problems.length === 0
		? { pass: true, score: 1, reason: "Criterion scores agree with the fixture's planted defects." }
		: { pass: false, score: 0, reason: problems.join("; ") };
}
