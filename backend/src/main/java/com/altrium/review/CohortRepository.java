package com.altrium.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Cohorts are configuration, not review content, so nothing here is scoped by subject. Who is
 * <em>in</em> a cohort is equally configuration: it says who will be reviewed, never anything
 * about a review. The Super Admin owns all of it (P-6.1, P-9.3) and holds no review access at
 * all (P-9.4), which is exactly why the two can live in the same place safely.
 */
public interface CohortRepository extends JpaRepository<Cohort, Long> {

    Optional<Cohort> findByName(String name);

    boolean existsByName(String name);

    /** Every cohort the sweep should draw on when opening a cycle for this quadrimester. */
    List<Cohort> findByQuadrimesterNo(Integer quadrimesterNo);
}
