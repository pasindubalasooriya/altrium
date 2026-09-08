package com.altrium.calendar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The slot arithmetic, on its own.
 *
 * <p>No Spring and no database, because none is needed: {@link SlotFinder} is a function of
 * its arguments. That is the point of having built it that way. The cases below are the ones
 * that a slot finder gets wrong, and every one of them would be awkward to provoke through a
 * booking and trivial to state here.
 */
class SlotFinderTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private final SlotFinder finder = new SlotFinder("Europe/London", "09:00", "17:00");

    /** A Monday well clear of today, so no test depends on when it is run. */
    private static final LocalDate MONDAY =
            LocalDate.of(2027, 3, 1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));

    private static final Instant LONG_AGO = Instant.parse("2020-01-01T00:00:00Z");

    private static Instant at(LocalDate day, String time) {
        return day.atTime(LocalTime.parse(time)).atZone(LONDON).toInstant();
    }

    private static TimeSlot busy(LocalDate day, String from, String to) {
        return new TimeSlot(at(day, from), at(day, to));
    }

    @Test
    @DisplayName("An empty pair of calendars offers slots from the start of the working day")
    void emptyCalendarsOfferFromTheStartOfTheDay() {
        List<TimeSlot> free = finder.propose(MONDAY, MONDAY, Duration.ofMinutes(30), List.of(), LONG_AGO);

        assertThat(free).isNotEmpty();
        assertThat(free.get(0).start()).isEqualTo(at(MONDAY, "09:00"));
        assertThat(free.get(0).end()).isEqualTo(at(MONDAY, "09:30"));
    }

    @Test
    @DisplayName("Nothing is offered outside working hours")
    void nothingOutsideWorkingHours() {
        List<TimeSlot> free = finder.propose(MONDAY, MONDAY, Duration.ofMinutes(30), List.of(), LONG_AGO);

        assertThat(free).allSatisfy(slot -> {
            assertThat(slot.start()).isAfterOrEqualTo(at(MONDAY, "09:00"));
            assertThat(slot.end()).isBeforeOrEqualTo(at(MONDAY, "17:00"));
        });
    }

    @Test
    @DisplayName("A busy block removes every slot that overlaps it, and no more")
    void aBusyBlockRemovesOverlappingSlotsOnly() {
        List<TimeSlot> free = finder.propose(MONDAY, MONDAY, Duration.ofMinutes(30),
                List.of(busy(MONDAY, "09:00", "10:00")), LONG_AGO);

        assertThat(free).noneSatisfy(slot ->
                assertThat(slot.start()).isEqualTo(at(MONDAY, "09:00")));
        assertThat(free).noneSatisfy(slot ->
                assertThat(slot.start()).isEqualTo(at(MONDAY, "09:30")));
        assertThat(free.get(0).start()).isEqualTo(at(MONDAY, "10:00"));
    }

    @Test
    @DisplayName("A meeting may start the moment another ends")
    void backToBackIsFree() {
        // The interval is half open, so 10:00 to 10:30 does not collide with 09:30 to 10:00.
        // Getting this wrong loses an entire slot after every existing meeting, which is the
        // kind of bug that looks like a busy diary rather than like a fault.
        List<TimeSlot> free = finder.propose(MONDAY, MONDAY, Duration.ofMinutes(30),
                List.of(busy(MONDAY, "09:30", "10:00")), LONG_AGO);

        assertThat(free).anySatisfy(slot ->
                assertThat(slot.start()).isEqualTo(at(MONDAY, "10:00")));
    }

    @Test
    @DisplayName("Both calendars narrow the offer; neither alone decides it")
    void bothCalendarsNarrowTheOffer() {
        // What one person has free at 09:00 the other does not, and the intersection is empty
        // until 11:00. This is the whole reason free/busy is read from two accounts.
        List<TimeSlot> free = finder.propose(MONDAY, MONDAY, Duration.ofMinutes(60),
                List.of(busy(MONDAY, "09:00", "11:00"), busy(MONDAY, "11:00", "13:00")),
                LONG_AGO);

        assertThat(free.get(0).start()).isEqualTo(at(MONDAY, "13:00"));
    }

    @Test
    @DisplayName("A longer meeting has fewer places to go, and never runs past the day")
    void aLongerMeetingMustStillFinishByTheEndOfTheDay() {
        List<TimeSlot> free = finder.propose(MONDAY, MONDAY, Duration.ofMinutes(90),
                List.of(busy(MONDAY, "09:00", "15:00")), LONG_AGO);

        // 15:00 to 16:30 fits; 15:30 to 17:00 fits; 16:00 to 17:30 does not.
        assertThat(free).hasSize(2);
        assertThat(free.get(free.size() - 1).end()).isEqualTo(at(MONDAY, "17:00"));
    }

    @Test
    @DisplayName("A wholly busy day offers nothing rather than offering it anyway")
    void aWhollyBusyDayOffersNothing() {
        List<TimeSlot> free = finder.propose(MONDAY, MONDAY, Duration.ofMinutes(30),
                List.of(busy(MONDAY, "08:00", "18:00")), LONG_AGO);

        assertThat(free).isEmpty();
    }

    @Test
    @DisplayName("The weekend is never proposed")
    void theWeekendIsNeverProposed() {
        LocalDate saturday = MONDAY.minusDays(2);
        LocalDate sunday = MONDAY.minusDays(1);

        List<TimeSlot> free = finder.propose(saturday, sunday, Duration.ofMinutes(30),
                List.of(), LONG_AGO);

        assertThat(free).isEmpty();
    }

    @Test
    @DisplayName("Nothing already past is offered, even on a day that is still open")
    void nothingInThePastIsOffered() {
        // The boundary that a slot finder reading its own clock cannot be tested at.
        Instant elevenOClock = at(MONDAY, "11:00");

        List<TimeSlot> free = finder.propose(MONDAY, MONDAY, Duration.ofMinutes(30),
                List.of(), elevenOClock);

        assertThat(free.get(0).start()).isEqualTo(elevenOClock);
    }

    @Test
    @DisplayName("Overlapping and touching busy blocks coalesce")
    void overlappingBlocksCoalesce() {
        // Two calendars routinely report the same meeting twice, and Google returns adjacent
        // blocks for back-to-back events.
        List<TimeSlot> merged = SlotFinder.merge(List.of(
                busy(MONDAY, "10:00", "11:00"),
                busy(MONDAY, "10:30", "12:00"),
                busy(MONDAY, "12:00", "12:30"),
                busy(MONDAY, "14:00", "15:00")));

        assertThat(merged).hasSize(2);
        assertThat(merged.get(0).start()).isEqualTo(at(MONDAY, "10:00"));
        assertThat(merged.get(0).end()).isEqualTo(at(MONDAY, "12:30"));
    }

    @Test
    @DisplayName("A block that starts before the window still blocks the start of it")
    void aBlockStartingBeforeTheWindowStillBlocks() {
        // An overnight event, or one that began yesterday afternoon. A finder that only
        // compared start times would offer nine o'clock in the middle of it.
        TimeSlot overnight = new TimeSlot(at(MONDAY.minusDays(1), "22:00"), at(MONDAY, "09:45"));

        List<TimeSlot> free = finder.propose(MONDAY, MONDAY, Duration.ofMinutes(30),
                List.of(overnight), LONG_AGO);

        assertThat(free.get(0).start()).isEqualTo(at(MONDAY, "10:00"));
    }
}
