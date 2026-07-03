package com.nextslope.web;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nextslope.resort.DuplicateExternalIdException;
import com.nextslope.resort.ResortForm;
import com.nextslope.resort.ResortNotFoundException;
import com.nextslope.resort.ResortService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class AdminResortController {

	private final ResortService resortService;

	@GetMapping("/admin/resorts")
	public String list(Model model) {
		model.addAttribute("resorts", resortService.listAll());
		return "admin/resorts/list";
	}

	@GetMapping("/admin/resorts/new")
	public String newForm(Model model) {
		model.addAttribute("resortForm", new ResortForm());
		model.addAttribute("formAction", "/admin/resorts");
		return "admin/resorts/form";
	}

	@PostMapping("/admin/resorts")
	public String create(@Valid @ModelAttribute("resortForm") ResortForm form,
			BindingResult bindingResult,
			Model model,
			RedirectAttributes redirectAttributes) {
		if (bindingResult.hasErrors()) {
			model.addAttribute("formAction", "/admin/resorts");
			return "admin/resorts/form";
		}

		try {
			resortService.create(form);
		} catch (DuplicateExternalIdException ex) {
			bindingResult.rejectValue("externalId", "externalId.duplicate",
					"External ID is already in use");
			model.addAttribute("formAction", "/admin/resorts");
			return "admin/resorts/form";
		}

		redirectAttributes.addFlashAttribute("resortSaved", true);
		return "redirect:/admin/resorts";
	}

	@GetMapping("/admin/resorts/{id}/edit")
	public String editForm(@PathVariable Long id, Model model) {
		try {
			model.addAttribute("resortForm", resortService.loadForm(id));
			model.addAttribute("formAction", "/admin/resorts/" + id);
			return "admin/resorts/form";
		} catch (ResortNotFoundException ex) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
	}

	@PostMapping("/admin/resorts/{id}")
	public String update(@PathVariable Long id,
			@Valid @ModelAttribute("resortForm") ResortForm form,
			BindingResult bindingResult,
			Model model,
			RedirectAttributes redirectAttributes) {
		if (bindingResult.hasErrors()) {
			model.addAttribute("formAction", "/admin/resorts/" + id);
			return "admin/resorts/form";
		}

		try {
			resortService.update(id, form);
		} catch (DuplicateExternalIdException ex) {
			bindingResult.rejectValue("externalId", "externalId.duplicate",
					"External ID is already in use");
			model.addAttribute("formAction", "/admin/resorts/" + id);
			return "admin/resorts/form";
		} catch (ResortNotFoundException ex) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}

		redirectAttributes.addFlashAttribute("resortSaved", true);
		return "redirect:/admin/resorts";
	}
}
