package com.nextslope.web;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import com.nextslope.resort.Resort;
import com.nextslope.resort.ResortRepository;

import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class ResortController {

	private final ResortRepository resortRepository;

	@GetMapping("/resorts")
	public String list(Model model) {
		model.addAttribute("resorts", resortRepository.findByActiveTrueOrderByCountryAscNameAsc());
		return "resorts/list";
	}

	@GetMapping("/resorts/{id}")
	public String detail(@PathVariable Long id, Model model) {
		Resort resort = resortRepository.findByIdAndActiveTrue(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		model.addAttribute("resort", resort);
		return "resorts/detail";
	}
}
