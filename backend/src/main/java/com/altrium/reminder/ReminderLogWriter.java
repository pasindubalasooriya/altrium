package com.altrium.reminder;

import com.altrium.org.AppUserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Records one sent reminder, in a transaction of its own.
 *
 * <p><strong>A separate bean, and that is the whole reason it exists.</strong> Spring's
 * {@code @Transactional} is applied by a proxy, so a method calling another method on
 * {@code this} bypasses it entirely. The same code as a private method on
 * {@link ReminderService} would compile, read correctly, and silently join the caller's
 * transaction - which here is no transaction at all, and the row would be written outside one.
 *
 * <p>Ordinary {@code REQUIRED} propagation, and the reasoning is worth recording because
 * {@code REQUIRES_NEW} looks like the right answer and is not.
 *
 * <p>What is needed is that ten emails sent and an eleventh failing leave ten log rows: an
 * email cannot be rolled back, and losing the rows would mean sending all ten again tomorrow.
 * {@link ReminderService#sweep} carries no {@code @Transactional} of its own precisely so that
 * there is no ambient transaction to lose, and each call here therefore begins and commits one
 * of its own. {@code REQUIRED} already gives the per-row commit; the separate bean is what makes
 * the annotation apply at all, since a self-invoked method bypasses the proxy entirely.
 *
 * <p>{@code REQUIRES_NEW} would additionally suspend any caller's transaction, which sounds
 * safer and breaks the tests for a reason that says something real: a suspended transaction's
 * uncommitted rows are invisible to the new one, so a log row referencing a person the test has
 * created but not committed fails its foreign key. The production behaviour is identical either
 * way, because there is no caller transaction to suspend. Given that, the propagation that can
 * also be tested is the correct one.
 *
 * <p><strong>Do not wrap {@code sweep} in a transaction.</strong> That is what would change the
 * behaviour here, and it is why the sweep is left transactionless deliberately rather than by
 * omission.
 */
@Component
public class ReminderLogWriter {

    private final ReminderLogRepository log;
    private final AppUserRepository users;

    public ReminderLogWriter(ReminderLogRepository log, AppUserRepository users) {
        this.log = log;
        this.users = users;
    }

    @Transactional
    public void record(ReminderItemType type, Long itemId, Long recipientId,
                       LocalDate dueDate, LocalDate sentOn) {
        log.save(new ReminderLog(
                type, itemId, users.getReferenceById(recipientId), dueDate, sentOn));
    }
}
