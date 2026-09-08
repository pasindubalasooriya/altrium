package com.altrium.calendar;

import java.util.List;

/**
 * The calendar half: reading busy times and writing an event.
 *
 * <p>Note what is <em>not</em> here. There is no method that reads a person's events. Altrium
 * asks Google only when somebody is busy, never what they are doing, and the narrowness of
 * this interface is where that is enforced: no future caller can reach for an event list,
 * because none is offered.
 */
public interface CalendarClient {

    /**
     * The busy intervals on this calendar within the window.
     *
     * <p>Google's free/busy endpoint returns intervals and nothing else. No title, no
     * attendees, no location, whatever the calendar's sharing settings are.
     */
    List<TimeSlot> busy(String accessToken, TimeSlot window);

    /** Creates the event on the caller's primary calendar and emails the invitation. */
    CreatedEvent create(String accessToken, EventRequest event);

    /**
     * @param attendeeEmail the other party, invited by email because there is no shared
     *                      Workspace directory to look them up in (scenario section 12)
     */
    record EventRequest(String title, String description, TimeSlot when, String attendeeEmail) {
    }

    record CreatedEvent(String eventId, String htmlLink) {
    }
}
