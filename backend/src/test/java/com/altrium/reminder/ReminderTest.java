package com.altrium.reminder;

import com.altrium.org.AppUser;
import com.altrium.org.Department;
import com.altrium.org.Role;
import com.altrium.plan.GoalAgreement;
import com.altrium.plan.GoalStatus;
import com.altrium.review.Rating;
import com.altrium.review.ReviewCycle;
import com.altrium.testsupport.OrgFixture;
import com.altrium.testsupport.PlanFixture;
import com.altrium.testsupport.ReviewFixture;
import com.altrium.testsupport.StubJwtDecoderConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * Feature 17 - email deadline reminders.
 *
 * <p>These call {@code sweep(today)} directly with a fixed date rather than waiting for a cron
 * trigger, which is the whole reason the job is a two-line class holding a clock. The sender is
 * replaced with a recorder, so what is asserted is which emails <em>would</em> leave and to whom.
 *
 * <p>Most of what matters here is what is <strong>not</strong> sent. A reminder that fails to
 * arrive is an inconvenience; one that arrives about an improvement plan the employee has not
 * been shown is a disclosure that cannot be taken back.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class ReminderTest {

    /** One email that would have been sent. */
    record Sent(String to, String subject, String body) {
    }

    @Autowired
    private ReminderService reminders;

    @Autowired
    private OrgFixture org;

    @Autowired
    private ReviewFixture reviews;

    @Autowired
    private PlanFixture plans;

    @Autowired
    private EntityManager em;

    @MockitoBean
    private ReminderSender sender;

    private final List<Sent> outbox = new ArrayList<>();

    @BeforeEach
    void recordEverySend() {
        outbox.clear();
        doAnswer(call -> {
            outbox.add(new Sent(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
            return null;
        }).when(sender).send(anyString(), anyString(), anyString());
    }

    private List<String> subjectsFor(String email) {
        return outbox.stream().filter(s -> s.to().equals(email)).map(Sent::subject).toList();
    }

    /** The date a cycle ends, three days before which the sweep should fire. */
    private LocalDate threeDaysBefore(ReviewCycle cycle) {
        return cycle.getEndDate().minusDays(3);
    }

    // ------------------------------------------------------------------ what is sent

    @Test
    @DisplayName("Section 13: each person is reminded about their own outstanding task")
    void section_13_everybodyIsRemindedAboutTheirOwnTask() {
        Department engineering = org.department("Engineering");
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        john.setManager(jane);
        aisha.setManager(jane);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.assignPeer(cycle, john, aisha, jane);
        reviews.flush();

        reminders.sweep(threeDaysBefore(cycle));

        // Three tasks, three people, three emails. John owes a self-review, Jane owes the
        // manager review of John, Aisha owes peer feedback on John.
        assertThat(subjectsFor(john.getEmail())).containsExactly("Your self-review is due");
        assertThat(subjectsFor(jane.getEmail()))
                .containsExactly("Your review of " + john.getFullName() + " is due");
        assertThat(subjectsFor(aisha.getEmail()))
                .containsExactly("Your feedback on " + john.getFullName() + " is due");
    }

    @Test
    @DisplayName("Section 13: an agreed development goal reminds the employee whose plan it is")
    void section_13_developmentGoalRemindsTheEmployee() {
        Department engineering = org.department("Engineering");
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();

        LocalDate today = LocalDate.of(2026, 5, 1);
        plans.developmentGoal(john, "Lead a design review", today.plusDays(7),
                GoalAgreement.AGREED, GoalStatus.OPEN);
        em.flush();

        reminders.sweep(today);

        assertThat(subjectsFor(john.getEmail())).containsExactly("Goal due: Lead a design review");
        assertThat(subjectsFor(jane.getEmail())).isEmpty();
    }

    // ------------------------------------------------------------------ what is not sent

    @Test
    @DisplayName("P-5.3: no reminder about an improvement plan HR have not co-signed")
    void P_5_3_uncosignedImprovementPlanRemindsNobody() {
        Department engineering = org.department("Engineering");
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();

        LocalDate today = LocalDate.of(2026, 5, 1);
        var plan = plans.improvementPlan(john, jane, today.plusDays(30), false);
        plans.improvementGoal(plan, "Reduce defect rate", today.plusDays(3));
        em.flush();

        reminders.sweep(today);

        // The whole plan is invisible to John until HR sign it (P-5.3), and an email would
        // announce it outside the system, where no gate could take it back.
        assertThat(outbox).isEmpty();

        // Co-signed, the same goal on the same date does remind him.
        plans.cosign(plan, jane);
        em.flush();
        reminders.sweep(today);

        assertThat(subjectsFor(john.getEmail())).containsExactly("Goal due: Reduce defect rate");
    }

    @Test
    @DisplayName("P-5.9: a goal the employee has not agreed to does not remind them")
    void P_5_9_unagreedGoalRemindsNobody() {
        Department engineering = org.department("Engineering");
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();

        LocalDate today = LocalDate.of(2026, 5, 1);
        // Drafted and submitted, not yet agreed. A DRAFT goal is not returned to John at all,
        // and a PENDING one is his to accept rather than his to work on.
        plans.developmentGoal(john, "Draft goal", today.plusDays(3),
                GoalAgreement.DRAFT, GoalStatus.OPEN);
        plans.developmentGoal(john, "Pending goal", today.plusDays(3),
                GoalAgreement.PENDING, GoalStatus.OPEN);
        em.flush();

        reminders.sweep(today);

        assertThat(outbox).isEmpty();
    }

    @Test
    @DisplayName("A finished task reminds nobody")
    void finishedWorkRemindsNobody() {
        Department engineering = org.department("Engineering");
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.selfReview(cycle, john, "Submitted already.");
        reviews.managerReview(cycle, john, jane, "Also submitted.");
        reviews.flush();

        reminders.sweep(threeDaysBefore(cycle));

        assertThat(outbox).isEmpty();
    }

    @Test
    @DisplayName("P-0.7: a deactivated person is not chased")
    void P_0_7_deactivatedPeopleAreNotChased() {
        Department engineering = org.department("Engineering");
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser tara = org.userIn(engineering, "tara", Role.EMPLOYEE);
        tara.setManager(jane);
        tara.setActive(false);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, tara);
        reviews.flush();

        reminders.sweep(threeDaysBefore(cycle));

        // Neither Tara, whose account is closed, nor Jane about Tara's review.
        assertThat(outbox).isEmpty();
    }

    @Test
    @DisplayName("P-3.3: the subject is never told that peer feedback is outstanding")
    void P_3_3_subjectIsNotToldAboutOutstandingPeerFeedback() {
        Department engineering = org.department("Engineering");
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        AppUser diego = org.userIn(engineering, "diego", Role.EMPLOYEE);
        john.setManager(jane);
        aisha.setManager(jane);
        diego.setManager(jane);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.selfReview(cycle, john, "Done, so no self-review reminder.");
        reviews.assignPeer(cycle, john, aisha, jane);
        reviews.assignPeer(cycle, john, diego, jane);
        reviews.flush();

        reminders.sweep(threeDaysBefore(cycle));

        // Two peers are chased. John hears nothing at all: a reminder that "your peer feedback
        // is still outstanding" would hand him the count P-3.3 withholds everywhere else.
        assertThat(subjectsFor(aisha.getEmail())).hasSize(1);
        assertThat(subjectsFor(diego.getEmail())).hasSize(1);
        assertThat(subjectsFor(john.getEmail())).isEmpty();

        // And no peer is told anything about the other.
        assertThat(outbox).noneMatch(s -> s.body().contains(diego.getFullName())
                && s.to().equals(aisha.getEmail()));
    }

    @Test
    @DisplayName("A manager is not told their team is behind; only the person responsible is")
    void managersAreNotToldTheirTeamIsBehind() {
        Department engineering = org.department("Engineering");
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.managerReview(cycle, john, jane, "Jane has done hers.");
        reviews.flush();

        reminders.sweep(threeDaysBefore(cycle));

        // John still owes his self-review. Jane owes nothing and hears nothing about it.
        assertThat(subjectsFor(john.getEmail())).hasSize(1);
        assertThat(subjectsFor(jane.getEmail())).isEmpty();
    }

    // ------------------------------------------------------------------ sending once

    @Test
    @DisplayName("Running the sweep twice in a day sends nothing the second time")
    void aSecondSweepOnTheSameDaySendsNothing() {
        Department engineering = org.department("Engineering");
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.flush();

        LocalDate today = threeDaysBefore(cycle);

        ReminderService.SweepResult first = reminders.sweep(today);
        assertThat(first.sent()).isEqualTo(2);

        int afterFirst = outbox.size();
        ReminderService.SweepResult second = reminders.sweep(today);

        assertThat(second.sent()).isZero();
        assertThat(second.alreadySent()).isEqualTo(2);
        assertThat(outbox).hasSize(afterFirst);
    }

    @Test
    @DisplayName("A deadline is reminded about again on the next offset, not silenced")
    void thenextOffsetRemindsAgain() {
        Department engineering = org.department("Engineering");
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.flush();

        reminders.sweep(cycle.getEndDate().minusDays(7));
        reminders.sweep(cycle.getEndDate().minusDays(3));
        reminders.sweep(cycle.getEndDate().minusDays(1));
        reminders.sweep(cycle.getEndDate());

        // Four offsets, four reminders each. Bounded, which a "due within a week" window is not.
        assertThat(subjectsFor(john.getEmail())).hasSize(4);
    }

    @Test
    @DisplayName("A day that is not an offset sends nothing")
    void nonOffsetDaysSendNothing() {
        Department engineering = org.department("Engineering");
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.flush();

        reminders.sweep(cycle.getEndDate().minusDays(5));

        assertThat(outbox).isEmpty();
    }

    @Test
    @DisplayName("A failed send is not recorded, so the next sweep tries again")
    void aFailedSendIsRetried() {
        Department engineering = org.department("Engineering");
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.flush();

        LocalDate today = threeDaysBefore(cycle);

        doThrow(new IllegalStateException("Resend is down")).when(sender)
                .send(anyString(), anyString(), anyString());

        ReminderService.SweepResult failed = reminders.sweep(today);
        assertThat(failed.sent()).isZero();
        assertThat(failed.failed()).isEqualTo(2);

        // The sender recovers. Nothing was logged, so the same day's sweep sends them now -
        // which is what makes an outage cost a delay rather than a permanently missed reminder.
        recordEverySend();
        ReminderService.SweepResult recovered = reminders.sweep(today);
        assertThat(recovered.sent()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ what the email says

    @Test
    @DisplayName("The email carries a task and a date, and no review or plan content")
    void theEmailCarriesNoContent() {
        Department engineering = org.department("Engineering");
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.selfReview(cycle, john, "A very private account of my year.");
        reviews.releasedRating(cycle, john, jane, Rating.NEEDS_IMPROVEMENT);
        reviews.flush();

        reminders.sweep(threeDaysBefore(cycle));

        // Jane still owes the manager review, so one email goes out. It must not carry John's
        // words or his rating: mail leaves this system's access control behind for good.
        assertThat(outbox).hasSize(1);
        String body = outbox.get(0).body();
        assertThat(body).doesNotContain("A very private account");
        assertThat(body).doesNotContain("NEEDS_IMPROVEMENT", "Needs improvement");
        assertThat(body).contains(john.getFullName());
        assertThat(body).contains("Open Altrium");
    }
}
