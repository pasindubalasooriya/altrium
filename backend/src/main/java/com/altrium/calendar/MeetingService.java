package com.altrium.calendar;

import com.altrium.auth.AuthorizationService;
import com.altrium.auth.CurrentUser;
import com.altrium.auth.CurrentUserService;
import com.altrium.auth.Grounds;
import com.altrium.auth.ReviewSubject;
import com.altrium.config.ConflictApiException;
import com.altrium.config.ValidationApiException;
import com.altrium.org.AppUser;
import com.altrium.org.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Feature 20 - scheduling the two review meetings through Google Calendar (scenario section 12).
 *
 * <h2>Who may put a meeting in whose calendar</h2>
 *
 * <p>Every path through this class begins with one
 * {@link AuthorizationService#require(com.altrium.auth.Capability, ReviewSubject)} call, made
 * against the meeting type's own capability and the reviewee the meeting is about. Nothing
 * here decides anything itself, and the two rules it enforces are therefore already written
 * down in {@link MeetingType}:
 *
 * <ul>
 *   <li>a plan meeting is called by {@code mgr(S)}, with S invited;</li>
 *   <li>a normalization meeting is called by HR-in-scope, with {@code mgr(S)} invited, about
 *       the rating of S, who is in neither seat.</li>
 * </ul>
 *
 * <p>The second is the one worth pausing on. Scoping it to S rather than to the manager means
 * an HR user can convene a calibration meeting exactly when they could perform the
 * calibration: the same department grant admits it, and P-2.2's own-review block refuses it
 * when the rating being calibrated is their own. No rule about meetings had to be written to
 * get that; it falls out of choosing the right subject.
 *
 * <h2>Reading a calendar is an access decision, and is made as one</h2>
 *
 * <p>Proposing slots means reading when the other party is busy, so it goes through the same
 * {@code require} call as the booking itself, with the same subject. <strong>You may see when
 * somebody is free exactly when you may put a meeting in their diary.</strong>
 *
 * <p>And the caller is given only the intersection. The other party's busy blocks are consumed
 * by {@link SlotFinder} and never returned, so what leaves this service is "you are both free
 * at these times" and never "they are busy at those".
 *
 * <h2>Without the second calendar</h2>
 *
 * <p>There is no Workspace directory, so free/busy is available only for people who have
 * personally connected their own account. When the other party has not, section 14's fallback
 * applies: slots are proposed from the organiser's calendar alone and the response says so, so
 * the screen can offer them as times to suggest rather than as times that are known to work.
 * The booking is not blocked by it, because a manager who cannot arrange a meeting until their
 * employee installs an integration has been given a worse system than a phone call.
 */
@Service
public class MeetingService {

    private static final Logger log = LoggerFactory.getLogger(MeetingService.class);

    /** How far ahead slots may be requested. Beyond this a calendar is guesswork anyway. */
    private static final int MAX_WINDOW_DAYS = 60;

    private final AuthorizationService authorization;
    private final CurrentUserService currentUser;
    private final AppUserRepository users;
    private final GoogleConnectionService connections;
    private final CalendarClient calendar;
    private final MeetingRepository meetings;
    private final SlotFinder slots;
    private final Clock clock;

    public MeetingService(AuthorizationService authorization,
                          CurrentUserService currentUser,
                          AppUserRepository users,
                          GoogleConnectionService connections,
                          CalendarClient calendar,
                          MeetingRepository meetings,
                          SlotFinder slots,
                          Clock clock) {
        this.authorization = authorization;
        this.currentUser = currentUser;
        this.users = users;
        this.connections = connections;
        this.calendar = calendar;
        this.meetings = meetings;
        this.slots = slots;
        this.clock = clock;
    }

    /**
     * Times both parties are free, or the organiser alone if the other has not connected.
     */
    @Transactional(readOnly = true)
    public SlotProposal proposeSlots(MeetingType type, Long subjectId,
                                     LocalDate from, LocalDate to, Integer minutes) {

        Parties parties = resolve(type, subjectId);
        Duration length = length(type, minutes);
        validateWindow(from, to);

        Instant now = clock.instant();
        TimeSlot window = new TimeSlot(
                from.atStartOfDay(slots.zone()).toInstant(),
                to.plusDays(1).atStartOfDay(slots.zone()).toInstant());

        // The organiser must have connected: the event is written to their calendar, so
        // without it there is nothing to propose slots into.
        String organiserToken = connections.accessTokenFor(parties.organiser().getId())
                .orElseThrow(() -> new ConflictApiException(
                        "Connect your Google Calendar before scheduling a meeting."));

        List<TimeSlot> busy = new ArrayList<>(calendar.busy(organiserToken, window));

        Optional<String> attendeeToken = connections.accessTokenFor(parties.attendee().getId());
        attendeeToken.ifPresent(token -> busy.addAll(calendar.busy(token, window)));

        List<TimeSlot> free = slots.propose(from, to, length, busy, now);

        return new SlotProposal(
                free,
                attendeeToken.isPresent(),
                parties.attendee().getFullName(),
                (int) length.toMinutes());
    }

    /**
     * Books the meeting: the event on the organiser's calendar, the invitation by email.
     *
     * <p>Not one transaction with the calendar call, because it cannot be. Google is written to
     * first and the row is written after, so the failure that is possible is an event that
     * exists with no row behind it. That direction is the right one: the meeting the people
     * involved can see is the meeting that happens, and a row claiming a booking that was
     * never made would be worse than a booking Altrium has forgotten.
     */
    @Transactional
    public BookedMeeting schedule(MeetingType type, Long subjectId, Instant start, Integer minutes) {
        Parties parties = resolve(type, subjectId);
        Duration length = length(type, minutes);

        if (start == null) {
            throw new ValidationApiException("A start time is required.");
        }
        Instant now = clock.instant();
        if (start.isBefore(now)) {
            throw new ValidationApiException("That time has already passed.");
        }

        TimeSlot when = new TimeSlot(start, start.plus(length));

        if (meetings.existsUpcoming(type, parties.subject().getId(), now)) {
            // The person has permission; the state refuses the second booking, so 409 rather
            // than 403 - the same reading as a duplicate peer submission.
            throw new ConflictApiException(
                    "A " + type.title().toLowerCase() + " is already booked for "
                            + parties.subject().getFullName() + ".");
        }

        String organiserToken = connections.accessTokenFor(parties.organiser().getId())
                .orElseThrow(() -> new ConflictApiException(
                        "Connect your Google Calendar before scheduling a meeting."));

        CalendarClient.CreatedEvent created = calendar.create(organiserToken,
                new CalendarClient.EventRequest(
                        type.title(),
                        description(type),
                        when,
                        parties.attendee().getEmail()));

        Meeting meeting = meetings.save(new Meeting(
                type, parties.subject(), parties.organiser(), parties.attendee(),
                when, created.eventId(), created.htmlLink()));

        log.info("{} booked by {} with {} about subject {}",
                type, parties.organiser().getId(), parties.attendee().getId(),
                parties.subject().getId());

        return new BookedMeeting(meeting.getId(), type, when,
                parties.attendee().getFullName(), created.htmlLink());
    }

    /** The caller's own meetings. Scoped in the query; see {@link MeetingRepository}. */
    @Transactional(readOnly = true)
    public List<MeetingView> myMeetings() {
        CurrentUser caller = currentUser.require();
        return meetings.forParticipant(caller.id(), clock.instant()).stream()
                .map(meeting -> MeetingView.of(meeting, caller.id()))
                .toList();
    }

    // ================================================================= the decision

    /**
     * The one authorization call, and the parties it settles.
     *
     * <p>Both public entry points go through here before touching a calendar, so there is no
     * route to a token, a busy list or an event that has not passed this line.
     */
    private Parties resolve(MeetingType type, Long subjectId) {
        CurrentUser caller = currentUser.require();

        ReviewSubject subject = authorization.subject(subjectId);
        Grounds grounds = authorization.require(type.capability(), subject);

        AppUser subjectUser = users.findById(subjectId).orElseThrow(() ->
                new ConflictApiException("That person no longer exists."));

        AppUser organiser = users.findById(caller.id()).orElseThrow(() ->
                new ConflictApiException("Your account could not be loaded."));

        AppUser attendee = switch (type) {
            case PLAN_MEETING -> subjectUser;
            case NORMALIZATION_MEETING -> managerOf(subject, subjectUser);
        };

        if (attendee.getId().equals(organiser.getId())) {
            // Only reachable on the normalization path, where an HR user who happens to be the
            // subject's manager would otherwise be invited to their own meeting. They already
            // hold the manager side of this conversation; there is nobody to calibrate with.
            throw new ConflictApiException(
                    "You are " + subjectUser.getFullName() + "'s manager, so there is no "
                            + "second party for this meeting.");
        }

        if (!attendee.isActive()) {
            throw new ConflictApiException(
                    attendee.getFullName() + " is deactivated and cannot be invited.");
        }

        log.debug("{} permitted on subject {} as {}", type.capability(), subjectId, grounds);
        return new Parties(subjectUser, organiser, attendee);
    }

    private AppUser managerOf(ReviewSubject subject, AppUser subjectUser) {
        if (subject.managerId() == null) {
            throw new ConflictApiException(
                    subjectUser.getFullName() + " has no manager, so there is no rating to "
                            + "calibrate with anybody.");
        }
        return users.findById(subject.managerId()).orElseThrow(() ->
                new ConflictApiException("That manager no longer exists."));
    }

    /**
     * The event body, which names the meeting and nobody's performance.
     *
     * <p>A calendar entry travels: it is copied into the attendee's calendar, it appears in
     * email invitations, and it is visible to anybody either person has shared their calendar
     * with, including people outside Altrium. So it carries the kind of meeting and a pointer
     * back into the system, and nothing that Altrium's own rules would have withheld.
     *
     * <p>The normalization description does not name the employee being calibrated, even
     * though both people in the room know who it is. The event lands in two personal Google
     * accounts outside any control this system has.
     */
    private String description(MeetingType type) {
        return switch (type) {
            case PLAN_MEETING -> "Scheduled through Altrium to agree your development plan.";
            case NORMALIZATION_MEETING -> "Scheduled through Altrium to calibrate a review rating.";
        };
    }

    private Duration length(MeetingType type, Integer minutes) {
        int chosen = minutes == null ? type.defaultMinutes() : minutes;
        if (chosen < 15 || chosen > 180) {
            throw new ValidationApiException("A meeting must be between 15 and 180 minutes.");
        }
        if (chosen % 15 != 0) {
            throw new ValidationApiException("A meeting length must be a multiple of 15 minutes.");
        }
        return Duration.ofMinutes(chosen);
    }

    private void validateWindow(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new ValidationApiException("A date range is required.");
        }
        if (to.isBefore(from)) {
            throw new ValidationApiException("The range ends before it begins.");
        }
        if (from.plusDays(MAX_WINDOW_DAYS).isBefore(to)) {
            throw new ValidationApiException(
                    "Slots can be proposed up to " + MAX_WINDOW_DAYS + " days ahead.");
        }
    }

    private record Parties(AppUser subject, AppUser organiser, AppUser attendee) {
    }

    /**
     * @param bothCalendarsChecked false when the other party has not connected Google, in which
     *                             case these are times the organiser is free and no more. The
     *                             screen has to say so; a proposal presented as mutual when it
     *                             is not is worse than no proposal
     */
    public record SlotProposal(List<TimeSlot> slots, boolean bothCalendarsChecked,
                               String attendeeName, int minutes) {
    }

    public record BookedMeeting(Long id, MeetingType type, TimeSlot when,
                                String attendeeName, String htmlLink) {
    }

    /**
     * One meeting as the person looking at it sees it.
     *
     * <p>{@code withName} is the <em>other</em> party, worked out from who is asking, so an
     * attendee is not shown their own name back. The organiser link is given only to the
     * organiser: Google issues it per calendar, and the attendee's copy of the event lives at
     * an address of their own.
     *
     * <p>The reviewee is not named on a normalization meeting, even though both people in the
     * room know who it is. A field that carries a third person's name into a list of
     * appointments is a route by which one could be learned, and nothing here needs it.
     */
    public record MeetingView(Long id, MeetingType type, String withName,
                              Instant startsAt, Instant endsAt, String htmlLink) {

        static MeetingView of(Meeting meeting, Long viewerId) {
            boolean viewerIsOrganiser = meeting.getOrganiser().getId().equals(viewerId);
            AppUser other = viewerIsOrganiser ? meeting.getAttendee() : meeting.getOrganiser();

            return new MeetingView(
                    meeting.getId(),
                    meeting.getType(),
                    other.getFullName(),
                    meeting.getStartsAt(),
                    meeting.getEndsAt(),
                    viewerIsOrganiser ? meeting.getGoogleHtmlLink() : null);
        }
    }
}
