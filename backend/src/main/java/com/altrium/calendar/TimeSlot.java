package com.altrium.calendar;

import java.time.Instant;

/**
 * A half-open interval, {@code [start, end)}.
 *
 * <p>Half-open so that a meeting ending at 10:00 and one starting at 10:00 do not overlap,
 * which is the whole reason back-to-back bookings are possible at all.
 */
public record TimeSlot(Instant start, Instant end) {

    public TimeSlot {
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("A slot must end after it starts");
        }
    }

    public boolean overlaps(TimeSlot other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }
}
