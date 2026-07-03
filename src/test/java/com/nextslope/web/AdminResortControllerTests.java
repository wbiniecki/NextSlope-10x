package com.nextslope.web;

import static com.nextslope.support.AccessControlAssertions.assertForbidden;
import static com.nextslope.support.AccessControlAssertions.assertRedirectedToLogin;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.nextslope.config.SecurityConfig;
import com.nextslope.resort.Resort;
import com.nextslope.resort.ResortService;
import com.nextslope.user.AppUserDetailsService;
import com.nextslope.user.UserRepository;

@WebMvcTest(controllers = AdminResortController.class)
@Import({SecurityConfig.class, AppUserDetailsService.class})
class AdminResortControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ResortService resortService;

	@MockitoBean
	private UserRepository userRepository;

	@BeforeEach
	void mockUserExists() {
		when(userRepository.existsByEmail(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
	}

	@Test
	void anonymousAdminResortsRedirectsToLogin() throws Exception {
		assertRedirectedToLogin(mockMvc.perform(get("/admin/resorts")));
	}

	@Test
	@WithMockUser(roles = "USER")
	void userAdminResortsReturns403() throws Exception {
		assertForbidden(mockMvc.perform(get("/admin/resorts")));
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void adminAdminResortsReturns200AndListView() throws Exception {
		when(resortService.listAll()).thenReturn(List.of(
				Resort.builder()
						.id(1L)
						.name("Sölden")
						.country("Austria")
						.active(true)
						.build()));

		mockMvc.perform(get("/admin/resorts"))
				.andExpect(status().isOk())
				.andExpect(view().name("admin/resorts/list"));
	}
}
