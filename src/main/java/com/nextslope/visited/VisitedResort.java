package com.nextslope.visited;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A single "user marked resort as visited" mark (one row per user/resort pair). Both FKs are stored
 * as plain ids rather than {@code @ManyToOne} to keep the entity lean, mirroring {@code PreferenceProfile}.
 * A mark is immutable — it exists or it doesn't — so there is no {@code updated_at}.
 */
@Entity
@Table(name = "visited_resorts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VisitedResort {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "resort_id", nullable = false)
	private Long resortId;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;
}
