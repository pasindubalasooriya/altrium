package com.altrium.review;

import com.altrium.auth.AuthorizationService;
import com.altrium.auth.Capability;
import com.altrium.auth.CurrentUserService;
import com.altrium.config.ConflictApiException;
import com.altrium.config.NotFoundApiException;
import com.altrium.config.ValidationApiException;
import com.altrium.org.AppUser;
import com.altrium.org.AppUserRepository;
import com.altrium.org.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Feature 5 - configuring cycles and cohorts, and opening a cycle when its date arrives.
 *
 * <p><strong>The only thing in Altrium that writes a cycle's status</strong>, mirroring the
 * rule that only {@code PlanService} writes a plan's. A status field written from two places
 * is a state machine with no single definition, and every question about it - "can this still
 * be rescheduled?", "has intake run?" - then has to be answered by reading both.
 *
 * <h2>Two ways in, one way through</h2>
 *
 * <p>A cycle opens either because the sweep found its date had arrived, or because the Super
 * Admin opened it by hand. Both land on the same private {@link #open} method. That is
 * deliberate: a manual "open now" written as its own path would be a second intake with its
 * own idea of who is eligible, and the exclusions at intake (P-0.7, P-1.5) are exactly the
 * kind of thing that gets implemented once and forgotten the second time.
 *
 * <p>The authorization difference sits at the entrance, where it belongs. {@link #openNow}
 * requires {@link Capability#CONFIGURE_CYCLE}; {@link #sweepDueCycles} requires nothing,
 * because there is nobody to require it of (P-6.4). The sweep bypasses the caller checks and
 * remains bound by every domain invariant below them - which is precisely why those invariants
 * live in the intake query rather than in a controller.
 */
@Service
@Transactional
public class CycleService {

    private static final Logger log = LoggerFactory.getLogger(CycleService.class);

    private final AuthorizationService authorization;
    private final CurrentUserService currentUser;
    private final ReviewCycleRepository cycles;
    private final CycleParticipantRepository participants;
    private final CohortRepository cohorts;
    private final CohortMemberRepository members;
    private final AppUserRepository users;

    public CycleService(AuthorizationService authorization,
                        CurrentUserService currentUser,
                        ReviewCycleRepository cycles,
                        CycleParticipantRepository participants,
                        CohortRepository cohorts,
                        CohortMemberRepository members,
                        AppUserRepository users) {
        this.authorization = authorization;
        this.currentUser = currentUser;
        this.cycles = cycles;
        this.participants = participants;
        this.cohorts = cohorts;
        this.members = members;
        this.users = users;
    }

    // ================================================================= cycle configuration

    /**
     * Configures a quadrimester's cycle (P-6.1). It is not open yet: the date is a promise
     * that the sweep will keep.
     */
    public <T> T createCycle(int financialYear, int quadrimesterNo,
                             LocalDate startDate, LocalDate endDate,
                             Function<ReviewCycle, T> mapper) {

        authorization.requireGlobal(Capability.CONFIGURE_CYCLE);

        requireQuadrimester(quadrimesterNo);
        requireDateOrder(startDate, endDate);

        cycles.findByFinancialYearAndQuadrimesterNo(financialYear, quadrimesterNo)
                .ifPresent(existing -> {
                    throw new ConflictApiException(
                            "FY" + financialYear + " Q" + quadrimesterNo + " is already configured");
                });

        ReviewCycle cycle = new ReviewCycle(financialYear, quadrimesterNo, startDate, endDate);
        cycle.setCreatedBy(users.findById(currentUser.require().id()).orElse(null));
        return mapper.apply(cycles.save(cycle));
    }

    /**
     * Moves a cycle's dates (P-6.2).
     *
     * <p>Refused once {@code opened_at} is set, and the refusal is a 409 rather than a 403: the
     * Super Admin has every right to configure cycles, and it is the state of this one that
     * forbids the write. Answering 403 would tell them they lack a permission they hold.
     *
     * <p>The rule has teeth because reviews are already in flight by then. Moving the start
     * date of a cycle whose self-reviews are half written would silently redate work that has
     * already happened.
     */
    public <T> T reschedule(Long cycleId, LocalDate startDate, LocalDate endDate,
                            Function<ReviewCycle, T> mapper) {

        authorization.requireGlobal(Capability.CONFIGURE_CYCLE);

        ReviewCycle cycle = requireCycle(cycleId);
        if (cycle.isOpened()) {
            throw new ConflictApiException(
                    cycle.label() + " opened on " + cycle.getOpenedAt()
                            + " and its dates are now fixed (P-6.2)");
        }

        requireDateOrder(startDate, endDate);
        cycle.setStartDate(startDate);
        cycle.setEndDate(endDate);
        return mapper.apply(cycle);
    }

    /**
     * Opens a cycle immediately, without waiting for its date (P-6.1).
     *
     * <p>Not a shortcut around the sweep but the same code path, entered by a person instead of
     * a clock. It exists because a cycle that can only be opened by waiting for tomorrow cannot
     * be demonstrated or tested today.
     */
    public <T> T openNow(Long cycleId, Function<ReviewCycle, T> mapper) {
        authorization.requireGlobal(Capability.CONFIGURE_CYCLE);

        ReviewCycle cycle = requireCycle(cycleId);
        if (cycle.isOpened()) {
            throw new ConflictApiException(cycle.label() + " is already open");
        }
        open(cycle);
        return mapper.apply(cycle);
    }

    /** Closes a cycle. Nothing reopens it: the artifacts stay readable to whoever could read them. */
    public <T> T close(Long cycleId, Function<ReviewCycle, T> mapper) {
        authorization.requireGlobal(Capability.CONFIGURE_CYCLE);

        ReviewCycle cycle = requireCycle(cycleId);
        if (!cycle.isOpened()) {
            throw new ConflictApiException(cycle.label() + " has not opened, so cannot be closed");
        }
        if (cycle.getStatus() == CycleStatus.CLOSED) {
            throw new ConflictApiException(cycle.label() + " is already closed");
        }
        cycle.setStatus(CycleStatus.CLOSED);
        cycle.setClosedAt(Instant.now());
        return mapper.apply(cycle);
    }

    // ================================================================= cohorts

    public <T> T createCohort(String name, Integer quadrimesterNo, Function<Cohort, T> mapper) {
        authorization.requireGlobal(Capability.CONFIGURE_CYCLE);

        if (quadrimesterNo != null) {
            requireQuadrimester(quadrimesterNo);
        }
        if (cohorts.existsByName(name)) {
            throw new ConflictApiException("A cohort named '" + name + "' already exists");
        }
        return mapper.apply(cohorts.save(new Cohort(name, quadrimesterNo)));
    }

    /**
     * Attaches a cohort to a quadrimester, or detaches it with null (scenario section 4).
     *
     * <p>Detaching is allowed at any time and affects only future intakes. It is not the
     * blocked section 15.4 path: nobody is being taken out of a cycle they are already in,
     * because {@link CycleParticipant} rows are a snapshot that this configuration no longer
     * reaches once intake has run.
     */
    public <T> T retargetCohort(Long cohortId, Integer quadrimesterNo, Function<Cohort, T> mapper) {
        authorization.requireGlobal(Capability.CONFIGURE_CYCLE);

        if (quadrimesterNo != null) {
            requireQuadrimester(quadrimesterNo);
        }
        Cohort cohort = requireCohort(cohortId);
        cohort.setQuadrimesterNo(quadrimesterNo);
        return mapper.apply(cohort);
    }

    /**
     * Puts an employee in a cohort, moving them out of any other (P-6.1).
     *
     * <p>A move rather than a second row, because a person belongs to exactly one cohort and
     * the schema enforces it. Doing it as an update keeps the operation honest at the API too:
     * "move Anna to the Q3 cohort" is one request, not a delete the caller has to remember.
     */
    public <T> T addMember(Long cohortId, Long userId, Function<CohortMember, T> mapper) {
        authorization.requireGlobal(Capability.CONFIGURE_CYCLE);

        Cohort cohort = requireCohort(cohortId);
        AppUser user = users.findById(userId)
                .orElseThrow(() -> new NotFoundApiException("No such user"));

        // P-1.5 / P-7.2 at the point of configuration, not only at intake. A Leadership member
        // sitting in a cohort would read as an intention the system silently refuses to carry
        // out every quarter, which is worse than refusing it once, here, where it is visible.
        if (user.getRoles().contains(Role.LEADERSHIP)) {
            throw new ValidationApiException(
                    user.getFullName() + " is Leadership and is never a reviewee (P-1.5)");
        }
        if (user.getRoles().contains(Role.SUPER_ADMIN)) {
            // Same reasoning one role along (P-9.5). The Super Admin is a dedicated platform
            // account, so enrolling one would create a participant whose every artifact the
            // authorization layer refuses - a cohort row that quietly does nothing.
            throw new ValidationApiException(
                    user.getFullName() + " is the Super Admin and is never a reviewee (P-9.5)");
        }
        if (!user.isActive()) {
            throw new ValidationApiException(
                    "A deactivated user cannot be added to a cohort (P-0.7)");
        }

        CohortMember existing = members.findByUserId(userId).orElse(null);
        if (existing == null) {
            return mapper.apply(members.save(new CohortMember(cohort, user)));
        }
        if (existing.getCohort().getId().equals(cohortId)) {
            throw new ConflictApiException(
                    user.getFullName() + " is already in " + cohort.getName());
        }
        requireNoReviewInFlight(user, "moved between cohorts");
        existing.setCohort(cohort);
        return mapper.apply(existing);
    }

    /**
     * Takes an employee out of their cohort.
     *
     * <p>Refused while they are in an open cycle. That is the <strong>blocked scenario section
     * 15.4 path</strong>: what should happen to in-flight reviews when the Super Admin removes
     * somebody mid-cycle is unanswered by the Product Owner, and the choices are not equivalent
     * - discarding the peer feedback already written about them, or leaving a review running
     * for somebody no longer in the cohort, are different products. Refusing keeps the question
     * open. Whichever answer comes back can then be implemented deliberately, rather than
     * discovered later as whatever the code happened to do.
     */
    public void removeMember(Long userId) {
        authorization.requireGlobal(Capability.CONFIGURE_CYCLE);

        CohortMember member = members.findByUserId(userId)
                .orElseThrow(() -> new NotFoundApiException("That user is in no cohort"));
        requireNoReviewInFlight(member.getUser(), "removed from a cohort");
        members.delete(member);
    }

    @Transactional(readOnly = true)
    public <T> List<T> listCohorts(Function<Cohort, T> mapper) {
        authorization.requireGlobal(Capability.CONFIGURE_CYCLE);
        return cohorts.findAll().stream().map(mapper).toList();
    }

    @Transactional(readOnly = true)
    public <T> List<T> listMembers(Long cohortId, Function<CohortMember, T> mapper) {
        authorization.requireGlobal(Capability.CONFIGURE_CYCLE);
        requireCohort(cohortId);
        return members.findByCohortIdOrderByUserFullNameAsc(cohortId).stream().map(mapper).toList();
    }

    // ================================================================= the sweep (P-6.4)

    /**
     * Opens every cycle whose configured date has arrived and which is not already open.
     *
     * <p>Called by {@link CycleSweepJob} with no user in the security context, so it performs
     * no authorization check - there is nobody to check (P-6.4). Everything that constrains it
     * is a domain invariant, applied inside the intake query.
     *
     * <p>Two properties the scenario asks for explicitly, both of which come from the selection
     * predicate rather than from any bookkeeping:
     * <ul>
     *   <li><b>Idempotent</b> - the predicate includes {@code opened_at IS NULL}, so a second
     *       run finds nothing. There is no "already swept today" flag to get out of step.</li>
     *   <li><b>Arrived or passed</b>, never equals today - so a run missed at the weekend
     *       resolves on the next sweep instead of skipping the cycle forever.</li>
     * </ul>
     */
    public SweepResult sweepDueCycles(LocalDate today) {
        List<ReviewCycle> due = cycles.findByOpenedAtIsNullAndStartDateLessThanEqual(today);

        List<String> opened = new ArrayList<>();
        int intake = 0;
        for (ReviewCycle cycle : due) {
            intake += open(cycle);
            opened.add(cycle.label());
        }

        if (!opened.isEmpty()) {
            log.info("Cycle sweep for {} opened {} ({} participants)", today, opened, intake);
        }
        return new SweepResult(today, opened, intake);
    }

    // ================================================================= internals

    /**
     * Stamps the cycle open and runs intake. The single place a cycle becomes OPEN.
     *
     * @return how many participants intake added
     */
    private int open(ReviewCycle cycle) {
        cycle.setOpenedAt(Instant.now());
        cycle.setStatus(CycleStatus.OPEN);
        return intake(cycle);
    }

    /**
     * Takes the quadrimester's cohorts into the cycle.
     *
     * <p>The eligibility rules are in the query (see {@link
     * CohortMemberRepository#findEligibleForQuadrimester}), not applied to its results. The
     * existence check on top makes intake safe to re-run: it is belt-and-braces over the unique
     * key on {@code (cycle_id, subject_id)}, which is what actually guarantees it.
     *
     * <p>A cycle whose quadrimester has no cohort attached opens with nobody in it, rather than
     * failing. That is the state scenario section 15.4 calls out as visible to HR through cycle
     * monitoring: an open cycle with zero participants is a configuration mistake the people
     * responsible for the cycle can see and fix, whereas a sweep that aborted would be silent.
     */
    private int intake(ReviewCycle cycle) {
        List<AppUser> eligible = members.findEligibleForQuadrimester(
                cycle.getQuadrimesterNo(), Role.LEADERSHIP);

        int added = 0;
        for (AppUser subject : eligible) {
            if (participants.existsByCycleIdAndSubjectId(cycle.getId(), subject.getId())) {
                continue;
            }
            // The department is snapshotted here, at intake, and never re-read. A transfer in
            // March must not move this quarter's review into a department whose HR user had
            // nothing to do with it.
            participants.save(new CycleParticipant(cycle, subject, subject.getDepartment()));
            added++;
        }
        return added;
    }

    private void requireNoReviewInFlight(AppUser user, String operation) {
        if (participants.existsBySubjectIdAndCycleStatus(user.getId(), CycleStatus.OPEN)) {
            throw new ConflictApiException(
                    user.getFullName() + " is under review in an open cycle and cannot be "
                            + operation + " until it closes (scenario section 15.4 is unresolved)");
        }
    }

    private ReviewCycle requireCycle(Long cycleId) {
        return cycles.findById(cycleId)
                .orElseThrow(() -> new NotFoundApiException("No such cycle"));
    }

    private Cohort requireCohort(Long cohortId) {
        return cohorts.findById(cohortId)
                .orElseThrow(() -> new NotFoundApiException("No such cohort"));
    }

    private static void requireQuadrimester(int quadrimesterNo) {
        if (quadrimesterNo < 1 || quadrimesterNo > 3) {
            throw new ValidationApiException(
                    "The financial year has three quadrimesters; " + quadrimesterNo + " is not one of them");
        }
    }

    private static void requireDateOrder(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new ValidationApiException("A cycle needs both a start and an end date");
        }
        if (!endDate.isAfter(startDate)) {
            throw new ValidationApiException("A cycle's end date must fall after its start date");
        }
    }

    /**
     * What one sweep did. Returned rather than logged alone so the scheduled job, the admin
     * endpoint and the tests all observe the same thing.
     *
     * @param cyclesOpened labels, so a log line names the cycles rather than counting them
     */
    public record SweepResult(LocalDate on, List<String> cyclesOpened, int participantsAdded) {

        public boolean openedNothing() {
            return cyclesOpened.isEmpty();
        }
    }
}
