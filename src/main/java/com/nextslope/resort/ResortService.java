package com.nextslope.resort;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ResortService {

	private final ResortRepository resortRepository;

	@Transactional(readOnly = true)
	public List<Resort> listAll() {
		return resortRepository.findAllByOrderByCountryAscNameAsc();
	}
}
