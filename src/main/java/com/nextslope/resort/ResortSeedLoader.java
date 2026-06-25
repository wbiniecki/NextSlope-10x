package com.nextslope.resort;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
public class ResortSeedLoader implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(ResortSeedLoader.class);

	private static final String CSV_PATH = "data/resorts-Europe-subset.csv";

	private final ResortRepository resortRepository;

	public ResortSeedLoader(ResortRepository resortRepository) {
		this.resortRepository = resortRepository;
	}

	@Override
	public void run(ApplicationArguments args) {
		seed();
	}

	void seed() {
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
