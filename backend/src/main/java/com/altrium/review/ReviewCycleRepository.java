package com.altrium.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Cycles are not scoped by subject, because a cycle is not about anybody — its dates and
 * status are the same fact for the whole organisation.
 *
 * <p>What must not leak from here is <em>who is in</em> a cycle. That is
 * {@link CycleParticipantRepository}'s question, and it is scoped.
 */
public interface ReviewCycleRepository extends JpaRepository<ReviewCycle, Long> {

    Optional<ReviewCycle> findByFinancialYearAndQuadrimesterNo(int financialYear, int quadrimesterNo);

    List<ReviewCycle> findByStatusOrderByStartDateDesc(CycleStatus status);

    /**
     * The sweep's selection predicate (P-6.4): start date <em>arrived or passed</em>, never
     * equals today, so a missed run still resolves on the next sweep.
     */
    List<ReviewCycle> findByOpenedAtIsNullAndStartDateLessThanEqual(java.time.LocalDate today);
}
