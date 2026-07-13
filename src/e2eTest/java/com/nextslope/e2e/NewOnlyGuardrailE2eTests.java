package com.nextslope.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.SelectOption;
import com.nextslope.profile.PreferenceProfileRepository;
import com.nextslope.support.UserFixtures;
import com.nextslope.user.User;
import com.nextslope.user.UserRepository;
import com.nextslope.visited.VisitedResortRepository;

/**
 * Browser-level guardrail test for test-plan §2 Risk #3 ("Visited / new-only guardrail broken"):
 * a {@code new-only} user must never receive a resort they marked visited in their top three.
 *
 * <p>Unlike the Phase-2 server-side suites (which prove the hard filter in isolation), this test
 * protects the <em>flow-level</em> guardrail across every real boundary the risk hides behind:
 * form login (CSRF), profile persistence through the real {@code /profile} form, visited-list
 * persistence through the real HTMX toggle, the recommender hard filter, and the HTMX-rendered
 * result cards — no mocks (no external services are involved).
 *
 * <p>The oracle is derived from the test's own inputs, never from the recommender's output being
 * trusted twice for the same claim: the test first captures the recommender's #1 pick under
 * {@code revisit-okay} defaults, marks exactly that resort visited, then (a) re-recommends under
 * {@code revisit-okay} and asserts the pick still appears — the control proving that being visited
 * alone does not remove a resort — and (b) switches novelty to "New resorts only" and asserts the
 * visited resort vanishes from the three cards. Only the novelty setting changed between (a) and
 * (b), so an exclusion in (b) is attributable to the hard filter, not coincidence. If the filter
 * stops excluding visited resorts, assertion (b) fails (verified by deliberate break).
 *
 * <p>Modeled on {@link HtmxSmokeE2eTests} (the §6.6 seed exemplar): PER_CLASS lifecycle, the
 * {@code htmx:afterSettle} counter for fresh-swap detection when re-clicking Recommend on the same
 * page (the control re-renders identical content, so auto-waiting alone can't see the new swap),
 * failure-only trace/screenshot diagnostics, and FK-safe teardown (visited rows → preference
 * profile → user; seeded resorts are never deleted). Seeds {@code userB} — a different fixture
 * email than the smoke suite's {@code userA} — per the test-plan §6.6 watch item on fixture
 * isolation across e2e classes sharing the named in-memory H2.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NewOnlyGuardrailE2eTests {
	private static final Path E2E_DIAGNOSTICS_DIR = Path.of("build", "reports", "e2e-diagnostics");

	@LocalServerPort
	private int port;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PreferenceProfileRepository preferenceProfileRepository;

	@Autowired
	private VisitedResortRepository visitedResortRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private User user;
	private Playwright playwright;
	private BrowserContext context;
	private Page page;
	private boolean traceRunning;

	@BeforeAll
	void setUp() {
		user = userRepository.save(UserFixtures.userB(passwordEncoder));
		playwright = Playwright.create();
		Browser browser = playwright.chromium().launch();
		context = browser.newContext(new Browser.NewContextOptions().setBaseURL("http://localhost:" + port));
		page = context.newPage();
		page.addInitScript("""
				window.__e2eAfterSettleCount = 0;
				document.addEventListener('htmx:afterSettle', function () {
					window.__e2eAfterSettleCount += 1;
				});
				""");
	}

	@AfterAll
	void tearDown() {
		try {
			stopTraceWithoutSaving();
			if (playwright != null) {
				playwright.close();
			}
		} finally {
			// FK-safe cleanup order: visited rows → preference profile → user. Never delete
			// resorts — the seed loader only refills an empty table, so later contexts in the
			// same JVM would lose the recommend candidate set.
			if (user != null) {
				deleteUserData(user);
			}
		}
	}

	@Test
	@DisplayName("Risk #3: a new-only user never sees a resort they marked visited in their top three")
	void newOnlyUserNeverSeesVisitedResortInTopThree() {
		try {
			startTraceCapture();

			// Log in through the real form (server-rendered CSRF hidden input, no test-side plumbing).
			logInThroughRealForm();

			// Save the profile with its pre-selected defaults — novelty stays revisit-okay, so the
			// visited list plays no part in the first recommendation.
			saveProfileThroughRealForm(null);

			// First recommendation: capture the recommender's own #1 pick as the test's oracle.
			// Ranking is deterministic (score desc, then country/name/id), so this pick is stable.
			List<String> baselineTopThree = recommendAndReadCardTitles();
			String visitedResortName = baselineTopThree.get(0);

			// Mark exactly that resort visited through the real HTMX toggle on /resorts.
			markResortVisitedThroughRealToggle(visitedResortName);

			// CONTROL: still revisit-okay — the visited resort must STILL be recommended. This pins
			// the later exclusion on the novelty setting rather than on coincidence or on the act of
			// marking a resort visited.
			List<String> controlTopThree = recommendAndReadCardTitles();
			assertTrue(controlTopThree.contains(visitedResortName),
					"control failed: under revisit-okay the visited resort '" + visitedResortName
							+ "' should still be in the top three " + controlTopThree
							+ " — the test's premise no longer holds");

			// Flip only the novelty axis to "New resorts only" through the real /profile form.
			saveProfileThroughRealForm("New resorts only");

			// THE GUARDRAIL: the visited resort must be gone from the three rendered cards.
			List<String> newOnlyTopThree = recommendAndReadCardTitles();
			assertFalse(newOnlyTopThree.contains(visitedResortName),
					"new-only guardrail broken: '" + visitedResortName + "' was marked visited but still "
							+ "appears in the top three " + newOnlyTopThree
							+ " — the hard filter is not excluding visited resorts");
		} catch (RuntimeException | AssertionError failure) {
			captureFailureDiagnostics("newOnlyUserNeverSeesVisitedResortInTopThree", failure);
			throw failure;
		} finally {
			stopTraceWithoutSaving();
		}
	}

	private void logInThroughRealForm() {
		page.navigate("/login");
		page.fill("input[name='username']", UserFixtures.USER_B_EMAIL);
		page.fill("input[name='password']", UserFixtures.USER_B_PASSWORD);
		page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign in")).click();

		// SecurityConfig: defaultSuccessUrl("/", true).
		assertThat(page).hasURL("http://localhost:" + port + "/");
	}

	/**
	 * Saves the profile through the real {@code /profile} form. With {@code noveltyLabel == null}
	 * the form's pre-selected defaults are kept (revisit-okay on first save, the stored value on
	 * later saves); otherwise only the novelty select is changed before submitting.
	 */
	private void saveProfileThroughRealForm(String noveltyLabel) {
		page.navigate("/profile");
		if (noveltyLabel != null) {
			page.getByLabel("Novelty preference").selectOption(new SelectOption().setLabel(noveltyLabel));
		}
		page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save profile")).click();

		// ProfileController redirects to /resorts with the profileSaved flash on success.
		assertThat(page).hasURL("http://localhost:" + port + "/resorts");
		assertThat(page.locator(".alert-success")).containsText("Your profile has been saved");
	}

	/**
	 * Clicks "Recommend resorts" on the current /resorts page and returns the three card titles in
	 * rank order. Waits on the {@code htmx:afterSettle} counter rather than on card count: the
	 * control step re-renders content identical to the previous swap, so only the settle counter
	 * proves a fresh response actually replaced the container.
	 */
	private List<String> recommendAndReadCardTitles() {
		awaitHtmxReady();
		int settleCountBeforeRecommend = currentAfterSettleCount();
		page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Recommend resorts")).click();
		awaitNextAfterSettle(settleCountBeforeRecommend);

		// Structural locator used for assertion only (per the E2E rules) — this is the swap output.
		Locator cards = page.locator("#recommend-results h2.card-title");
		assertThat(cards).hasCount(3);
		return cards.allTextContents();
	}

	/**
	 * Finds the resort's row on /resorts by its name link and clicks its real visited toggle. The
	 * swapped-in "Visited ✓" state (fresh from the server after the POST persisted) is the proof
	 * the visited row was written — no repository shortcut.
	 */
	private void markResortVisitedThroughRealToggle(String resortName) {
		Locator row = page.locator("tr[id^='resort-row-']").filter(new Locator.FilterOptions()
				.setHas(page.getByRole(AriaRole.LINK,
						new Page.GetByRoleOptions().setName(resortName).setExact(true))));
		// Guard: the name-based oracle requires the resort name to be unique in the seed table.
		assertThat(row).hasCount(1);

		row.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Mark visited")).click();

		// hx-swap="outerHTML" replaces the button; the swapped-in state proves persistence.
		Locator swappedToggle = row.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Visited ✓"));
		assertThat(swappedToggle).isVisible();
		assertEquals("true", swappedToggle.getAttribute("data-visited"),
				"visited toggle swapped in without data-visited=true — the visited write may not have landed");
	}

	/**
	 * The HTMX runtime loads from a CDN script at the bottom of the body; a click that lands
	 * before it has initialized would silently do nothing. Condition-based wait, not a sleep.
	 */
	private void awaitHtmxReady() {
		page.waitForFunction("() => window.htmx !== undefined");
	}

	private int currentAfterSettleCount() {
		return ((Number) page.evaluate("Number(window.__e2eAfterSettleCount || 0)")).intValue();
	}

	private void awaitNextAfterSettle(int baselineCount) {
		page.waitForFunction("() => Number(window.__e2eAfterSettleCount || 0) > " + baselineCount);
	}

	/**
	 * Playwright trace + screenshot on failure only (same discipline as the seed exemplar): the
	 * passing path keeps no artifact writes; CI triage gets concrete browser evidence.
	 */
	private void captureFailureDiagnostics(String testName, Throwable failure) {
		String prefix = testName + "-" + System.currentTimeMillis();
		try {
			Files.createDirectories(E2E_DIAGNOSTICS_DIR);
		} catch (Exception diagnosticsFailure) {
			failure.addSuppressed(diagnosticsFailure);
			return;
		}
		try {
			if (page != null) {
				page.screenshot(new Page.ScreenshotOptions()
						.setPath(E2E_DIAGNOSTICS_DIR.resolve(prefix + ".png")).setFullPage(true));
			}
		} catch (Exception screenshotFailure) {
			failure.addSuppressed(screenshotFailure);
		}
		try {
			if (traceRunning && context != null) {
				context.tracing().stop(new Tracing.StopOptions()
						.setPath(E2E_DIAGNOSTICS_DIR.resolve(prefix + ".zip")));
				traceRunning = false;
			}
		} catch (Exception traceFailure) {
			failure.addSuppressed(traceFailure);
		}
	}

	private void startTraceCapture() {
		if (context == null) {
			return;
		}
		context.tracing().start(new Tracing.StartOptions()
				.setScreenshots(true)
				.setSnapshots(true)
				.setSources(false));
		traceRunning = true;
	}

	private void stopTraceWithoutSaving() {
		if (!traceRunning || context == null) {
			return;
		}
		context.tracing().stop(new Tracing.StopOptions());
		traceRunning = false;
	}

	private void deleteUserData(User testUser) {
		Long userId = testUser.getId();
		visitedResortRepository.findResortIdsByUserId(userId)
				.forEach(resortId -> visitedResortRepository.deleteByUserIdAndResortId(userId, resortId));
		preferenceProfileRepository.findByUserId(userId).ifPresent(preferenceProfileRepository::delete);
		userRepository.deleteById(userId);
	}
}
