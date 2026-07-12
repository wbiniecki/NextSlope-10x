package com.nextslope.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.nextslope.config.SecurityConfig;
import com.nextslope.user.AccountService;
import com.nextslope.user.AppUserDetailsService;
import com.nextslope.user.CurrentUserService;
import com.nextslope.user.UserRepository;

/**
 * Controller slice for the S-07 self-delete flow. Like {@code /profile}, the routes carry no id —
 * the target is always the authenticated principal, so there is no cross-user surface to test.
 */
@WebMvcTest(controllers = AccountController.class)
@Import({SecurityConfig.class, AppUserDetailsService.class})
class AccountControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AccountService accountService;

	@MockitoBean
	private CurrentUserService currentUserService;

	@MockitoBean
	private UserRepository userRepository;

	@BeforeEach
	void mockUserExists() {
		when(userRepository.existsByEmail(anyString())).thenReturn(true);
	}

	@Test
	@WithMockUser(username = "user")
	void authenticatedGetRendersConfirmDeleteView() throws Exception {
		when(currentUserService.requireUserId(any(UserDetails.class))).thenReturn(1L);

		mockMvc.perform(get("/account/delete"))
				.andExpect(status().isOk())
				.andExpect(view().name("account/confirm-delete"));
	}

	@Test
	@WithMockUser(username = "user")
	void postDeletesAccountInvalidatesSessionAndRedirectsToDeletedBanner() throws Exception {
		when(currentUserService.requireUserId(any(UserDetails.class))).thenReturn(1L);

		// unauthenticated() proves the controller cleared the security context; the authoritative
		// end-to-end session-invalidation proof lives in AccountDeletionIntegrationTests.
		mockMvc.perform(post("/account/delete").with(csrf()))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/?deleted"))
				.andExpect(unauthenticated());

		verify(accountService).deleteAccount(1L);
	}
}
