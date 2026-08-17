package com.altrium.web.admin;

import com.altrium.review.Cohort;
import com.altrium.review.CohortMember;
import com.altrium.review.CycleService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Feature 5 - cycle and cohort configuration (P-6.1, P-9.3). Super Admin only.
 *
 * <p><strong>No {@code @PreAuthorize} here, unlike the older admin controllers.</strong> The
 * capability {@code CONFIGURE_CYCLE} already names the Super Admin as its only grounds, and
 * {@link CycleService} requires it on every method. Adding the annotation as well would put the
 * same rule in two places, and the day somebody widens one the other would still read as
 * though it were enforced. The capability is the authority; this class is transport.
 *
 * <p>Note what a Super Admin gets from all this power and what they do not: they decide who is
 * reviewed and when, and can read none of it (P-9.4). Configuring the cycle and seeing inside
 * it are different rights, and this controller only holds the first.
 */
@RestController
@RequestMapping("/api/admin")
public class CycleAdminController {

    private final CycleService cycles;

    public CycleAdminController(CycleService cycles) {
        this.cycles = cycles;
    }

    // ---------------------------------------------------------------- wire shapes

    public record CycleView(
            Long id,
            String label,
            int financialYear,
            int quadrimesterNo,
            String status,
            LocalDate startDate,
            LocalDate endDate,
            Instant openedAt,
            Instant closedAt) {

        static CycleView of(com.altrium.review.ReviewCycle cycle) {
            return new CycleView(
                    cycle.getId(),
                    cycle.label(),
                    cycle.getFinancialYear(),
                    cycle.getQuadrimesterNo(),
                    cycle.getStatus().name(),
                    cycle.getStartDate(),
                    cycle.getEndDate(),
                    cycle.getOpenedAt(),
                    cycle.getClosedAt());
        }
    }

    public record CohortView(Long id, String name, Integer quadrimesterNo) {

        static CohortView of(Cohort cohort) {
            return new CohortView(cohort.getId(), cohort.getName(), cohort.getQuadrimesterNo());
        }
    }

    public record CohortMemberView(Long userId, String fullName, Long cohortId, String cohortName) {

        static CohortMemberView of(CohortMember member) {
            return new CohortMemberView(
                    member.getUser().getId(),
                    member.getUser().getFullName(),
                    member.getCohort().getId(),
                    member.getCohort().getName());
        }
    }

    public record CycleRequest(
            @NotNull Integer financialYear,
            @NotNull Integer quadrimesterNo,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate) {
    }

    public record RescheduleRequest(@NotNull LocalDate startDate, @NotNull LocalDate endDate) {
    }

    /** {@code quadrimesterNo} may be null: a cohort attached to no quadrimester is never swept. */
    public record CohortRequest(@NotBlank String name, Integer quadrimesterNo) {
    }

    public record QuadrimesterRequest(Integer quadrimesterNo) {
    }

    public record MemberRequest(@NotNull Long userId) {
    }

    // ---------------------------------------------------------------- cycles

    @PostMapping("/cycles")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Configure a quadrimester's cycle; it opens on its date, not now")
    public CycleView createCycle(@Valid @RequestBody CycleRequest request) {
        return cycles.createCycle(
                request.financialYear(),
                request.quadrimesterNo(),
                request.startDate(),
                request.endDate(),
                CycleView::of);
    }

    /**
     * Moves a cycle's dates. 409 once it has opened (P-6.2) - the caller has the permission,
     * and it is the cycle's state that refuses.
     */
    @PutMapping("/cycles/{cycleId}/dates")
    @Operation(summary = "Reschedule a cycle; refused once it has opened (P-6.2)")
    public CycleView reschedule(@PathVariable Long cycleId,
                                @Valid @RequestBody RescheduleRequest request) {
        return cycles.reschedule(cycleId, request.startDate(), request.endDate(), CycleView::of);
    }

    /**
     * Opens a cycle now rather than on its date.
     *
     * <p>The same code path the sweep takes, entered by a person. It is here because a cycle
     * that can only be opened by waiting until tomorrow cannot be demonstrated today.
     */
    @PostMapping("/cycles/{cycleId}/open")
    @Operation(summary = "Open a cycle immediately, running the sweep's own intake")
    public CycleView openNow(@PathVariable Long cycleId) {
        return cycles.openNow(cycleId, CycleView::of);
    }

    @PostMapping("/cycles/{cycleId}/close")
    @Operation(summary = "Close a cycle; its artifacts stay readable to whoever could read them")
    public CycleView close(@PathVariable Long cycleId) {
        return cycles.close(cycleId, CycleView::of);
    }

    // ---------------------------------------------------------------- cohorts

    @GetMapping("/cohorts")
    @Operation(summary = "Every cohort and the quadrimester it is attached to")
    public List<CohortView> listCohorts() {
        return cycles.listCohorts(CohortView::of);
    }

    @PostMapping("/cohorts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a cohort, optionally attached to a quadrimester")
    public CohortView createCohort(@Valid @RequestBody CohortRequest request) {
        return cycles.createCohort(request.name(), request.quadrimesterNo(), CohortView::of);
    }

    /** A null {@code quadrimesterNo} detaches the cohort, which is how it stops being swept. */
    @PutMapping("/cohorts/{cohortId}/quadrimester")
    @Operation(summary = "Attach a cohort to a quadrimester, or detach it with null")
    public CohortView retarget(@PathVariable Long cohortId,
                               @RequestBody QuadrimesterRequest request) {
        return cycles.retargetCohort(cohortId, request.quadrimesterNo(), CohortView::of);
    }

    @GetMapping("/cohorts/{cohortId}/members")
    @Operation(summary = "Who is in a cohort")
    public List<CohortMemberView> listMembers(@PathVariable Long cohortId) {
        return cycles.listMembers(cohortId, CohortMemberView::of);
    }

    /**
     * Adds an employee, moving them out of any other cohort - a person belongs to exactly one
     * (scenario section 4). Moving somebody who is mid-cycle is refused; see the delete below.
     */
    @PostMapping("/cohorts/{cohortId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Put an employee in this cohort, moving them out of any other")
    public CohortMemberView addMember(@PathVariable Long cohortId,
                                      @Valid @RequestBody MemberRequest request) {
        return cycles.addMember(cohortId, request.userId(), CohortMemberView::of);
    }

    /**
     * Removes an employee from their cohort.
     *
     * <p>409 while they are in an open cycle. Scenario section 15.4 - what becomes of the
     * reviews already written about somebody removed mid-cycle - is still with the Product
     * Owner, and this refusal is what keeps the question open instead of answering it by
     * accident. The path is not built, deliberately.
     */
    @DeleteMapping("/cohorts/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove an employee from their cohort; refused mid-cycle (section 15.4)")
    public void removeMember(@PathVariable Long userId) {
        cycles.removeMember(userId);
    }
}
