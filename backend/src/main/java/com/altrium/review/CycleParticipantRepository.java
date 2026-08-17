package com.altrium.review;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

/**
 * The review list's repository - the one every "which reviews may I see?" query goes
 * through.
 *
 * <p>Note what is <em>not</em> here: no {@code findByCycleId}, no {@code findAll()} wrapper,
 * no finder that returns participants without a {@link Specification}. Such a method would
 * be entirely ordinary to add and would bypass the authorization layer completely the first
 * time somebody reached for it. Every read is forced through a specification so that the
 * scope has somewhere to go (P-0.3).
 */
public interface CycleParticipantRepository
        extends JpaRepository<CycleParticipant, Long>, JpaSpecificationExecutor<CycleParticipant> {

    /**
     * To-one associations are fetched with the page, because the review list shows the
     * subject's name, department and manager on every row. Without this, a page of 50 costs
     * 150 extra queries.
     *
     * <p>Only to-one associations belong here. A collection would force Hibernate to
     * paginate in memory, which is precisely the organisational-size assumption the
     * constraints forbid.
     */
    @Override
    @EntityGraph(attributePaths = {"subject", "subject.department", "subject.manager", "cycle"})
    Page<CycleParticipant> findAll(Specification<CycleParticipant> spec, Pageable pageable);

    Optional<CycleParticipant> findByCycleIdAndSubjectId(Long cycleId, Long subjectId);
}
