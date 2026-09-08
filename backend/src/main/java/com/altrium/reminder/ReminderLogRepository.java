package com.altrium.reminder;

import org.springframework.data.repository.Repository;

/**
 * The log's two operations: has this already gone out today, and record that it has.
 *
 * <p>The {@code exists} check is an optimisation and not the guarantee. The guarantee is the
 * unique key in the schema; this only saves composing and sending an email that the insert
 * would then refuse. Treating it as the protection would be the classic check-then-act race,
 * and the window between the two is exactly when a second sweep runs.
 */
public interface ReminderLogRepository extends Repository<ReminderLog, Long> {

    boolean existsByItemTypeAndItemIdAndRecipientIdAndSentOn(
            ReminderItemType itemType, Long itemId, Long recipientId, java.time.LocalDate sentOn);

    ReminderLog save(ReminderLog entry);
}
