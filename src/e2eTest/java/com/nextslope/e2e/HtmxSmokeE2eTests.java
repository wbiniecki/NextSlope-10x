package com.nextslope.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
 * Browser-driven HTMX smoke suite (test-plan §3 Phase 3 / §6.6): the one tier that can see
 * client-side behavior MockMvc structurally cannot — the recommend fragment swap and the
 * visited-toggle swap plus its custom {@code htmx:afterSwap} row-highlight listener.
 *
 * <p>One chained journey against the real app (random port, default profile: in-memory H2 +
 * Flyway + 150-resort seed): form login → save preference profile → recommend (3 cards swapped
 * in place) → visited toggle on/off (button swap + row highlight). A {@code window.__e2eMarker}
 * set before each HTMX interaction proves the swaps happen in place — a full page reload would
 * wipe {@code window}, so a surviving marker is the reload-free proof.
 *
 * <p>Playwright auto-waiting assertions absorb the swap latency the assertions observe; the one
 * deliberate condition-based wait is the {@code htmx:afterSettle} guard ({@link #awaitNextAfterSettle})
 * against the listener-rebind race on a freshly swapped button — still no sleeps or arbitrary
 * timeouts. The transient spinner visibility is deliberately NOT asserted (in-memory H2 answers in
 * milliseconds — the #1 flake risk); only the indicator's structural wiring is.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HtmxSmokeE2eTests {
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
	private User adminUser;
	private Playwright playwright;
	private Browser browser;
	private BrowserContext context;
	private Page page;
	private boolean traceRunning;

	@BeforeAll
	void setUp() {
		user = userRepository.save(UserFixtures.userA(passwordEncoder));
		adminUser = userRepository.save(UserFixtures.admin(passwordEncoder));
		playwright = Playwright.create();
		browser = playwright.chromium().launch();
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
			// same JVM would lose the recommend candidate set. Runs even if the Playwright
			// close throws, so the seeded user never leaks into a shared JVM-cached context.
			if (user != null) {
				deleteUserData(user);
			}
			if (adminUser != null) {
				deleteUserData(adminUser);
			}
		}
	}

	@Test
	void chainedHtmxJourney() {
		try {
			startTraceCapture();
			logInThroughRealForm();
			savePreferenceProfileThroughRealForm();
			recommendSwapsThreeCardsInPlace();
			visitedToggleSwapsAndHighlightsRowInPlace();
		} catch (RuntimeException failure) {
			captureFailureDiagnostics("chainedHtmxJourney", failure);
			throw failure;
		} catch (AssertionError failure) {
			captureFailureDiagnostics("chainedHtmxJourney", failure);
			throw failure;
		} finally {
			stopTraceWithoutSaving();
		}
	}

	@Test
	void adminActiveToggleSwapsAndRestylesRowInPlace() {
		try {
			startTraceCapture();
			logInThroughRealForm(UserFixtures.ADMIN_EMAIL, UserFixtures.ADMIN_PASSWORD);
			adminResortActiveToggleSwapsAndRestylesRowInPlace();
		} catch (RuntimeException failure) {
			captureFailureDiagnostics("adminActiveToggleSwapsAndRestylesRowInPlace", failure);
			throw failure;
		} catch (AssertionError failure) {
			captureFailureDiagnostics("adminActiveToggleSwapsAndRestylesRowInPlace", failure);
			throw failure;
		} finally {
			stopTraceWithoutSaving();
		}
	}

	/**
	 * Real form login: Thymeleaf's th:action auto-injects the hidden _csrf input server-side,
	 * so a real browser submission must authenticate without any test-side CSRF plumbing —
	 * this is the first proof of that path (MockMvc tests all inject .with(csrf())).
	 */
	private void logInThroughRealForm() {
		logInThroughRealForm(UserFixtures.USER_A_EMAIL, UserFixtures.USER_A_PASSWORD);
	}

	private void logInThroughRealForm(String email, String password) {
		ensureSignedOut();
		page.navigate("/login");
		page.fill("input[name='username']", email);
		page.fill("input[name='password']", password);
		page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign in")).click();

		// SecurityConfig: defaultSuccessUrl("/", true).
		assertThat(page).hasURL("http://localhost:" + port + "/");
	}

	/**
	 * Saves the profile through the real /profile form using its pre-selected defaults
	 * (intermediate / balanced / revisit-okay / any region) — enough for /recommend to take
	 * the three-cards branch instead of the no-profile prompt.
	 */
	private void savePreferenceProfileThroughRealForm() {
		page.navigate("/profile");
		page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save profile")).click();

		// ProfileController redirects to /resorts with the profileSaved flash on success.
		assertThat(page).hasURL("http://localhost:" + port + "/resorts");
		assertThat(page.locator(".alert-success")).containsText("Your profile has been saved");
	}

	private void recommendSwapsThreeCardsInPlace() {
		awaitHtmxReady();
		Locator recommendButton = page.getByRole(AriaRole.BUTTON,
				new Page.GetByRoleOptions().setName("Recommend resorts"));

		// Structural indicator wiring only — never the spinner's transient visibility.
		assertThat(recommendButton).hasAttribute("hx-indicator", "#recommend-indicator");
		Locator indicator = page.locator("#recommend-indicator");
		assertThat(indicator).hasCount(1);
		assertThat(indicator).hasClass(Pattern.compile("\\bhtmx-indicator\\b"));

		setReloadMarker();
		recommendButton.click();

		Locator cards = page.locator("#recommend-results .row .col");
		assertThat(cards).hasCount(3);
		for (int i = 0; i < 3; i++) {
			assertThat(cards.nth(i).locator("h2.card-title")).hasText(Pattern.compile("\\S"));
			assertThat(cards.nth(i).locator("p.card-text.fst-italic")).hasText(Pattern.compile("\\S"));
		}
		assertReloadMarkerSurvived("recommend swap");
	}

	private void visitedToggleSwapsAndHighlightsRowInPlace() {
		Locator firstRow = page.locator("tr[id^='resort-row-']").first();
		String rowId = firstRow.getAttribute("id");
		Locator toggle = firstRow.locator("button.visited-toggle");

		assertThat(toggle).hasAttribute("data-visited", "false");
		assertThat(page.locator("#" + rowId + ".table-active")).hasCount(0);

		setReloadMarker();
		int settleCountBeforeToggleOn = currentAfterSettleCount();
		toggle.click();
		awaitNextAfterSettle(settleCountBeforeToggleOn);

		// hx-swap="outerHTML" replaces the button fragment; the locator re-resolves to it.
		assertThat(toggle).hasAttribute("data-visited", "true");
		assertThat(toggle).hasText("Visited ✓");
		// Row highlight is applied by the custom htmx:afterSwap listener in layout.html —
		// one of two browser-only afterSwap branches covered in this class (visited + admin active-toggle).
		assertThat(page.locator("#" + rowId + ".table-active")).hasCount(1);
		assertReloadMarkerSurvived("visited toggle on");

		toggle.click();

		assertThat(toggle).hasAttribute("data-visited", "false");
		assertThat(toggle).hasText("Mark visited");
		assertThat(page.locator("#" + rowId + ".table-active")).hasCount(0);
		assertReloadMarkerSurvived("visited toggle off");
	}

	private void adminResortActiveToggleSwapsAndRestylesRowInPlace() {
		page.navigate("/admin/resorts");
		assertThat(page).hasURL("http://localhost:" + port + "/admin/resorts");
		awaitHtmxReady();

		Locator firstRow = page.locator("tr[id^='admin-resort-row-']").first();
		String rowId = firstRow.getAttribute("id");
		Locator toggle = firstRow.locator("button.active-toggle");
		String initialActive = toggle.getAttribute("data-active");

		assertTrue(rowId != null && !rowId.isBlank(), "expected an admin resort row id");
		assertTrue("true".equals(initialActive) || "false".equals(initialActive),
				"expected active-toggle data-active to be true/false but was '" + initialActive + "'");

		setReloadMarker();
		int settleCountBeforeToggle = currentAfterSettleCount();
		toggle.click();
		awaitNextAfterSettle(settleCountBeforeToggle);

		// hx-swap="outerHTML" replaces only the button fragment. The row class must be reconciled
		// by layout.html's htmx:afterSwap active-toggle branch from the swapped button's data-active.
		String swappedActive = toggle.getAttribute("data-active");
		assertTrue(!initialActive.equals(swappedActive),
				"expected active-toggle data-active to flip after in-place outerHTML swap");
		boolean shouldBeInactive = "false".equals(swappedActive);
		assertThat(page.locator("#" + rowId + ".table-secondary")).hasCount(shouldBeInactive ? 1 : 0);
		assertReloadMarkerSurvived("admin active toggle");

		// Toggle back: restores the shared seed data (tearDown never deletes resorts, so a
		// deactivated resort would otherwise leak into the recommend candidate pool for the
		// rest of the JVM) and proves the afterSwap branch also removes the class again.
		int settleCountBeforeToggleBack = currentAfterSettleCount();
		toggle.click();
		awaitNextAfterSettle(settleCountBeforeToggleBack);

		assertThat(toggle).hasAttribute("data-active", initialActive);
		boolean originallyInactive = "false".equals(initialActive);
		assertThat(page.locator("#" + rowId + ".table-secondary")).hasCount(originallyInactive ? 1 : 0);
		assertReloadMarkerSurvived("admin active toggle back");
	}

	/**
	 * The HTMX runtime loads from a CDN script at the bottom of the body; a click that lands
	 * before it has initialized would silently do nothing (hx-post is inert until processed).
	 * Condition-based wait, not a sleep — resolves as soon as htmx is on the window.
	 */
	private void awaitHtmxReady() {
		page.waitForFunction("() => window.htmx !== undefined");
	}

	/**
	 * PER_CLASS lifecycle shares one browser context (cookies/auth) across the two @Test
	 * methods, so each login must first drop whatever session the previous test left behind.
	 */
	private void ensureSignedOut() {
		page.navigate("/");
		Locator signOutButton = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign out"));
		if (signOutButton.count() > 0) {
			signOutButton.click();
			assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign out"))).hasCount(0);
		}
	}

	private void setReloadMarker() {
		page.evaluate("window.__e2eMarker = true");
	}

	/**
	 * Reads the {@code htmx:afterSettle} counter (installed via addInitScript in setUp) —
	 * the pre-click baseline for {@link #awaitNextAfterSettle}.
	 */
	private int currentAfterSettleCount() {
		return ((Number) page.evaluate("Number(window.__e2eAfterSettleCount || 0)")).intValue();
	}

	/**
	 * htmx 2.0.4 rebinds the swapped-in button's click handler as a settle task (processNode
	 * runs in doSettle — after {@code htmx:afterSwap} but before {@code htmx:afterSettle}), so
	 * a click landing in that window is a silent no-op. Condition-based wait, not a sleep —
	 * resolves once the counter passes the pre-click baseline, i.e. the next settle completed.
	 */
	private void awaitNextAfterSettle(int baselineCount) {
		page.waitForFunction("() => Number(window.__e2eAfterSettleCount || 0) > " + baselineCount);
	}

	private void assertReloadMarkerSurvived(String interaction) {
		assertTrue(Boolean.TRUE.equals(page.evaluate("window.__e2eMarker === true")),
				"window.__e2eMarker was wiped after '" + interaction
						+ "' — the page fully reloaded instead of swapping in place");
	}

	/**
	 * Playwright trace + screenshot on failure only: the passing path keeps no artifact writes,
	 * while CI triage gets concrete browser evidence (trace zip + PNG) instead of assertion text.
	 * Screenshot and trace-save are guarded independently so one capture failing (suppressed
	 * onto the original failure) never costs the other artifact.
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
