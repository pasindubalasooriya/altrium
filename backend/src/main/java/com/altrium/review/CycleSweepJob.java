package com.altrium.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * The daily sweep that opens cycles whose date has arrived (P-6.4).
 *
 * <p>This class is deliberately almost empty. It owns <em>when</em> the sweep runs and nothing
 * about <em>what</em> it does: the work is {@link CycleService#sweepDueCycles}, the same method
 * an administrator's manual open goes through. A scheduled job that grew its own copy of the
 * intake rules would be the one place nobody tests and nobody reads, running unattended at
 * three in the morning.
 *
 * <h2>Running as the system principal</h2>
 *
 * <p>There is no security context here and none is faked. The sweep does not act on behalf of
 * the Super Admin who configured the cycle - they may have left the company since - so
 * borrowing their identity would attribute an automated action to a person and produce an
 * audit trail that is simply untrue. It bypasses the caller checks because there is no caller,
 * and stays bound by every domain invariant: intake still refuses Leadership (P-1.5) and still
 * skips deactivated employees (P-0.7), because those rules live in the query, below the point
 * where authorization would have been.
 *
 * <p>{@link Clock} is injected rather than {@code LocalDate.now()} being called directly, so a
 * test can put the clock on a date and assert what the sweep did, instead of asserting against
 * whatever today happens to be when the suite runs.
 */
@Component
@ConditionalOnProperty(name = "altrium.cycle-sweep.enabled", havingValue = "true", matchIfMissing = true)
public class CycleSweepJob {

    private static final Logger log = LoggerFactory.getLogger(CycleSweepJob.class);

    private final CycleService cycles;
    private final Clock clock;

    public CycleSweepJob(CycleService cycles, Clock clock) {
        this.cycles = cycles;
        this.clock = clock;
    }

    /**
     * Runs at 02:00 daily by default.
     *
     * <p>The exact hour does not matter, and that is the point of selecting on <em>date arrived
     * or passed</em>: a run that is missed, delayed or fired twice all reach the same state.
     */
    @Scheduled(cron = "${altrium.cycle-sweep.cron:0 0 2 * * *}")
    public void run() {
        LocalDate today = LocalDate.now(clock);
        CycleService.SweepResult result = cycles.sweepDueCycles(today);

        if (result.openedNothing()) {
            // Logged at debug: on most days there is genuinely nothing due, and a daily INFO
            // line saying so trains everybody to ignore the log this job writes.
            log.debug("Cycle sweep for {} found nothing due", today);
        }
    }
}
