package com.altrium.web.hr;

import com.altrium.review.CycleMonitoringService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Feature 6 - HR cycle monitoring (P-6.3).
 *
 * <p>No {@code @PreAuthorize}, for the same reason as the review endpoints: "HR" is not the
 * rule. The rule is "the departments this person holds a grant for at this moment, excluding
 * their own unless the grant is explicit, and never their own case", which no annotation can
 * say. {@link CycleMonitoringService} carries all of it into the {@code WHERE} clause.
 *
 * <p>The response answers the two operational questions scenario section 4 gives HR: did the
 * cycle fire, and who is behind. An open cycle showing zero participants is the visible form of
 * a cycle the Super Admin configured wrongly, which is the trade-off section 15.4 accepts.
 */
@RestController
@RequestMapping("/api/cycles")
public class CycleMonitoringController {

    private final CycleMonitoringService monitoring;

    public CycleMonitoringController(CycleMonitoringService monitoring) {
        this.monitoring = monitoring;
    }

    public record CycleStatusView(
            Long cycleId,
            String label,
            String status,
            LocalDate startDate,
            LocalDate endDate,
            Instant openedAt,
            Instant closedAt,
            boolean fired) {
    }

    /** Counts only. Nothing in this shape can carry a name, a rating or a line of feedback. */
    public record DepartmentProgressView(
            Long departmentId,
            String departmentName,
            long participants,
            long selfReviewsSubmitted,
            long managerReviewsSubmitted,
            long peerAssignmentsMade,
            long peerReviewsSubmitted,
            long ratingsSet,
            long ratingsReleased) {

        static DepartmentProgressView of(CycleMonitoringService.DepartmentReport report) {
            return new DepartmentProgressView(
                    report.departmentId(),
                    report.departmentName(),
                    report.participants(),
                    report.selfReviewsSubmitted(),
                    report.managerReviewsSubmitted(),
                    report.peerAssignmentsMade(),
                    report.peerReviewsSubmitted(),
                    report.ratingsSet(),
                    report.ratingsReleased());
        }
    }

    public record CycleMonitoringView(CycleStatusView cycle, List<DepartmentProgressView> departments) {

        static CycleMonitoringView of(CycleMonitoringService.CycleReport report) {
            var cycle = report.cycle();
            return new CycleMonitoringView(
                    new CycleStatusView(
                            cycle.getId(),
                            cycle.label(),
                            cycle.getStatus().name(),
                            cycle.getStartDate(),
                            cycle.getEndDate(),
                            cycle.getOpenedAt(),
                            cycle.getClosedAt(),
                            cycle.isOpened()),
                    report.departments().stream().map(DepartmentProgressView::of).toList());
        }
    }

    /**
     * Progress across every department the caller may monitor.
     *
     * <p>A caller holding no grants gets an empty department list, exactly as the review list
     * returns no rows to someone who may see none. The targeted endpoint below is where a
     * denial is a denial, because there the caller named a department.
     */
    @GetMapping("/{cycleId}/monitoring")
    @Operation(summary = "Cycle progress across the caller's granted departments (P-6.3)")
    public CycleMonitoringView monitor(@PathVariable Long cycleId) {
        return CycleMonitoringView.of(monitoring.report(cycleId));
    }

    /** 403 when the department is not granted, or is the caller's own without an explicit grant. */
    @GetMapping("/{cycleId}/monitoring/{departmentId}")
    @Operation(summary = "Cycle progress for one named department; 403 when it is not granted")
    public CycleMonitoringView monitorDepartment(@PathVariable Long cycleId,
                                                 @PathVariable Long departmentId) {
        return CycleMonitoringView.of(monitoring.report(cycleId, departmentId));
    }
}
