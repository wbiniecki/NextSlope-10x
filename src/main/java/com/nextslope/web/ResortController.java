package com.nextslope.web;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import com.nextslope.resort.Resort;
import com.nextslope.resort.ResortRepository;
import com.nextslope.user.CurrentUserService;
import com.nextslope.visited.VisitedResortService;

import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class ResortController {

	private final ResortRepository resortRepository;
	private final CurrentUserService currentUserService;
	private final VisitedResortService visitedResortService;

	@GetMapping("/resorts")
	public String list(@AuthenticationPrincipal UserDetails principal, Model model) {
		Long userId = currentUserService.requireUserId(principal);
		model.addAttribute("resorts", resortRepository.findByActiveTrueOrderByCountryAscNameAsc());
		model.addAttribute("visitedIds", visitedResortService.visitedResortIds(userId));
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
