package com.altrium.review;

import com.altrium.auth.AuthorizationService;
import com.altrium.auth.Capability;
import com.altrium.auth.SubjectScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Feature 18 - role-based dashboards for the manager and for HR (scenario section 13).
 *
 * <h2>One endpoint, because there is one rule</h2>
 *
 * <p>The manager's team dashboard and HR's department dashboard are not two features. They are
 * the same aggregate over a different set of people, and which people is decided by the scope
 * in the SQL, exactly as it is for the review list - which likewise serves both consoles from
 * one call. Writing two endpoints would mean writing the scope twice, and the one that differs
 * is the leak.
 *
 * <p>The scope taken is {@code READ_REVIEW_SUMMARY}'s, so <strong>a dashboard counts exactly
 * the people whose reviews the caller may open, and never one more</strong>. That is worth more
 * than a capability of its own would be: a separate {@code READ_TEAM_METRICS} could drift from
 * the read it summarises, and a total that counts somebody you cannot open is a disclosure in
 * aggregate form.
 *
 * <h2>Leadership do not come here</h2>
 *
 * <p>They hold none of those grounds, so their scope is empty and this returns an empty
 * dashboard rather than a denial - the same shape the improvement-plan queue takes for a
 * manager (P-5.15). Their metrics are {@link LeadershipMetricsService}, which is unscoped by
 * department and refuses a per-department rating breakdown (P-7.5). An employee likewise gets
 * an empty dashboard: their only ground is {@code SELF}, and it is removed below.
 *
 * <h2>The caller is always removed</h2>
 *
 * <p>{@link SubjectScope#withoutSelf()} first, for two reasons that happen to agree. A
 * manager's dashboard is about their team, and a manager under review is not one of their own
 * reports - the same correction already made to the team list. And P-6.3 excludes an HR user's
 * own participant row from every total, so a granted HR Head oversees their department without
 * their own case being counted in it.
 *
 * <p>It also leaves the remaining grounds as exactly {@code DIRECT_MANAGER} and
 * {@code HR_IN_SCOPE}, which is what makes the peer-submission count safe to include.
 */
@Service
public class DashboardService {

    /**
     * A value no id can take, so an empty ground yields an {@code IN} that matches nothing.
     *
     * <p>JPQL cannot express {@code IN ()}, and the alternative - branching to a different
     * query when a set is empty - would mean four spellings of the same predicate. A caller
     * with no grounds at all therefore gets zeroes, which is the correct failure direction and
     * the same one {@code SubjectScopeSpecification} takes when it returns false.
     */
    private static final Set<Long> MATCHES_NOTHING = Set.of(-1L);

    private final AuthorizationService authorization;
    private final ReviewCycleRepository cycles;
    private final DashboardRepository dashboards;

    public DashboardService(AuthorizationService authorization,
                            ReviewCycleRepository cycles,
                            DashboardRepository dashboards) {
        this.authorization = authorization;
        this.cycles = cycles;
        this.dashboards = dashboards;
    }

    /**
     * What the caller may see about a cycle, in totals.
     *
     * @param cycleId the cycle; its dates and status are not about anybody, so it is read
     *                unscoped, exactly as the cycle list is
     */
    @Transactional(readOnly = true)
    public <T> T forCycle(Long cycleId, DashboardMapper<T> mapper) {
        ReviewCycle cycle = cycles.findById(cycleId)
                .orElseThrow(() -> new com.altrium.config.NotFoundApiException("No such cycle"));

        SubjectScope scope = authorization
                .subjectScopeFor(Capability.READ_REVIEW_SUMMARY)
                .withoutSelf();

        // The repository's WHERE clause is a transcription of SubjectScopeSpecification with
        // the self branch left out. This is what stops that transcription becoming wrong: if
        // withoutSelf ever stopped removing the caller, the clause would silently under-report
        // instead of failing.
        if (scope.includeSelf()) {
            throw new IllegalStateException(
                    "The dashboard scope must not include the caller; see DashboardRepository.SCOPE");
        }

        if (scope.isEmpty()) {
            // Not a denial. Leadership and employees legitimately have no dashboard here, and
            // an empty one says so without claiming they asked something forbidden.
            return mapper.map(cycle, true, 0, 0, 0, 0, 0, 0, Map.of());
        }

        Collection<Long> reports = orSentinel(scope.directReportIds());
        Collection<Long> departments = orSentinel(scope.hrDepartmentIds());

        DashboardRepository.Totals totals =
                dashboards.totals(cycleId, scope.callerId(), reports, departments);
        long peerReviews =
                dashboards.peerReviewsSubmitted(cycleId, scope.callerId(), reports, departments);

        List<LeadershipMetricsRepository.RatingTotal> distribution =
                dashboards.ratingDistribution(cycleId, scope.callerId(), reports, departments);

        // An EnumMap with only the ratings that occur. A rating nobody was given is absent
        // rather than present as zero - the client decides whether "nobody" is worth drawing,
        // and the response does not pretend to know how many bars a chart should have.
        Map<Rating, Long> byRating = new EnumMap<>(Rating.class);
        for (LeadershipMetricsRepository.RatingTotal row : distribution) {
            byRating.put(row.getRating(), row.getTotal());
        }

        return mapper.map(
                cycle,
                false,
                totals.getParticipants(),
                totals.getSelfReviewsSubmitted(),
                totals.getManagerReviewsSubmitted(),
                peerReviews,
                totals.getRatingsSet(),
                totals.getRatingsReleased(),
                byRating);
    }

    private static Collection<Long> orSentinel(Set<Long> ids) {
        return ids.isEmpty() ? MATCHES_NOTHING : ids;
    }

    /** Maps a dashboard. Totals only; nothing passed here names a person. */
    @FunctionalInterface
    public interface DashboardMapper<T> {
        T map(ReviewCycle cycle,
              boolean scopeIsEmpty,
              long participants,
              long selfReviewsSubmitted,
              long managerReviewsSubmitted,
              long peerReviewsSubmitted,
              long ratingsSet,
              long ratingsReleased,
              Map<Rating, Long> ratingDistribution);
    }
}
