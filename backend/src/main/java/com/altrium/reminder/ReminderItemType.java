package com.altrium.reminder;

/**
 * What a reminder is about.
 *
 * <p>Four kinds, and the distinction is not cosmetic: it decides who is responsible. A cycle
 * participant row generates a reminder to the employee about their self-review and another to
 * their manager about the manager review, from the same row and the same deadline. Without the
 * type, those two would share an identity in the log and the second would be suppressed as a
 * duplicate of the first.
 */
public enum ReminderItemType {

    /** The employee's own review of themselves. Responsible: the employee. */
    SELF_REVIEW,

    /** One assigned peer's feedback. Responsible: that peer, never the subject. */
    PEER_REVIEW,

    /** The manager's review of a report. Responsible: the manager. */
    MANAGER_REVIEW,

    /**
     * A development or improvement goal falling due. Responsible: the employee whose plan it is.
     *
     * <p>One type for both plan kinds on purpose. The reminder says the same thing and goes to
     * the same person; what differs is whether the goal may be reminded about at all, and that
     * is decided in the query rather than here.
     */
    PLAN_GOAL
}
