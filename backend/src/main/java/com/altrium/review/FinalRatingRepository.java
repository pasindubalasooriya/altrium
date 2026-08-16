package com.altrium.review;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface FinalRatingRepository
        extends JpaRepository<FinalRating, Long>, JpaSpecificationExecutor<FinalRating> {
}
