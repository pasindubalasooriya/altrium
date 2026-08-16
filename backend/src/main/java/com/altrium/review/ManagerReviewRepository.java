package com.altrium.review;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ManagerReviewRepository
        extends JpaRepository<ManagerReview, Long>, JpaSpecificationExecutor<ManagerReview> {
}
