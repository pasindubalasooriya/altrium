package com.altrium.calendar;

import com.altrium.auth.Capability;

/**
 * The two meetings Altrium books (scenario section 12).
 *
 * <p>Each carries the capability that governs it, so the decision to schedule is made from
 * the request's own type rather than from a branch somebody has to remember to extend. Adding
 * a third kind of meeting without naming its capability will not compile.
 */
public enum MeetingType {

    /**
     * The manager and the employee, to agree a development or improvement plan (section 8,
     * section 9). The reviewee is the employee, and so is the person invited.
     */
    PLAN_MEETING(Capability.SCHEDULE_PLAN_MEETING, "Plan meeting", 45),

    /**
     * HR and the manager, to calibrate the manager's rating of an employee (section 5 step 5).
     *
     * <p>The reviewee is that employee, who is not in the room. Scoping it to them rather than
     * to the manager is deliberate: it makes "may this HR user book this meeting?" the same
     * question as "may this HR user calibrate this rating?", answered by the same department
     * grant and blocked by the same P-2.2 rule when the rating is their own.
     */
    NORMALIZATION_MEETING(Capability.SCHEDULE_NORMALIZATION_MEETING, "Normalization meeting", 30);

    private final Capability capability;
    private final String title;
    private final int defaultMinutes;

    MeetingType(Capability capability, String title, int defaultMinutes) {
        this.capability = capability;
        this.title = title;
        this.defaultMinutes = defaultMinutes;
    }

    public Capability capability() {
        return capability;
    }

    /**
     * What the event is called in the organiser's calendar.
     *
     * <p>The title says the kind of meeting and nothing else. A calendar entry is visible to
     * anybody the organiser has shared their calendar with, including people outside Altrium,
     * so a title carrying a rating or a plan would put review content where none of this
     * system's rules reach.
     */
    public String title() {
        return title;
    }

    public int defaultMinutes() {
        return defaultMinutes;
    }
}
