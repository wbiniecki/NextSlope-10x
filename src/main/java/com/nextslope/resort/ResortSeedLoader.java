package com.nextslope.resort;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ResortSeedLoader implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(ResortSeedLoader.class);

	private static final String CSV_PATH = "data/resorts-Europe-subset.csv";

	private final ResortRepository resortRepository;

	/**
	 * When {@code false} (default) the loader only seeds an empty table. When {@code true}
	 * (opt-in via {@code nextslope.resort-seed.resync}) it reconciles an already-populated
	 * table to the CSV by upserting each row keyed on {@code external_id}, never deleting
	 * rows and never touching the {@code active} flag of existing rows.
	 */
	private final boolean resync;

	public ResortSeedLoader(ResortRepository resortRepository,
			@Value("${nextslope.resort-seed.resync:false}") boolean resync) {
		this.resortRepository = resortRepository;
		this.resync = resync;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		seed();
	}

	void seed() {
		if (resync) {
			resync();
			return;
		}

		if (resortRepository.count() != 0) {
			log.info("resort seed skipped — table already populated");
			return;
		}

		List<Resort> resorts = parseResorts();
		try {
			resortRepository.saveAll(resorts);
			log.info("resort seed inserted {} resorts", resorts.size());
		} catch (DataIntegrityViolationException ex) {
			// Lost the race past the empty-table pre-check (concurrent boot) — the
			// UNIQUE(external_id) backstop held; treat as already-seeded rather than failing startup.
			log.info("resort seed skipped — table already populated (unique constraint backstop)");
		}
	}

	private void resync() {
		List<Resort> parsed = parseResorts();
		// Load the current catalog once and index by external_id, so reconciliation is a single
		// SELECT + one batched saveAll rather than a findByExternalId per CSV row.
		Map<Long, Resort> existingByExternalId = new HashMap<>();
		for (Resort existing : resortRepository.findAll()) {
			if (existing.getExternalId() != null) {
				existingByExternalId.put(existing.getExternalId(), existing);
			}
		}
		List<Resort> toSave = new ArrayList<>();
		int updated = 0;
		int inserted = 0;
		for (Resort incoming : parsed) {
			if (incoming.getExternalId() == null) {
				throw new IllegalStateException(
						"Cannot resync a resort row with a blank ID — external_id is required to upsert by key.");
			}
			Resort existing = existingByExternalId.get(incoming.getExternalId());
			if (existing == null) {
				toSave.add(incoming);
				inserted++;
			} else {
				copyFacts(incoming, existing);
				toSave.add(existing);
				updated++;
			}
		}
		resortRepository.saveAll(toSave);
		log.info("resort seed resync complete — {} updated, {} inserted", updated, inserted);
	}

	/**
	 * Copy the CSV-sourced fact columns from {@code incoming} onto {@code existing}, leaving the
	 * row's identity ({@code id}, {@code externalId}), audit timestamps, and — critically — the
	 * {@code active} flag untouched so an admin deactivation survives a resync.
	 */
	private static void copyFacts(Resort incoming, Resort existing) {
		existing.setName(incoming.getName());
		existing.setCountry(incoming.getCountry());
		existing.setContinent(incoming.getContinent());
		existing.setLatitude(incoming.getLatitude());
		existing.setLongitude(incoming.getLongitude());
		existing.setPrice(incoming.getPrice());
		existing.setSeason(incoming.getSeason());
		existing.setHighestPoint(incoming.getHighestPoint());
		existing.setLowestPoint(incoming.getLowestPoint());
		existing.setBeginnerSlopes(incoming.getBeginnerSlopes());
		existing.setIntermediateSlopes(incoming.getIntermediateSlopes());
		existing.setDifficultSlopes(incoming.getDifficultSlopes());
		existing.setTotalSlopes(incoming.getTotalSlopes());
		existing.setLongestRun(incoming.getLongestRun());
		existing.setSnowCannons(incoming.getSnowCannons());
		existing.setSurfaceLifts(incoming.getSurfaceLifts());
		existing.setChairLifts(incoming.getChairLifts());
		existing.setGondolaLifts(incoming.getGondolaLifts());
		existing.setTotalLifts(incoming.getTotalLifts());
		existing.setLiftCapacity(incoming.getLiftCapacity());
		existing.setChildFriendly(incoming.getChildFriendly());
		existing.setSnowparks(incoming.getSnowparks());
		existing.setNightskiing(incoming.getNightskiing());
		existing.setSummerSkiing(incoming.getSummerSkiing());
	}

	private List<Resort> parseResorts() {
		ClassPathResource resource = new ClassPathResource(CSV_PATH);
		CSVFormat format = CSVFormat.DEFAULT.builder()
				.setHeader()
				.setSkipHeaderRecord(true)
				.get();

		try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8);
				CSVParser parser = format.parse(reader)) {
			List<Resort> resorts = new ArrayList<>();
			for (CSVRecord record : parser) {
				resorts.add(toResort(record));
			}
			return resorts;
		} catch (IOException ex) {
			throw new UncheckedIOException("Failed to read resort seed CSV: " + CSV_PATH, ex);
		}
	}

	private Resort toResort(CSVRecord record) {
		return Resort.builder()
				.externalId(parseLong(record, "ID"))
				.name(trim(record.get("Resort")))
				.country(trim(record.get("Country")))
				.continent(trim(record.get("Continent")))
				.latitude(parseDouble(record, "Latitude"))
				.longitude(parseDouble(record, "Longitude"))
				.price(parseInt(record, "Price"))
				.season(trim(record.get("Season")))
				.highestPoint(parseInt(record, "Highest point"))
				.lowestPoint(parseInt(record, "Lowest point"))
				.beginnerSlopes(parseInt(record, "Beginner slopes"))
				.intermediateSlopes(parseInt(record, "Intermediate slopes"))
				.difficultSlopes(parseInt(record, "Difficult slopes"))
				.totalSlopes(parseInt(record, "Total slopes"))
				.longestRun(parseInt(record, "Longest run"))
				.snowCannons(parseInt(record, "Snow cannons"))
				.surfaceLifts(parseInt(record, "Surface lifts"))
				.chairLifts(parseInt(record, "Chair lifts"))
				.gondolaLifts(parseInt(record, "Gondola lifts"))
				.totalLifts(parseInt(record, "Total lifts"))
				.liftCapacity(parseInt(record, "Lift capacity"))
				.childFriendly(parseYesNo(record.get("Child friendly")))
				.snowparks(parseYesNo(record.get("Snowparks")))
				.nightskiing(parseYesNo(record.get("Nightskiing")))
				.summerSkiing(parseYesNo(record.get("Summer skiing")))
				.active(true)
				.build();
	}

	private static String trim(String value) {
		return value == null ? null : value.trim();
	}

	private static boolean parseYesNo(String value) {
		return "Yes".equalsIgnoreCase(trim(value));
	}

	private static Long parseLong(CSVRecord record, String column) {
		String trimmed = trim(record.get(column));
		if (trimmed == null || trimmed.isEmpty()) {
			return null;
		}
		try {
			return Long.valueOf(trimmed);
		} catch (NumberFormatException ex) {
			throw malformedCell(record, column, trimmed, ex);
		}
	}

	private static Integer parseInt(CSVRecord record, String column) {
		String trimmed = trim(record.get(column));
		if (trimmed == null || trimmed.isEmpty()) {
			return null;
		}
		try {
			return Integer.valueOf(trimmed);
		} catch (NumberFormatException ex) {
			throw malformedCell(record, column, trimmed, ex);
		}
	}

	private static Double parseDouble(CSVRecord record, String column) {
		String trimmed = trim(record.get(column));
		if (trimmed == null || trimmed.isEmpty()) {
			return null;
		}
		try {
			return Double.valueOf(trimmed);
		} catch (NumberFormatException ex) {
			throw malformedCell(record, column, trimmed, ex);
		}
	}

	private static IllegalStateException malformedCell(CSVRecord record, String column, String value, NumberFormatException cause) {
		return new IllegalStateException(
				"Malformed numeric cell in resort seed CSV " + CSV_PATH
						+ " (row " + record.getRecordNumber() + ", column \"" + column + "\"): \"" + value + "\"",
				cause);
	}
}
