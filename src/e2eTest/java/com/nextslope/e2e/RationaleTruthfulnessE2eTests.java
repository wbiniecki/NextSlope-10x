package com.nextslope.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.nextslope.profile.PreferenceProfileRepository;
import com.nextslope.support.UserFixtures;
import com.nextslope.user.User;
import com.nextslope.user.UserRepository;
import com.nextslope.visited.VisitedResortRepository;

/**
 * Browser-level truthfulness test for test-plan §2 Risk #1 ("Rationale lies"): the "why this
 * matched you" line on a recommendation card must reference a preference axis the user actually
 * set — never an axis they didn't.
 *
 * <p>Flow-level scope: the full truthfulness contract (rationale ↔ real scoring reasons) lives in
 * the Phase-2 unit/integration suite; this test protects the risk across every real boundary it
 * hides behind — form login (CSRF), profile form binding + persistence through the real
 * {@code /profile} form, recommender scoring + rationale generation, and the HTMX-rendered result
 * cards — with no mocks (no external services are involved).
 *
 * <p>The oracle is derived from the test's own form input, never from the rationale generator's
 * output (that would be the oracle-problem tautology §2's Risk Response Guidance forbids): the
 * test unchecks "Any region" and selects exactly one distinctive region country through the real
 * form, then asserts every rendered card's rationale names that country. A satisfied single-country
 * region filter is a perfect-alignment axis no other axis can strictly beat, so a rationale that
 * cites an axis the user never distinctively set (or stops reflecting the set one) drops the
 * country from the text and fails the assertion — verified by deliberate break.
 *
 * <p>Modeled on {@link HtmxSmokeE2eTests} (the §6.6 seed exemplar): PER_CLASS lifecycle,
 * failure-only trace/screenshot diagnostics, and FK-safe teardown (visited rows → preference
 * profile → user; seeded resorts are never deleted). Seeds {@code userC} — a fixture email unique
 * to this class, per the test-plan §6.6 watch item on fixture isolation across e2e classes sharing
 * the named in-memory H2.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RationaleTruthfulnessE2eTests {
	private static final Path E2E_DIAGNOSTICS_DIR = Path.of("build", "reports", "e2e-diagnostics");

	/**
	 * The one distinctive axis value THE TEST sets through the real form — the input-derived
	 * oracle every rendered rationale must reference. Austria has 32 seeded resorts, comfortably
	 * above the three-survivor floor the recommender needs.
	 */
	private static final String SELECTED_REGION_COUNTRY = "Austria";

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
		user = userRepository.save(UserFixtures.userC(passwordEncoder));
		playwright = Playwright.create();
		Browser browser = playwright.chromium().launch();
		context = browser.newContext(new Browser.NewContextOptions().setBaseURL("http://localhost:" + port));
		page = context.newPage();
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
	@DisplayName("Risk #1: every rendered rationale references the region preference the user actually set")
	void rationaleReferencesTheRegionAxisTheUserActuallySet() {
		try {
			startTraceCapture();

			// Log in through the real form (server-rendered CSRF hidden input, no test-side plumbing).
			logInThroughRealForm();

			// Set the one distinctive axis through the real /profile form: uncheck "Any region"
			// (the server discards selected countries while it is checked) and select exactly one
			// country. All other axes keep the form's pre-selected defaults.
			saveProfileWithSingleRegionCountry();

			// Recommend through the real HTMX button and read the three rendered cards.
			recommendAndAwaitThreeCards();

			// THE ORACLE: each card's rationale must reference the region value the test itself
			// submitted. A rationale citing an axis the user never distinctively set (or dropping
			// the set axis) cannot contain the selected country and fails here.
			Locator rationales = page.locator("#recommend-results p.card-text.fst-italic");
			assertThat(rationales).hasCount(3);
			List<String> rationaleTexts = rationales.allTextContents();
			for (String rationale : rationaleTexts) {
				assertTrue(rationale.contains(SELECTED_REGION_COUNTRY),
						"rationale lies: '" + rationale + "' does not reference the region preference the "
								+ "user actually set ('" + SELECTED_REGION_COUNTRY + "') — it may be citing an "
								+ "axis the user never set. All rationales: " + rationaleTexts);
			}

			// Corroboration in the rendered flow: the region hard filter means every card's own
			// country label must equal the selected country — so the rationale's region claim is
			// not just present but true of the resort it decorates.
			Locator countries = page.locator("#recommend-results p.text-muted.small");
			assertThat(countries).hasCount(3);
			for (String country : countries.allTextContents()) {
				assertEquals(SELECTED_REGION_COUNTRY, country.strip(),
						"a recommended card is outside the user's selected region — the rationale's "
								+ "region claim would be untrue of the resort it decorates");
			}
		} catch (RuntimeException | AssertionError failure) {
			captureFailureDiagnostics("rationaleReferencesTheRegionAxisTheUserActuallySet", failure);
			throw failure;
		} finally {
			stopTraceWithoutSaving();
		}
	}

	private void logInThroughRealForm() {
		page.navigate("/login");
		page.fill("input[name='username']", UserFixtures.USER_C_EMAIL);
		page.fill("input[name='password']", UserFixtures.USER_C_PASSWORD);
		page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign in")).click();

		// SecurityConfig: defaultSuccessUrl("/", true).
		assertThat(page).hasURL("http://localhost:" + port + "/");
	}

	/**
	 * Saves the profile through the real {@code /profile} form with exactly one region country
	 * selected. "Any region" is unchecked explicitly (server-side normalization discards the
	 * country list while it is checked) rather than relying on the page's progressive-enhancement
	 * checkbox script.
	 */
	private void saveProfileWithSingleRegionCountry() {
		page.navigate("/profile");
		page.getByLabel("Any region (recommend from everywhere)").uncheck();
		page.getByLabel(SELECTED_REGION_COUNTRY, new Page.GetByLabelOptions().setExact(true)).check();
		page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save profile")).click();

		// ProfileController redirects to /resorts with the profileSaved flash on success.
		assertThat(page).hasURL("http://localhost:" + port + "/resorts");
		assertThat(page.locator(".alert-success")).containsText("Your profile has been saved");
	}

	/**
	 * Clicks "Recommend resorts" and waits (via auto-waiting assertions — the container starts
	 * empty, so the three cards appearing IS the fresh-swap signal) for the rendered result.
	 */
	private void recommendAndAwaitThreeCards() {
		awaitHtmxReady();
		page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Recommend resorts")).click();

		// Structural locator used for assertion only (per the E2E rules) — this is the swap output.
		assertThat(page.locator("#recommend-results h2.card-title")).hasCount(3);
	}

	/**
	 * The HTMX runtime loads from a CDN script at the bottom of the body; a click that lands
	 * before it has initialized would silently do nothing. Condition-based wait, not a sleep.
	 */
	private void awaitHtmxReady() {
		page.waitForFunction("() => window.htmx !== undefined");
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
