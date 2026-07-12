package com.nextslope.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
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
 * <p>Playwright auto-waiting assertions absorb all swap latency; no sleeps or manual waits.
 * The transient spinner visibility is deliberately NOT asserted (in-memory H2 answers in
 * milliseconds — the #1 flake risk); only the indicator's structural wiring is.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HtmxSmokeE2eTests {

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
	private Browser browser;
	private Page page;

	@BeforeAll
	void setUp() {
		user = userRepository.save(UserFixtures.userA(passwordEncoder));
		playwright = Playwright.create();
		browser = playwright.chromium().launch();
		page = browser.newPage(new Browser.NewPageOptions().setBaseURL("http://localhost:" + port));
	}

	@AfterAll
	void tearDown() {
		if (playwright != null) {
			playwright.close();
		}
		// FK-safe cleanup order: visited rows → preference profile → user. Never delete
		// resorts — the seed loader only refills an empty table, so later contexts in the
		// same JVM would lose the recommend candidate set.
		if (user != null) {
			Long userId = user.getId();
			visitedResortRepository.findResortIdsByUserId(userId)
					.forEach(resortId -> visitedResortRepository.deleteByUserIdAndResortId(userId, resortId));
			preferenceProfileRepository.findByUserId(userId).ifPresent(preferenceProfileRepository::delete);
			userRepository.deleteById(userId);
		}
	}

	@Test
	void chainedHtmxJourney() {
		logInThroughRealForm();
		savePreferenceProfileThroughRealForm();
		recommendSwapsThreeCardsInPlace();
		visitedToggleSwapsAndHighlightsRowInPlace();
	}

	/**
	 * Real form login: Thymeleaf's th:action auto-injects the hidden _csrf input server-side,
	 * so a real browser submission must authenticate without any test-side CSRF plumbing —
	 * this is the first proof of that path (MockMvc tests all inject .with(csrf())).
	 */
	private void logInThroughRealForm() {
		page.navigate("/login");
		page.fill("input[name='username']", UserFixtures.USER_A_EMAIL);
		page.fill("input[name='password']", UserFixtures.USER_A_PASSWORD);
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
		assertEquals(0, page.locator("#" + rowId + ".table-active").count(),
				"row must start un-highlighted");

		setReloadMarker();
		toggle.click();

		// hx-swap="outerHTML" replaces the button fragment; the locator re-resolves to it.
		assertThat(toggle).hasAttribute("data-visited", "true");
		assertThat(toggle).hasText("Visited ✓");
		// Row highlight is applied by the custom htmx:afterSwap listener in layout.html —
		// the single most browser-only behavior in the app.
		assertThat(page.locator("#" + rowId + ".table-active")).hasCount(1);
		assertReloadMarkerSurvived("visited toggle on");

		toggle.click();

		assertThat(toggle).hasAttribute("data-visited", "false");
		assertThat(toggle).hasText("Mark visited");
		assertThat(page.locator("#" + rowId + ".table-active")).hasCount(0);
		assertReloadMarkerSurvived("visited toggle off");
	}

	/**
	 * The HTMX runtime loads from a CDN script at the bottom of the body; a click that lands
	 * before it has initialized would silently do nothing (hx-post is inert until processed).
	 * Condition-based wait, not a sleep — resolves as soon as htmx is on the window.
	 */
	private void awaitHtmxReady() {
		page.waitForFunction("() => window.htmx !== undefined");
	}

	private void setReloadMarker() {
		page.evaluate("window.__e2eMarker = true");
	}

	private void assertReloadMarkerSurvived(String interaction) {
		assertTrue(Boolean.TRUE.equals(page.evaluate("window.__e2eMarker === true")),
				"window.__e2eMarker was wiped after '" + interaction
						+ "' — the page fully reloaded instead of swapping in place");
	}
}
