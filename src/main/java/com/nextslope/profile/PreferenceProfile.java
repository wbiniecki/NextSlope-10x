package com.nextslope.profile;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.nextslope.resort.DifficultyMix;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A signed-in user's preference profile (one row per user). The FK to {@code users} is stored as a
 * plain id rather than a {@code @ManyToOne} to keep the entity lean. Region preference is the empty
 * set when "any region" — there is no separate flag (S-05 treats an empty set as "no region filter").
 */
@Entity
@Table(name = "preference_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PreferenceProfile {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "experience_level", nullable = false, length = 32)
	private ExperienceLevel experienceLevel;

	@Enumerated(EnumType.STRING)
	@Column(name = "difficulty_band", nullable = false, length = 32)
	private DifficultyBand difficultyBand;

	@Enumerated(EnumType.STRING)
	@Column(name = "novelty_preference", nullable = false, length = 32)
	private NoveltyPreference noveltyPreference;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "preference_profile_regions", joinColumns = @JoinColumn(name = "profile_id"))
	@Column(name = "country")
	@Builder.Default
	private Set<String> regionCountries = new HashSet<>();

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/** S-05 read accessor: the canonical easy/medium/hard triple for the chosen band. */
	@Transient
	public DifficultyMix getPreferredMix() {
		return difficultyBand.toMix();
	}
}
