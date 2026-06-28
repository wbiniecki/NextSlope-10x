package com.nextslope.recommendation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the recommendation tunables as a bean so the scorer and rationale builder share one source of
 * truth. The refinement session (Phase 4) retunes {@link ScoringConfig} here, in one place.
 */
@Configuration
public class RecommendationConfig {

	@Bean
	ScoringConfig scoringConfig() {
		return ScoringConfig.defaults();
	}
}
