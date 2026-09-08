package com.altrium.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * The daily sweep that sends deadline reminders (scenario section 13).
 *
 * <p>Almost empty, in the same way {@code CycleSweepJob} is and for the same reason. It owns
 * <em>when</em> the sweep runs and nothing about <em>what</em> it does: the work is
 * {@link ReminderService#sweep}, an ordinary method a test can call with a date. A scheduled job
 * that grew its own copy of the rules would be the one nobody reads and nobody tests, running
 * unattended at seven in the morning.
 *
 * <p>Runs at 07:00 rather than at two, unlike the cycle sweep. A cycle opening at two in the
 * morning is invisible; an email arriving then is read at breakfast with the timestamp showing,
 * which makes it look automated in the way people ignore.
 *
 * <p>{@link Clock} is injected so a test can put the clock on a date and assert what was sent,
 * instead of asserting against whatever today happens to be when the suite runs.
 */
@Component
@ConditionalOnProperty(name = "altrium.reminders.enabled", havingValue = "true", matchIfMissing = true)
public class ReminderSweepJob {

    private static final Logger log = LoggerFactory.getLogger(ReminderSweepJob.class);

    private final ReminderService reminders;
    private final Clock clock;

    public ReminderSweepJob(ReminderService reminders, Clock clock) {
        this.reminders = reminders;
        this.clock = clock;
    }

    @Scheduled(cron = "${altrium.reminders.cron:0 0 7 * * *}")
    public void run() {
        LocalDate today = LocalDate.now(clock);
        try {
            reminders.sweep(today);
        } catch (RuntimeException e) {
            // Swallowed on purpose. An exception escaping a @Scheduled method is logged by the
            // container and the schedule continues, but nothing else would say which run failed.
            // Tomorrow's sweep picks up everything unlogged, so a bad day costs a day.
            log.error("Reminder sweep for {} failed", today, e);
        }
    }
}
