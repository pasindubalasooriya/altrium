package com.altrium.reminder;

import com.altrium.review.CycleParticipant;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * What falls due, and who is responsible for it (scenario section 13).
 *
 * <p>A narrow {@link Repository}, like the metrics and dashboard ones: the only methods are the
 * four below, so nothing here can return a row about a person's review content by accident. Each
 * returns a name, an address and a deadline, and never a word anybody wrote.
 *
 * <h2>Every suppression rule that can be a predicate is one</h2>
 *
 * <p>The sweep runs with no caller, so there is no authorization decision to make. What there
 * is instead is a set of rules about who may be told what, and those are in the {@code WHERE}
 * clauses rather than in a filter afterwards:
 *
 * <ul>
 *   <li><b>Only the person responsible.</b> Each query selects one recipient per row and it is
 *       the person who has to act. A manager is not told their team is behind, because no query
 *       here produces that row.</li>
 *   <li><b>Not if it is already done.</b> A submitted review or an approved goal does not match.</li>
 *   <li><b>Not if the plan is not yet visible.</b> An improvement goal on a plan HR have not
 *       co-signed is excluded, because the employee has not been told the plan exists (P-5.3)
 *       and an email would announce it.</li>
 *   <li><b>Not to a deactivated person.</b> Their rows survive (P-0.7); their inbox does not
 *       need chasing.</li>
 * </ul>
 *
 * <p>Note what is <em>not</em> here: no query asks the subject to chase their peers, and none
 * reports how many peer reviews are outstanding about anybody. A subject may not learn that
 * count (P-3.3), and an email is the easiest place to leak it.
 */
public interface ReminderRepository extends Repository<CycleParticipant, Long> {

    /**
     * One thing somebody has to do, and when.
     *
     * @param about whom or what the task concerns: a colleague's name for a review of somebody
     *              else, a goal's title for a goal. Empty for a person's own self-review, which
     *              is about nobody but them
     */
    interface DueItem {
        Long getItemId();

        Long getRecipientId();

        String getRecipientName();

        String getRecipientEmail();

        String getAbout();

        LocalDate getDueDate();
    }

    /**
     * Self-reviews not yet submitted, in cycles closing on one of the given dates.
     *
     * <p>The left join is what makes "never started" and "saved a draft" the same case. Both
     * leave {@code submittedAt} null, both mean the employee still has to act, and a query that
     * only found missing rows would stop reminding the moment somebody typed one sentence.
     */
    @Query("""
            SELECT p.id                AS itemId,
                   s.id                AS recipientId,
                   s.fullName          AS recipientName,
                   s.email             AS recipientEmail,
                   ''                  AS about,
                   c.endDate           AS dueDate
            FROM CycleParticipant p
            JOIN p.cycle c
            JOIN p.subject s
            LEFT JOIN SelfReview sr ON sr.cycle = c AND sr.subject = s
            WHERE c.status = com.altrium.review.CycleStatus.OPEN
              AND c.endDate IN :dueDates
              AND sr.submittedAt IS NULL
              AND s.active = true
            """)
    List<DueItem> selfReviewsDue(@Param("dueDates") Collection<LocalDate> dueDates);

    /**
     * Manager reviews not yet submitted. The reminder goes to the manager, about the report.
     *
     * <p>The join to {@code s.manager} is an inner join, so somebody at the top of the chain
     * with no manager produces no row rather than an unaddressed email.
     */
    @Query("""
            SELECT p.id                AS itemId,
                   m.id                AS recipientId,
                   m.fullName          AS recipientName,
                   m.email             AS recipientEmail,
                   s.fullName          AS about,
                   c.endDate           AS dueDate
            FROM CycleParticipant p
            JOIN p.cycle c
            JOIN p.subject s
            JOIN s.manager m
            LEFT JOIN ManagerReview mr ON mr.cycle = c AND mr.subject = s
            WHERE c.status = com.altrium.review.CycleStatus.OPEN
              AND c.endDate IN :dueDates
              AND mr.submittedAt IS NULL
              AND m.active = true
              AND s.active = true
            """)
    List<DueItem> managerReviewsDue(@Param("dueDates") Collection<LocalDate> dueDates);

    /**
     * Peer reviews not yet submitted. One row per assignment, so each peer is told about their
     * own work and nothing about the other's.
     *
     * <p>Naming the subject is right here and is not a leak: a peer already knows whom they were
     * asked to review, because they have to write about them. What they are never told is who
     * else was assigned (P-3.9), and no row here carries it.
     */
    @Query("""
            SELECT a.id                AS itemId,
                   pe.id               AS recipientId,
                   pe.fullName         AS recipientName,
                   pe.email            AS recipientEmail,
                   s.fullName          AS about,
                   c.endDate           AS dueDate
            FROM PeerAssignment a
            JOIN a.cycle c
            JOIN a.subject s
            JOIN a.peer pe
            LEFT JOIN PeerReview pr ON pr.cycle = c AND pr.subject = s AND pr.peer = pe
            WHERE c.status = com.altrium.review.CycleStatus.OPEN
              AND c.endDate IN :dueDates
              AND pr.submittedAt IS NULL
              AND pe.active = true
              AND s.active = true
            """)
    List<DueItem> peerReviewsDue(@Param("dueDates") Collection<LocalDate> dueDates);

    /**
     * Development goals falling due on the employee's own plan.
     *
     * <p>Three conditions carry the rules. {@code AGREED} because a goal the manager has drafted
     * or submitted is not yet the employee's to work on, and reminding them about one would show
     * them a goal they have not accepted (P-5.9). {@code OPEN} because an approved goal is
     * finished. And the plan must be {@code ACTIVE}: a development plan suspended under an
     * improvement plan is on hold, and chasing its goals would contradict the suspension - as
     * well as hinting at it, for an employee whose improvement plan is not yet co-signed.
     */
    @Query("""
            SELECT g.id                AS itemId,
                   u.id                AS recipientId,
                   u.fullName          AS recipientName,
                   u.email             AS recipientEmail,
                   g.title             AS about,
                   g.targetDate        AS dueDate
            FROM PlanGoal g
            JOIN g.plan dp
            JOIN dp.user u
            WHERE g.targetDate IN :dueDates
              AND g.status = com.altrium.plan.GoalStatus.OPEN
              AND g.agreement = com.altrium.plan.GoalAgreement.AGREED
              AND dp.status = com.altrium.plan.PlanStatus.ACTIVE
              AND u.active = true
            """)
    List<DueItem> developmentGoalsDue(@Param("dueDates") Collection<LocalDate> dueDates);

    /**
     * Improvement goals falling due.
     *
     * <p><strong>{@code cosignedAt IS NOT NULL} is the important line in this file.</strong> A
     * plan HR have not co-signed is invisible to the employee (P-5.3), so an email about one of
     * its goals would announce the plan that the console is carefully withholding - and would do
     * it outside the system, where no gate could take it back. Enforced here, in the query, for
     * the same reason the read is: a check further up is a check somebody can forget to make.
     *
     * <p>There is no agreement condition, because an improvement goal has no agreement step. A
     * PIP is put to an employee rather than agreed with them.
     */
    @Query("""
            SELECT g.id                AS itemId,
                   u.id                AS recipientId,
                   u.fullName          AS recipientName,
                   u.email             AS recipientEmail,
                   g.title             AS about,
                   g.targetDate        AS dueDate
            FROM PlanGoal g
            JOIN g.improvementPlan ip
            JOIN ip.user u
            WHERE g.targetDate IN :dueDates
              AND g.status = com.altrium.plan.GoalStatus.OPEN
              AND ip.status = com.altrium.plan.ImprovementStatus.ACTIVE
              AND ip.cosignedAt IS NOT NULL
              AND u.active = true
            """)
    List<DueItem> improvementGoalsDue(@Param("dueDates") Collection<LocalDate> dueDates);
}
