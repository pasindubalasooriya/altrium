package com.altrium.calendar;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns two people's busy times into times they are both free (scenario section 12).
 *
 * <p>A pure function of its arguments, with no repository and no clock of its own beyond the
 * one passed in. That is what lets the awkward cases be tested directly rather than through a
 * booking, and the awkward cases are where a slot finder goes wrong: a meeting that spans the
 * end of the working day, a busy block that starts before the window, two blocks that touch.
 *
 * <h2>What the caller is given back, and what they are not</h2>
 *
 * <p>Only the intersection. The busy intervals that produced it never leave this class, so a
 * manager arranging a plan meeting learns the times their employee is free for it and nothing
 * else about their week. Google's free/busy endpoint already withholds titles and attendees,
 * but "busy 14:00 to 17:30 on Thursday" is still a fact about somebody's day that nobody needs
 * in order to book half an hour with them.
 */
@Component
public class SlotFinder {

    /** How many candidate slots are worth offering. A longer list is a list nobody reads. */
    private static final int DEFAULT_LIMIT = 8;

    /**
     * Slots start on the half hour.
     *
     * <p>Aligning to a grid rather than proposing the first free instant is what keeps the
     * offer readable: "10:00 or 10:30" is a choice, and "10:07 or 10:52" is a machine talking.
     */
    private static final Duration GRANULARITY = Duration.ofMinutes(30);

    private final ZoneId zone;
    private final LocalTime dayStart;
    private final LocalTime dayEnd;

    public SlotFinder(@Value("${altrium.meetings.zone:Europe/London}") String zone,
                      @Value("${altrium.meetings.day-start:09:00}") String dayStart,
                      @Value("${altrium.meetings.day-end:17:00}") String dayEnd) {
        this.zone = ZoneId.of(zone);
        this.dayStart = LocalTime.parse(dayStart);
        this.dayEnd = LocalTime.parse(dayEnd);
    }

    public ZoneId zone() {
        return zone;
    }

    /**
     * Free slots of the given length, within working hours, on weekdays, in date order.
     *
     * @param from  the first day to consider, in {@link #zone}
     * @param to    the last day, inclusive
     * @param busy  every busy interval from every calendar consulted, in any order and
     *              overlapping freely. Merging is this class's job, not its callers'
     * @param now   nothing before this is offered. Passed rather than read from the system so
     *              the boundary case is testable
     */
    public List<TimeSlot> propose(LocalDate from, LocalDate to, Duration length,
                                  List<TimeSlot> busy, Instant now) {
        List<TimeSlot> merged = merge(busy);
        List<TimeSlot> free = new ArrayList<>();

        for (LocalDate day = from; !day.isAfter(to) && free.size() < DEFAULT_LIMIT; day = day.plusDays(1)) {
            if (isWeekend(day)) {
                // A review conversation is work, and proposing Saturday would say otherwise.
                continue;
            }
            addSlotsFor(day, length, merged, now, free);
        }
        return free;
    }

    private void addSlotsFor(LocalDate day, Duration length, List<TimeSlot> busy,
                             Instant now, List<TimeSlot> into) {
        ZonedDateTime open = day.atTime(dayStart).atZone(zone);
        ZonedDateTime close = day.atTime(dayEnd).atZone(zone);

        ZonedDateTime cursor = open;
        while (into.size() < DEFAULT_LIMIT) {
            ZonedDateTime end = cursor.plus(length);
            if (end.isAfter(close)) {
                // The last slot of the day has to finish by the end of it, so a 45 minute
                // meeting simply has fewer places to go than a 30 minute one.
                return;
            }

            TimeSlot candidate = new TimeSlot(cursor.toInstant(), end.toInstant());

            if (!candidate.start().isBefore(now) && isFree(candidate, busy)) {
                into.add(candidate);
            }
            cursor = cursor.plus(GRANULARITY);
        }
    }

    private static boolean isFree(TimeSlot candidate, List<TimeSlot> busy) {
        for (TimeSlot block : busy) {
            if (!block.start().isBefore(candidate.end())) {
                // This block begins at or after the candidate ends, and the list is sorted,
                // so nothing left in it can overlap either.
                return true;
            }
            if (candidate.overlaps(block)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWeekend(LocalDate day) {
        return day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    /**
     * Sorts and coalesces, so overlapping and touching blocks become one.
     *
     * <p>Two calendars routinely report the same meeting twice, and Google itself returns
     * adjacent blocks for back-to-back events. Without this, {@link #isFree} would still be
     * correct but would do it by luck rather than by construction.
     */
    static List<TimeSlot> merge(List<TimeSlot> busy) {
        List<TimeSlot> sorted = new ArrayList<>(busy);
        sorted.sort(Comparator.comparing(TimeSlot::start));

        List<TimeSlot> merged = new ArrayList<>();
        for (TimeSlot block : sorted) {
            if (merged.isEmpty()) {
                merged.add(block);
                continue;
            }
            TimeSlot last = merged.get(merged.size() - 1);
            if (!block.start().isAfter(last.end())) {
                merged.set(merged.size() - 1, new TimeSlot(
                        last.start(),
                        last.end().isAfter(block.end()) ? last.end() : block.end()));
            } else {
                merged.add(block);
            }
        }
        return merged;
    }
}
