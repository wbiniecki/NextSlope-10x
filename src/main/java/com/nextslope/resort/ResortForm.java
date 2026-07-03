package com.nextslope.resort;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Form-bound view of a resort for admin create/edit — never the entity. Only the PRD six facts plus
 * optional {@code externalId} are editable; all other columns are untouched on edit.
 */
@Getter
@Setter
@NoArgsConstructor
public class ResortForm {

	private Long id;

	@NotBlank(message = "Name is required")
	private String name;

	@NotBlank(message = "Country is required")
	private String country;

	@NotNull(message = "Top lift height is required")
	@PositiveOrZero(message = "Top lift height must be zero or greater")
	private Integer highestPoint;

	@NotNull(message = "Number of lifts is required")
	@PositiveOrZero(message = "Number of lifts must be zero or greater")
	private Integer totalLifts;

	@NotNull(message = "Beginner slope count is required")
	@PositiveOrZero(message = "Beginner slope count must be zero or greater")
	private Integer beginnerSlopes;

	@NotNull(message = "Intermediate slope count is required")
	@PositiveOrZero(message = "Intermediate slope count must be zero or greater")
	private Integer intermediateSlopes;

	@NotNull(message = "Difficult slope count is required")
	@PositiveOrZero(message = "Difficult slope count must be zero or greater")
	private Integer difficultSlopes;

	private Long externalId;
}
