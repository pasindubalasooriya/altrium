package com.altrium.review;

/**
 * {@code CONFIGURED -> OPEN -> CLOSED} (P-6.1, P-6.2).
 *
 * <p>The transition to {@link #OPEN} is made by the sweep, not by a person: the Super Admin
 * sets the date and the system fires on it (scenario §4). The status column and
 * {@code opened_at} move together, and only {@code CycleService} moves them.
 */
public enum CycleStatus {

    /** Dates and cohort are being decided. The start date is still editable (P-6.2). */
    CONFIGURED,

    /** The sweep has stamped {@code opened_at}. The start date is now locked. */
    OPEN,

    /** No further writes. Artifacts stay readable to whoever could read them. */
    CLOSED
}
