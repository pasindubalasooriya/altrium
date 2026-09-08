package com.altrium.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Feature 17 - email deadline reminders (scenario section 13).
 *
 * <p>"Notifications are email. Review-submission deadlines and plan goal deadlines fire as
 * emails, sent only to the person responsible. This is the direct fix for plans get forgotten."
 *
 * <h2>Fixed offsets, not a window</h2>
 *
 * <p>The sweep asks for deadlines that are exactly 7, 3, 1 and 0 days away, rather than for
 * everything due within a week. The difference matters for the overdue case: a window would
 * remind about a missed goal every single day until somebody closed it, and a reminder that
 * arrives daily is one people filter into a folder, taking the useful ones with it. Four emails
 * per deadline is a bounded promise.
 *
 * <p>It also makes the whole thing idempotent for free. A rerun on the same day asks for the
 * same four dates and finds every row already logged.
 *
 * <h2>Nothing here decides who may see what</h2>
 *
 * <p>The sweep has no caller, so there is no authorization decision to make and none is faked.
 * The rules that look like authorization - not to a deactivated person, not about an
 * uncosigned improvement plan, not to anybody but the person responsible - live in
 * {@link ReminderRepository}'s {@code WHERE} clauses, below the point where a caller would have
 * been, in exactly the way the cycle sweep's intake rules do (P-6.4).
 *
 * <h2>The email says almost nothing</h2>
 *
 * <p>A task and a date, and a link back into Altrium. No review text, no rating, no plan
 * content, no counts. Mail leaves the system's access control behind: anything put in the body
 * has escaped it, and cannot be withdrawn. The link is what takes the person back to a page
 * where the ordinary checks apply.
 */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    private static final DateTimeFormatter DUE = DateTimeFormatter.ofPattern("d MMMM yyyy");

    private final ReminderRepository due;
    private final ReminderLogRepository sent;
    private final ReminderLogWriter writer;
    private final ReminderSender sender;

    private final List<Integer> offsets;
    private final String appUrl;

    /**
     * Where every reminder goes instead of the person's own address, outside production.
     *
     * <p>Product Owner decision, 2026-09-06. The seeded people hold {@code @altrium.test}
     * addresses, which is a reserved domain that accepts no mail, so without this nothing could
     * be demonstrated at all.
     *
     * <p>Applied at the last moment, deliberately. The recipient is resolved exactly as it would
     * be in production and only the destination is substituted, so what is being exercised is
     * the real rule about who gets told. Blank means normal delivery, so **the safe direction is
     * the default**: forgetting to set this sends real mail to the right people, and forgetting
     * to unset it sends demo mail to one inbox. Only one of those is a disaster.
     */
    private final String redirectTo;

    public ReminderService(ReminderRepository due,
                           ReminderLogRepository sent,
                           ReminderLogWriter writer,
                           ReminderSender sender,
                           @Value("${altrium.reminders.days-before:7,3,1,0}") List<Integer> offsets,
                           @Value("${altrium.reminders.redirect-to:}") String redirectTo,
                           @Value("${altrium.app-url:http://localhost:5173}") String appUrl) {
        this.due = due;
        this.sent = sent;
        this.writer = writer;
        this.sender = sender;
        this.offsets = List.copyOf(offsets);
        this.redirectTo = redirectTo == null ? "" : redirectTo.trim();
        this.appUrl = appUrl;
    }

    /**
     * One day's reminders.
     *
     * <p><strong>Not one transaction, deliberately.</strong> Each send commits its own log row
     * through {@link ReminderLogWriter}, so a failure on the eleventh email does not roll back
     * the ten that have already left - which would mean sending them all again tomorrow. An
     * email is not transactional and pretending otherwise produces duplicates, which is the one
     * failure this feature must not have. Wrapping this method in a transaction would undo that.
     */
    public SweepResult sweep(LocalDate today) {
        Set<LocalDate> dueDates = new LinkedHashSet<>();
        for (int offset : offsets) {
            dueDates.add(today.plusDays(offset));
        }

        List<Candidate> candidates = new ArrayList<>();
        add(candidates, ReminderItemType.SELF_REVIEW, due.selfReviewsDue(dueDates));
        add(candidates, ReminderItemType.MANAGER_REVIEW, due.managerReviewsDue(dueDates));
        add(candidates, ReminderItemType.PEER_REVIEW, due.peerReviewsDue(dueDates));
        add(candidates, ReminderItemType.PLAN_GOAL, due.developmentGoalsDue(dueDates));
        add(candidates, ReminderItemType.PLAN_GOAL, due.improvementGoalsDue(dueDates));

        int sentCount = 0;
        int alreadySent = 0;
        int failed = 0;

        for (Candidate candidate : candidates) {
            if (sent.existsByItemTypeAndItemIdAndRecipientIdAndSentOn(
                    candidate.type(), candidate.item().getItemId(),
                    candidate.item().getRecipientId(), today)) {
                alreadySent++;
                continue;
            }

            try {
                sender.send(destination(candidate), subject(candidate), body(candidate, today));
                writer.record(candidate.type(), candidate.item().getItemId(),
                        candidate.item().getRecipientId(), candidate.item().getDueDate(), today);
                sentCount++;
            } catch (RuntimeException e) {
                // Nothing is recorded, so tomorrow's sweep tries again. A reminder that never
                // arrives is a smaller failure than one that arrives twice, and the log row is
                // what decides which of the two a retry produces.
                failed++;
                log.warn("Reminder to {} about {} {} failed; will retry on the next sweep",
                        candidate.item().getRecipientId(), candidate.type(),
                        candidate.item().getItemId(), e);
            }
        }

        SweepResult result = new SweepResult(candidates.size(), sentCount, alreadySent, failed);
        if (result.sent() > 0 || result.failed() > 0) {
            log.info("Reminder sweep for {}: {} due, {} sent, {} already sent today, {} failed",
                    today, result.considered(), result.sent(), result.alreadySent(), result.failed());
        }
        return result;
    }

    private static void add(List<Candidate> into, ReminderItemType type,
                            List<ReminderRepository.DueItem> items) {
        for (ReminderRepository.DueItem item : items) {
            into.add(new Candidate(type, item));
        }
    }

    private String destination(Candidate candidate) {
        return redirectTo.isEmpty() ? candidate.item().getRecipientEmail() : redirectTo;
    }

    /**
     * The subject line, with the real recipient named when the destination has been redirected.
     *
     * <p>Without that, a demo inbox holding thirty reminders is thirty identical-looking emails
     * with no way to tell whose each one was, which defeats the point of running the sweep
     * against real data.
     */
    private String subject(Candidate candidate) {
        String line = switch (candidate.type()) {
            case SELF_REVIEW -> "Your self-review is due";
            case MANAGER_REVIEW -> "Your review of " + candidate.item().getAbout() + " is due";
            case PEER_REVIEW -> "Your feedback on " + candidate.item().getAbout() + " is due";
            case PLAN_GOAL -> "Goal due: " + candidate.item().getAbout();
        };

        return redirectTo.isEmpty()
                ? line
                : "[for " + candidate.item().getRecipientName()
                        + " <" + candidate.item().getRecipientEmail() + ">] " + line;
    }

    /**
     * A task, a date and a link. Nothing else, and that is the rule rather than an omission.
     */
    private String body(Candidate candidate, LocalDate today) {
        LocalDate dueDate = candidate.item().getDueDate();

        // ChronoUnit, not Period. Period.getDays() is the day *component* of a years-months-days
        // breakdown, so a deadline five weeks out reports as 5 and the wording would be wrong in
        // exactly the case a reminder is least expected.
        long days = ChronoUnit.DAYS.between(today, dueDate);

        String when;
        if (days == 0) {
            when = "today, " + DUE.format(dueDate);
        } else if (days == 1) {
            when = "tomorrow, " + DUE.format(dueDate);
        } else if (days < 0) {
            when = "was due on " + DUE.format(dueDate);
        } else {
            when = "on " + DUE.format(dueDate) + ", in " + days + " days";
        }

        String what = switch (candidate.type()) {
            case SELF_REVIEW -> "Your self-review is due " + when + ".";
            case MANAGER_REVIEW -> "Your review of " + candidate.item().getAbout()
                    + " is due " + when + ".";
            case PEER_REVIEW -> "Your feedback on " + candidate.item().getAbout()
                    + " is due " + when + ".";
            case PLAN_GOAL -> "Your goal \"" + candidate.item().getAbout()
                    + "\" is due " + when + ".";
        };

        return "Hello " + candidate.item().getRecipientName() + ",\n\n"
                + what + "\n\n"
                + "Open Altrium to complete it: " + appUrl + "\n\n"
                + "This is an automatic reminder. Nobody else has been told.\n";
    }

    /** One thing to remind somebody about, with the kind that decides who is responsible. */
    public record Candidate(ReminderItemType type, ReminderRepository.DueItem item) {
    }

    /**
     * @param considered  how many deadlines matched an offset today
     * @param alreadySent how many were skipped because this reminder had already gone out today
     */
    public record SweepResult(int considered, int sent, int alreadySent, int failed) {
    }
}
