package com.altrium.review;

import com.altrium.org.AppUser;
import com.altrium.org.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CohortMemberRepository extends JpaRepository<CohortMember, Long> {

    /** At most one, by the unique key on {@code user_id} - see {@link CohortMember}. */
    Optional<CohortMember> findByUserId(Long userId);

    List<CohortMember> findByCohortIdOrderByUserFullNameAsc(Long cohortId);

    long countByCohortId(Long cohortId);

    /**
     * Who the sweep takes into a cycle for this quadrimester (P-6.4).
     *
     * <p>Both exclusions are applied <em>here</em>, in the query, rather than by filtering the
     * result afterwards. They are domain invariants the sweep is still bound by even though it
     * runs with no user to authorize:
     *
     * <ul>
     *   <li><b>P-0.7</b> - a deactivated employee is not taken into a newly opened cycle. Their
     *       existing rows survive untouched; what stops is being drawn into anything new.</li>
     *   <li><b>P-1.5</b> - Leadership is never a reviewee. Enforced at creation rather than
     *       hidden on read, so no artifact for a Leadership member is ever brought into being
     *       by an automated job at three in the morning.</li>
     * </ul>
     *
     * <p>The join to the cohort carries the quadrimester, so a cohort detached from every
     * quadrimester matches nothing and is silently skipped, which is the whole meaning of
     * detaching it.
     */
    @Query("""
            SELECT m.user FROM CohortMember m
            WHERE m.cohort.quadrimesterNo = :quadrimesterNo
              AND m.user.active = true
              AND :leadership NOT MEMBER OF m.user.roles
            """)
    List<AppUser> findEligibleForQuadrimester(@Param("quadrimesterNo") Integer quadrimesterNo,
                                              @Param("leadership") Role leadership);
}
