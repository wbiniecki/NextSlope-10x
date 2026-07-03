package com.nextslope.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.nextslope.resort.ResortService;

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
}
