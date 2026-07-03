package com.nextslope.web;

import static com.nextslope.support.AccessControlAssertions.assertForbidden;
import static com.nextslope.support.AccessControlAssertions.assertRedirectedToLogin;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.nextslope.config.SecurityConfig;
import com.nextslope.resort.ConcurrentResortUpdateException;
import com.nextslope.resort.DuplicateExternalIdException;
import com.nextslope.resort.Resort;
import com.nextslope.resort.ResortForm;
import com.nextslope.resort.ResortNotFoundException;
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

	@Test
	@WithMockUser(roles = "ADMIN")
	void adminNewResortFormReturnsEmptyForm() throws Exception {
		mockMvc.perform(get("/admin/resorts/new"))
				.andExpect(status().isOk())
				.andExpect(view().name("admin/resorts/form"))
				.andExpect(model().attributeExists("resortForm"))
				.andExpect(model().attribute("formAction", "/admin/resorts"));
	}

	@Test
	@WithMockUser(roles = "USER")
	void userNewResortFormReturns403() throws Exception {
		assertForbidden(mockMvc.perform(get("/admin/resorts/new")));
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void invalidCreateReRendersFormWithoutCallingService() throws Exception {
		mockMvc.perform(post("/admin/resorts")
						.with(csrf())
						.contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.param("name", "")
						.param("country", "Austria")
						.param("highestPoint", "3000")
						.param("totalLifts", "20")
						.param("beginnerSlopes", "10")
						.param("intermediateSlopes", "20")
						.param("difficultSlopes", "5"))
				.andExpect(status().isOk())
				.andExpect(view().name("admin/resorts/form"))
				.andExpect(model().attributeHasFieldErrors("resortForm", "name"));

		verify(resortService, never()).create(any());
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void validCreateCallsServiceAndRedirectsWithFlash() throws Exception {
		mockMvc.perform(post("/admin/resorts")
						.with(csrf())
						.contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.param("name", "Test Resort")
						.param("country", "Austria")
						.param("highestPoint", "3000")
						.param("totalLifts", "20")
						.param("beginnerSlopes", "10")
						.param("intermediateSlopes", "20")
						.param("difficultSlopes", "5"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/admin/resorts"))
				.andExpect(flash().attribute("resortSaved", true));

		verify(resortService).create(any(ResortForm.class));
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void duplicateExternalIdOnCreateShowsFieldError() throws Exception {
		doThrow(new DuplicateExternalIdException(42L)).when(resortService).create(any());

		mockMvc.perform(post("/admin/resorts")
						.with(csrf())
						.contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.param("name", "Test Resort")
						.param("country", "Austria")
						.param("highestPoint", "3000")
						.param("totalLifts", "20")
						.param("beginnerSlopes", "10")
						.param("intermediateSlopes", "20")
						.param("difficultSlopes", "5")
						.param("externalId", "42"))
				.andExpect(status().isOk())
				.andExpect(view().name("admin/resorts/form"))
				.andExpect(model().attributeHasFieldErrors("resortForm", "externalId"));
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void editFormLoadsPopulatedForm() throws Exception {
		ResortForm form = populatedForm();
		when(resortService.loadForm(7L)).thenReturn(form);

		mockMvc.perform(get("/admin/resorts/7/edit"))
				.andExpect(status().isOk())
				.andExpect(view().name("admin/resorts/form"))
				.andExpect(model().attribute("resortForm", form))
				.andExpect(model().attribute("formAction", "/admin/resorts/7"));
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void editFormMissingResortReturns404() throws Exception {
		when(resortService.loadForm(99L)).thenThrow(new ResortNotFoundException(99L));

		mockMvc.perform(get("/admin/resorts/99/edit"))
				.andExpect(status().isNotFound());
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void validUpdateCallsServiceAndRedirects() throws Exception {
		mockMvc.perform(post("/admin/resorts/7")
						.with(csrf())
						.contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.param("id", "7")
						.param("name", "Updated Resort")
						.param("country", "France")
						.param("highestPoint", "2500")
						.param("totalLifts", "15")
						.param("beginnerSlopes", "5")
						.param("intermediateSlopes", "10")
						.param("difficultSlopes", "3"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/admin/resorts"))
				.andExpect(flash().attribute("resortSaved", true));

		verify(resortService).update(eq(7L), any(ResortForm.class));
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void adminToggleActiveReturnsFragmentAndCallsService() throws Exception {
		when(resortService.toggleActive(7L)).thenReturn(false);

		mockMvc.perform(post("/admin/resorts/7/active").with(csrf()))
				.andExpect(status().isOk())
				.andExpect(view().name("admin/resorts/active-toggle-response"))
				.andExpect(model().attribute("resortId", 7L))
				.andExpect(model().attribute("active", false));

		verify(resortService).toggleActive(7L);
	}

	@Test
	@WithMockUser(roles = "USER")
	void userToggleActiveReturns403() throws Exception {
		assertForbidden(mockMvc.perform(post("/admin/resorts/7/active").with(csrf())));

		verify(resortService, never()).toggleActive(any());
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void toggleActiveMissingResortReturns404() throws Exception {
		when(resortService.toggleActive(99L)).thenThrow(new ResortNotFoundException(99L));

		mockMvc.perform(post("/admin/resorts/99/active").with(csrf()))
				.andExpect(status().isNotFound());
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void toggleActiveConcurrentUpdateReturns409() throws Exception {
		when(resortService.toggleActive(77L)).thenThrow(new ConcurrentResortUpdateException(77L, null));

		mockMvc.perform(post("/admin/resorts/77/active").with(csrf()))
				.andExpect(status().isConflict());
	}

	private static ResortForm populatedForm() {
		ResortForm form = new ResortForm();
		form.setId(7L);
		form.setName("Sölden");
		form.setCountry("Austria");
		form.setHighestPoint(3340);
		form.setTotalLifts(31);
		form.setBeginnerSlopes(14);
		form.setIntermediateSlopes(29);
		form.setDifficultSlopes(13);
		return form;
	}
}
