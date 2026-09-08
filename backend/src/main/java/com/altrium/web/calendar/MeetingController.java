package com.altrium.web.calendar;

import com.altrium.calendar.MeetingService;
import com.altrium.calendar.MeetingType;
import com.altrium.config.ValidationApiException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Feature 20 - booking the two review meetings (scenario section 12).
 *
 * <p>No {@code @PreAuthorize}, as everywhere else in Altrium. Whether a caller may propose
 * slots with somebody, or put a meeting in their calendar, depends on who reports to whom and
 * which departments an HR user has been granted, and neither of those is a role. The gate is
 * the capability required inside {@link MeetingService}.
 */
@RestController
@RequestMapping("/api/meetings")
public class MeetingController {

    private final MeetingService meetings;

    public MeetingController(MeetingService meetings) {
        this.meetings = meetings;
    }

    /**
     * Times both parties are free.
     *
     * <p>A GET, because it changes nothing, and the type and subject are the same pair that
     * govern the booking. A caller with no grounds to book with this person is refused here
     * too, so free/busy cannot be read as a way in.
     */
    @GetMapping("/slots")
    @Operation(summary = "Propose mutually free slots; gated by the same capability as booking")
    public MeetingService.SlotProposal slots(
            @RequestParam MeetingType type,
            @RequestParam Long subjectId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer minutes) {

        return meetings.proposeSlots(type, subjectId, from, to, minutes);
    }

    /** Books it: the event on the organiser's calendar, the invitation by email. */
    @PostMapping
    @Operation(summary = "Schedule a plan or normalization meeting (P-11.1, P-11.2)")
    public MeetingService.BookedMeeting schedule(@RequestBody ScheduleRequest request) {
        if (request == null || request.type() == null || request.subjectId() == null) {
            throw new ValidationApiException("A meeting type and a person are required.");
        }
        return meetings.schedule(request.type(), request.subjectId(), request.start(), request.minutes());
    }

    /**
     * The caller's own meetings, upcoming first.
     *
     * <p>Takes no user id. The only list this endpoint can produce is the caller's own, which
     * is why there is no capability to check: the scope is in the query.
     */
    @GetMapping("/me")
    @Operation(summary = "The caller's own upcoming meetings")
    public List<MeetingService.MeetingView> mine() {
        return meetings.myMeetings();
    }

    public record ScheduleRequest(@NotNull MeetingType type,
                                  @NotNull Long subjectId,
                                  Instant start,
                                  Integer minutes) {
    }

}
