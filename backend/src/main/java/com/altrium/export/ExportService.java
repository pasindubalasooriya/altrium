package com.altrium.export;

import com.altrium.auth.AuthorizationService;
import com.altrium.auth.Capability;
import com.altrium.auth.CurrentUserService;
import com.altrium.auth.Grounds;
import com.altrium.org.AppUserRepository;
import com.altrium.review.CycleMonitoringService;
import com.altrium.review.DashboardService;
import com.altrium.review.LeadershipMetricsService;
import com.altrium.review.Rating;
import com.altrium.review.ReviewCycle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Feature 19 - PDF and XLSX exports (scenario section 13).
 *
 * <h2>It runs no query of its own</h2>
 *
 * <p>Every number in an export comes from the service that already produces it for a screen:
 * {@link CycleMonitoringService} for HR, {@link LeadershipMetricsService} for Leadership,
 * {@link DashboardService} for HR's rating distribution. That is the whole design.
 *
 * <p>A hand-written export query would be a second definition of "which rows may this person
 * see", and the day the two disagreed the file would be the one that was wrong - and the one
 * already on somebody's laptop. Reusing the services makes it structurally impossible for an
 * export to contain a row the screen would not have shown, because it is the same call.
 *
 * <p>It also means each of those services re-checks the caller on its own. The
 * {@code EXPORT_REPORT} gate below is therefore not the only protection, and is not doing the
 * scoping: it decides <em>whether this person may take a file at all</em> (P-8.1), and the
 * services decide what is in it (P-8.2). A manager passes none of them, and would fail the
 * inner ones even if the outer one were removed.
 *
 * <h2>The manager exclusion is about distribution, not visibility</h2>
 *
 * <p>A manager may read every row of their own dashboard and may not export it. That looks
 * inconsistent until you notice the two are different acts: a screen is checked again on every
 * request, and a file is checked once and then travels. Scenario section 6 draws the line
 * there, and this is the one capability in the system where somebody is refused data they can
 * already see.
 */
@Service
public class ExportService {

    private final AuthorizationService authorization;
    private final CurrentUserService currentUser;
    private final AppUserRepository users;
    private final CycleMonitoringService monitoring;
    private final LeadershipMetricsService leadershipMetrics;
    private final DashboardService dashboards;
    private final ExportLogRepository log;
    private final Clock clock;

    public ExportService(AuthorizationService authorization,
                         CurrentUserService currentUser,
                         AppUserRepository users,
                         CycleMonitoringService monitoring,
                         LeadershipMetricsService leadershipMetrics,
                         DashboardService dashboards,
                         ExportLogRepository log,
                         Clock clock) {
        this.authorization = authorization;
        this.currentUser = currentUser;
        this.users = users;
        this.monitoring = monitoring;
        this.leadershipMetrics = leadershipMetrics;
        this.dashboards = dashboards;
        this.log = log;
        this.clock = clock;
    }

    /**
     * Builds the report a caller is entitled to, and records that they took it.
     *
     * <p>Not read-only, because of that record. The log write is part of the export rather than
     * a side effect of it: an export that succeeded and was not logged is exactly the case the
     * table exists to rule out, so the two share a transaction and fail together.
     */
    @Transactional
    public ExportReport buildReport(Long cycleId, ExportFormat format) {
        // P-8.1. Global, because an HR export covers the whole of their granted set rather than
        // one department named in the request. Managers hold no grounds here at all.
        Grounds grounds = authorization.requireGlobal(Capability.EXPORT_REPORT);

        ExportReport report = grounds == Grounds.LEADERSHIP
                ? companyReport(cycleId)
                : departmentReport(cycleId);

        // P-8.2 made visible. The scope note is written into the file and into the log from the
        // same value, so what a reader sees on the page is what the audit trail says was taken.
        log.save(new ExportLog(
                users.getReferenceById(currentUser.require().id()),
                report.cycle(),
                format,
                grounds,
                report.scopeNote(),
                report.departments().size()));

        return report;
    }

    /**
     * Leadership: the organisation, in totals.
     *
     * <p>The rating distribution is organisation-wide and is not broken down by department, in
     * the file exactly as on the screen (P-7.5). An export is the easiest place for that rule
     * to be quietly dropped, because a spreadsheet invites one more column.
     */
    private ExportReport companyReport(Long cycleId) {
        LeadershipMetricsService.CycleMetrics metrics = leadershipMetrics.forCycle(cycleId);

        List<DepartmentRow> rows = metrics.departments().stream()
                .map(d -> new DepartmentRow(
                        d.departmentName(),
                        d.participants(),
                        d.selfReviewsSubmitted(),
                        d.managerReviewsSubmitted(),
                        d.ratingsSet(),
                        d.ratingsReleased()))
                .toList();

        return new ExportReport(
                metrics.cycle(),
                "Leadership",
                "The whole organisation",
                rows,
                metrics.ratingDistribution(),
                Instant.now(clock),
                currentUser.require().fullName());
    }

    /**
     * HR: the departments they were granted, and nothing else.
     *
     * <p>Both calls below are scoped by {@code grants(A)} inside their own queries, and both
     * exclude the caller's own participant row (P-6.3). The file inherits all of that by
     * construction rather than by this method remembering to apply it.
     */
    private ExportReport departmentReport(Long cycleId) {
        CycleMonitoringService.CycleReport report = monitoring.report(cycleId);

        List<DepartmentRow> rows = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (CycleMonitoringService.DepartmentReport department : report.departments()) {
            rows.add(new DepartmentRow(
                    department.departmentName(),
                    department.participants(),
                    department.selfReviewsSubmitted(),
                    department.managerReviewsSubmitted(),
                    department.ratingsSet(),
                    department.ratingsReleased()));
            names.add(department.departmentName());
        }

        Map<Rating, Long> distribution = dashboards.forCycle(cycleId,
                (cycle, scopeIsEmpty, participants, selfReviews, managerReviews,
                 peerReviews, ratingsSet, ratingsReleased, byRating) -> byRating);

        return new ExportReport(
                report.cycle(),
                "HR",
                names.isEmpty() ? "No departments in scope" : "Departments: " + String.join(", ", names),
                rows,
                distribution,
                Instant.now(clock),
                currentUser.require().fullName());
    }

    /**
     * One department's progress in a report. Counts only - no name of any person, no rating
     * belonging to anyone, nothing that could be traced to an individual.
     */
    public record DepartmentRow(String departmentName,
                                long participants,
                                long selfReviewsSubmitted,
                                long managerReviewsSubmitted,
                                long ratingsSet,
                                long ratingsReleased) {
    }

    /**
     * Everything a renderer needs, and nothing a renderer should decide.
     *
     * <p>The two formats are given the same model, so a PDF and a spreadsheet of the same cycle
     * cannot disagree. Any difference between them is layout.
     *
     * @param audience     "HR" or "Leadership", printed on the file. A reader who finds a
     *                     spreadsheet later should be able to tell which kind of report it is
     *                     without inferring it from the columns
     * @param scopeNote    what the file covers, in words, so the scope survives outside the
     *                     system that applied it
     * @param generatedFor the person who ran it, printed on the file for the same reason
     * @param ratingDistribution ratings across everybody in scope. Never per department: for
     *                     Leadership because P-7.5 forbids it, and for HR because that is what
     *                     their dashboard shows and the two must not diverge
     */
    public record ExportReport(ReviewCycle cycle,
                               String audience,
                               String scopeNote,
                               List<DepartmentRow> departments,
                               Map<Rating, Long> ratingDistribution,
                               Instant generatedAt,
                               String generatedFor) {
    }
}
