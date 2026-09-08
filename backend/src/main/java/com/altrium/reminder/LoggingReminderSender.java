package com.altrium.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * The sender used when no Resend key is configured: it prints instead of sending.
 *
 * <p>This is what lets the feature be developed and demonstrated on a machine with no
 * credentials at all. The sweep still runs, every suppression rule still applies, and every log
 * row is still written - only the last step changes. A feature that could not run without a key
 * would be one whose scheduling logic nobody exercised until the day of the demo.
 *
 * <p>It logs the subject and the address and **not the body**, even though the body is
 * deliberately thin. A log file is read by more people than an inbox is, and there is no reason
 * for it to accumulate who is behind on what.
 */
@Component
@ConditionalOnMissingBean(ResendReminderSender.class)
public class LoggingReminderSender implements ReminderSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingReminderSender.class);

    @Override
    public void send(String to, String subject, String body) {
        log.info("[reminder, not sent - no Resend key configured] to={} subject={}", to, subject);
    }
}
