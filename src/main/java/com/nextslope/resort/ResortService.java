package com.nextslope.resort;

import java.util.List;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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

	@Transactional(readOnly = true)
	public ResortForm loadForm(Long id) {
		Resort resort = resortRepository.findById(id)
				.orElseThrow(() -> new ResortNotFoundException(id));
		return toForm(resort);
	}

	@Transactional
	public void create(ResortForm form) {
		enforceExternalIdUniqueness(form.getExternalId(), null);
		Resort resort = new Resort();
		resort.setActive(true);
		applyManagedFields(resort, form);
		saveResort(resort);
	}

	@Transactional
	public void update(Long id, ResortForm form) {
		Resort resort = resortRepository.findById(id)
				.orElseThrow(() -> new ResortNotFoundException(id));
		enforceExternalIdUniqueness(form.getExternalId(), id);
		applyManagedFields(resort, form);
		saveResort(resort);
	}

	@Transactional
	public boolean toggleActive(Long id) {
		Resort resort = resortRepository.findById(id)
				.orElseThrow(() -> new ResortNotFoundException(id));
		resort.setActive(!resort.getActive());
		try {
			resortRepository.saveAndFlush(resort);
		} catch (ObjectOptimisticLockingFailureException ex) {
			throw new ConcurrentResortUpdateException(id, ex);
		}
		return resort.getActive();
	}

	private void applyManagedFields(Resort resort, ResortForm form) {
		resort.setName(form.getName());
		resort.setCountry(form.getCountry());
		resort.setHighestPoint(form.getHighestPoint());
		resort.setTotalLifts(form.getTotalLifts());
		resort.setBeginnerSlopes(form.getBeginnerSlopes());
		resort.setIntermediateSlopes(form.getIntermediateSlopes());
		resort.setDifficultSlopes(form.getDifficultSlopes());
		resort.setExternalId(form.getExternalId());
		resort.setTotalSlopes(form.getBeginnerSlopes()
				+ form.getIntermediateSlopes()
				+ form.getDifficultSlopes());
	}

	private void enforceExternalIdUniqueness(Long externalId, Long excludeId) {
		if (externalId == null) {
			return;
		}
		resortRepository.findByExternalId(externalId).ifPresent(existing -> {
			if (excludeId == null || !existing.getId().equals(excludeId)) {
				throw new DuplicateExternalIdException(externalId);
			}
		});
	}

	private void saveResort(Resort resort) {
		try {
			resortRepository.save(resort);
		} catch (DataIntegrityViolationException ex) {
			if (resort.getExternalId() != null && isExternalIdViolation(ex)) {
				throw new DuplicateExternalIdException(resort.getExternalId());
			}
			throw ex;
		}
	}

	private static boolean isExternalIdViolation(DataIntegrityViolationException ex) {
		// Only relabel a save failure as a duplicate-externalId error when it is genuinely the
		// uq_resorts_external_id unique constraint. The constraint name appears in both the H2 and
		// Postgres violation messages, so matching it keeps unrelated integrity errors truthful.
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			String message = cause.getMessage();
			if (message != null
					&& message.toUpperCase(Locale.ROOT).contains("UQ_RESORTS_EXTERNAL_ID")) {
				return true;
			}
		}
		return false;
	}

	private static ResortForm toForm(Resort resort) {
		ResortForm form = new ResortForm();
		form.setId(resort.getId());
		form.setName(resort.getName());
		form.setCountry(resort.getCountry());
		form.setHighestPoint(resort.getHighestPoint());
		form.setTotalLifts(resort.getTotalLifts());
		form.setBeginnerSlopes(resort.getBeginnerSlopes());
		form.setIntermediateSlopes(resort.getIntermediateSlopes());
		form.setDifficultSlopes(resort.getDifficultSlopes());
		form.setExternalId(resort.getExternalId());
		return form;
	}
}
