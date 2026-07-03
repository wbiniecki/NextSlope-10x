package com.nextslope.resort;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "resorts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Resort {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "external_id")
	private Long externalId;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "country", nullable = false)
	private String country;

	@Column(name = "continent")
	private String continent;

	@Column(name = "latitude")
	private Double latitude;

	@Column(name = "longitude")
	private Double longitude;

	@Column(name = "price")
	private Integer price;

	@Column(name = "season")
	private String season;

	@Column(name = "highest_point")
	private Integer highestPoint;

	@Column(name = "lowest_point")
	private Integer lowestPoint;

	@Column(name = "beginner_slopes")
	private Integer beginnerSlopes;

	@Column(name = "intermediate_slopes")
	private Integer intermediateSlopes;

	@Column(name = "difficult_slopes")
	private Integer difficultSlopes;

	@Column(name = "total_slopes")
	private Integer totalSlopes;

	@Column(name = "longest_run")
	private Integer longestRun;

	@Column(name = "snow_cannons")
	private Integer snowCannons;

	@Column(name = "surface_lifts")
	private Integer surfaceLifts;

	@Column(name = "chair_lifts")
	private Integer chairLifts;

	@Column(name = "gondola_lifts")
	private Integer gondolaLifts;

	@Column(name = "total_lifts")
	private Integer totalLifts;

	@Column(name = "lift_capacity")
	private Integer liftCapacity;

	@Column(name = "child_friendly")
	private Boolean childFriendly;

	@Column(name = "snowparks")
	private Boolean snowparks;

	@Column(name = "nightskiing")
	private Boolean nightskiing;

	@Column(name = "summer_skiing")
	private Boolean summerSkiing;

	@Column(name = "active", nullable = false)
	private Boolean active;

	@Version
	@Column(name = "version", nullable = false)
	private Long version;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/**
	 * Display-only easy/medium/hard split derived from the three difficulty slope
	 * counts. Percentages are taken over the sum of those three counts (not
	 * {@code totalSlopes}, which can differ) and rounded by the largest-remainder
	 * method so the three values always sum to 100. A zero denominator yields zeros.
	 */
	@Transient
	public DifficultyMix getDifficultyMix() {
		int beginner = nullToZero(beginnerSlopes);
		int intermediate = nullToZero(intermediateSlopes);
		int difficult = nullToZero(difficultSlopes);
		int denominator = beginner + intermediate + difficult;
		if (denominator == 0) {
			return new DifficultyMix(0, 0, 0);
		}

		int[] counts = {beginner, intermediate, difficult};
		int[] floors = new int[3];
		double[] remainders = new double[3];
		int floorSum = 0;
		for (int i = 0; i < 3; i++) {
			double exact = counts[i] * 100.0 / denominator;
			floors[i] = (int) Math.floor(exact);
			remainders[i] = exact - floors[i];
			floorSum += floors[i];
		}

		int leftover = 100 - floorSum;
		while (leftover > 0) {
			int largest = 0;
			for (int i = 1; i < 3; i++) {
				if (remainders[i] > remainders[largest]) {
					largest = i;
				}
			}
			floors[largest]++;
			remainders[largest] = -1;
			leftover--;
		}

		return new DifficultyMix(floors[0], floors[1], floors[2]);
	}

	private static int nullToZero(Integer value) {
		return value == null ? 0 : value;
	}
}
