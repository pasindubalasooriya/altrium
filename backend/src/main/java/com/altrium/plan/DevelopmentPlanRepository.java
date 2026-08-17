package com.altrium.plan;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * A plan is always fetched for one named person, whose identity has already been through
 * {@code AuthorizationService}. There is no list here and no specification, because there is no
 * "all plans" question in the product: HR see a department's plans through the people in it.
 */
public interface DevelopmentPlanRepository extends JpaRepository<DevelopmentPlan, Long> {

    /**
     * Goals are fetched with the plan. Safe as an entity graph over a collection, unlike
     * anywhere on the review side, because this returns a single row rather than a page - there
     * is no pagination for the collection to force into memory.
     */
    @EntityGraph(attributePaths = {"user", "goals"})
    Optional<DevelopmentPlan> findByUserId(Long userId);
}
