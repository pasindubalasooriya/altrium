package com.altrium.review;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * The aggregate queries behind the manager's and HR's dashboards (scenario section 13).
 *
 * <p>A narrow {@link Repository} rather than a {@code JpaRepository}, for the same reason
 * {@link LeadershipMetricsRepository} is one: the only methods that exist are the two below,
 * so a page of participants cannot arrive through this interface by accident and nothing here
 * can return a row about a person.
 *
 * <h2>The scope clause, and why it is written out rather than built</h2>
 *
 * <p>Every other scoped read in this system goes through
 * {@link com.altrium.auth.SubjectScopeSpecification}, and that class exists precisely so the
 * predicate is not written twice. It cannot be used here: these are grouped aggregates over
 * an ad-hoc join between {@code CycleParticipant} and three artifact tables that carry no
 * mapped association to it, which the Criteria API cannot express without dropping into
 * Hibernate-specific joins.
 *
 * <p>So the clause is transcribed, once, into {@link #SCOPE} and shared by both queries below.
 * Two things keep the transcription honest. It carries no {@code includeSelf} branch at all,
 * because {@code DashboardService} passes a scope with the caller already removed and asserts
 * that it did - a dashboard is about other people, and P-6.3 excludes the caller's own
 * participant row from every total in any case. And {@code DashboardTest} pins the totals to
 * {@code CycleMonitoringService}, which reaches the same rows down the ordinary path, so a
 * divergence between the two shows up as a failing test rather than as two screens quietly
 * disagreeing.
 *
 * <p>Both id sets must be non-empty. The service substitutes a sentinel that matches nothing,
 * so a caller with no grounds gets zero rows rather than an {@code IN ()} that fails to parse.
 * The failure direction is the same as the specification's: a bug here returns nothing.
 */
public interface DashboardRepository extends Repository<CycleParticipant, Long> {

    /**
     * The caller's grounds as a {@code WHERE} fragment: their direct reports, plus the
     * departments granted to them minus their own row (P-2.2 in SQL).
     */
    String SCOPE = """
            AND ( p.subject.id IN :reportIds
                  OR ( p.department.id IN :departmentIds AND p.subject.id <> :callerId ) )
            """;

    /**
     * One row of totals for everybody in scope.
     *
     * <p>Peer submissions are counted here and that is safe by construction rather than by
     * care: after the caller's own row is removed the only remaining grounds are
     * {@code DIRECT_MANAGER} and {@code HR_IN_SCOPE}, which are exactly the grounds of
     * {@code READ_PEER_REVIEW} (P-3.2). Nobody can reach this count for a person whose peer
     * feedback they could not open one at a time, and no subject can reach their own (P-3.3).
     */
    @Query("""
            SELECT COUNT(p.id)                                                  AS participants,
                   SUM(CASE WHEN sr.submittedAt IS NOT NULL THEN 1 ELSE 0 END)  AS selfReviewsSubmitted,
                   SUM(CASE WHEN mr.submittedAt IS NOT NULL THEN 1 ELSE 0 END)  AS managerReviewsSubmitted,
                   SUM(CASE WHEN fr.id          IS NOT NULL THEN 1 ELSE 0 END)  AS ratingsSet,
                   SUM(CASE WHEN fr.releasedAt  IS NOT NULL THEN 1 ELSE 0 END)  AS ratingsReleased
            FROM CycleParticipant p
            LEFT JOIN SelfReview    sr ON sr.cycle = p.cycle AND sr.subject = p.subject
            LEFT JOIN ManagerReview mr ON mr.cycle = p.cycle AND mr.subject = p.subject
            LEFT JOIN FinalRating   fr ON fr.cycle = p.cycle AND fr.subject = p.subject
            WHERE p.cycle.id = :cycleId
            """ + SCOPE)
    Totals totals(@Param("cycleId") Long cycleId,
                  @Param("callerId") Long callerId,
                  @Param("reportIds") Collection<Long> reportIds,
                  @Param("departmentIds") Collection<Long> departmentIds);

    /**
     * How many peer reviews have been submitted about the people in scope.
     *
     * <p>Separate from {@link #totals} rather than another {@code LEFT JOIN} on it: a subject
     * has two peers, so joining the peer table into that query would multiply every other row
     * and turn each count into a count of pairs. The classic aggregate bug, and worth one extra
     * query to be rid of.
     */
    @Query("""
            SELECT COUNT(pr.id)
            FROM CycleParticipant p
            JOIN PeerReview pr ON pr.cycle = p.cycle AND pr.subject = p.subject
            WHERE p.cycle.id = :cycleId
              AND pr.submittedAt IS NOT NULL
            """ + SCOPE)
    long peerReviewsSubmitted(@Param("cycleId") Long cycleId,
                              @Param("callerId") Long callerId,
                              @Param("reportIds") Collection<Long> reportIds,
                              @Param("departmentIds") Collection<Long> departmentIds);

    /**
     * The rating distribution across the people in scope.
     *
     * <p><strong>Broken down by nothing, and that is the difference from Leadership.</strong>
     * P-7.5 forbids a per-department distribution for Leadership, because a department of one
     * would make its distribution that person's rating and Leadership hold no grounds to read
     * an individual rating. The rule does not apply here and would be theatre if it did: a
     * manager may read each report's rating one at a time (P-4.2), and HR may read every rating
     * in a granted department (P-2.1), so an aggregate over exactly those people discloses
     * nothing they could not already open. A team of one is a team whose rating its manager set.
     */
    @Query("""
            SELECT fr.rating AS rating, COUNT(fr.id) AS total
            FROM CycleParticipant p
            JOIN FinalRating fr ON fr.cycle = p.cycle AND fr.subject = p.subject
            WHERE p.cycle.id = :cycleId
            """ + SCOPE + """
            GROUP BY fr.rating
            """)
    List<LeadershipMetricsRepository.RatingTotal> ratingDistribution(
            @Param("cycleId") Long cycleId,
            @Param("callerId") Long callerId,
            @Param("reportIds") Collection<Long> reportIds,
            @Param("departmentIds") Collection<Long> departmentIds);

    /** Counts only. Nothing here names a person, so no review content passes through it. */
    interface Totals {
        long getParticipants();

        long getSelfReviewsSubmitted();

        long getManagerReviewsSubmitted();

        long getRatingsSet();

        long getRatingsReleased();
    }
}
