package com.altrium.reminder;

/**
 * Sending one email.
 *
 * <p>An interface with two implementations rather than one class with an {@code if}, so that a
 * machine with no API key runs the whole sweep, applies every suppression rule and writes every
 * log row, and simply prints instead of sending. The scheduling logic is then exercised in
 * development rather than skipped, which is where the mistakes are.
 *
 * <p>Deliberately knows nothing about reminders. It is handed a finished address, subject and
 * body: who should receive a reminder and whether they should receive one at all are decisions
 * that belong upstream, and a sender that could make them would be a second place they were made.
 */
public interface ReminderSender {

    /**
     * @param to a real address, already redirected if the environment redirects
     * @throws RuntimeException if the send fails; the caller records nothing and tries tomorrow
     */
    void send(String to, String subject, String body);
}
