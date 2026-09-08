package com.altrium.calendar;

import com.altrium.org.AppUser;
import com.altrium.org.Department;
import com.altrium.org.HrGrantService;
import com.altrium.org.Role;
import com.altrium.testsupport.Acting;
import com.altrium.testsupport.OrgFixture;
import com.altrium.testsupport.StubJwtDecoderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Feature 20 - Google Calendar scheduling (P-11.1, P-11.2, scenario section 12).
 *
 * <p>Every denial here goes through {@code MockMvc} with a minted token, never through a
 * screen. What is being proved is that the endpoint refuses, not that a button is hidden.
 *
 * <p>Google itself is replaced. {@link GoogleOAuthClient} and {@link CalendarClient} are the
 * two seams that touch the network, and with both stubbed the connect flow, the token cipher,
 * the state store and every authorization rule below still run for real - including a genuine
 * round trip of an encrypted refresh token through MySQL.
 *
 * <p>The tests worth reading twice are the two about disclosure: that free/busy is refused to
 * anybody who could not book, and that the other party's busy blocks never appear in a
 * response. Booking a meeting is a small feature; reading somebody's diary is not.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class MeetingTest {

    private static final String MEETINGS = "/api/meetings";
    private static final String SLOTS = "/api/meetings/slots";
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrgFixture org;

    @Autowired
    private HrGrantService grants;

    @Autowired
    private Acting acting;

    @Autowired
    private GoogleConnectionService connections;

    @MockitoBean
    private GoogleOAuthClient oauth;

    @MockitoBean
    private CalendarClient calendar;

    @BeforeEach
    void stubGoogle() {
        // The authorization URL carries the state back to the test, which is how connect() can
        // complete a real consent round trip without a browser.
        when(oauth.authorizationUrl(anyString()))
                .thenAnswer(call -> "https://consent.example/?state=" + call.getArgument(0));

        // An access token that names the refresh token it came from, so a busy-list stub can
        // be tied to one person's calendar.
        when(oauth.accessToken(anyString()))
                .thenAnswer(call -> "access-for-" + call.getArgument(0));

        when(calendar.busy(anyString(), any())).thenReturn(List.of());
        when(calendar.create(anyString(), any()))
                .thenReturn(new CalendarClient.CreatedEvent("google-event-1", "https://calendar.example/e/1"));
    }

    // ------------------------------------------------------------------ the organisation

    private String bearer(String handle) {
        return "Bearer " + OrgFixture.tokenFor(handle);
    }

    /** A real consent round trip for this person, ending in an encrypted row in MySQL. */
    private void connect(AppUser user, String handle) {
        when(oauth.exchange("code-" + handle)).thenReturn(new GoogleTokens(
                "refresh-" + handle,
                "access-now",
                handle + "@gmail.example",
                "https://www.googleapis.com/auth/calendar.events"));

        acting.as(user);
        String url = connections.beginConnect();
        String state = url.substring(url.indexOf("state=") + "state=".length());

        connections.completeConnect(state, "code-" + handle);
        acting.clear();
    }

    /** The access token the stubs will see for this person. */
    private static String tokenOf(String handle) {
        return "access-for-refresh-" + handle;
    }

    private static LocalDate monday() {
        return LocalDate.now(LONDON).plusDays(7).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
    }

    private static Instant at(LocalDate day, String time) {
        return day.atTime(LocalTime.parse(time)).atZone(LONDON).toInstant();
    }

    private static String scheduleBody(String type, Long subjectId, Instant start) {
        return "{\"type\":\"%s\",\"subjectId\":%d,\"start\":\"%s\"}".formatted(type, subjectId, start);
    }

    // ============================================================ P-11.1 the plan meeting

    @Test
    @DisplayName("P-11.1: a manager schedules the plan meeting with their own report")
    void P_11_1_managerSchedulesWithTheirReport() throws Exception {
        Department eng = org.department("Engineering");
        AppUser jane = org.userIn(eng, "jane-mgr", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(eng, "john-emp", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();
        connect(jane, "jane-mgr");

        mvc.perform(post(MEETINGS)
                        .header("Authorization", bearer("jane-mgr"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody("PLAN_MEETING", john.getId(), at(monday(), "10:00"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("PLAN_MEETING"))
                // The employee is the one invited, which is the whole shape of this meeting.
                .andExpect(jsonPath("$.attendeeName").value(john.getFullName()));
    }

    @Test
    @DisplayName("P-11.1: a manager cannot schedule a plan meeting with somebody else's report")
    void P_11_1_managerCannotScheduleWithANonReport() throws Exception {
        Department eng = org.department("Engineering");
        AppUser jane = org.userIn(eng, "jane-other", Role.EMPLOYEE, Role.MANAGER);
        AppUser elena = org.userIn(eng, "elena-mgr", Role.EMPLOYEE, Role.MANAGER);
        AppUser diego = org.userIn(eng, "diego-emp", Role.EMPLOYEE);
        diego.setManager(elena);
        org.flush();
        connect(jane, "jane-other");

        // Holding MANAGER is not the point; being this person's manager is (P-1.1).
        mvc.perform(post(MEETINGS)
                        .header("Authorization", bearer("jane-other"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody("PLAN_MEETING", diego.getId(), at(monday(), "10:00"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-11.1: HR cannot schedule the plan meeting, however wide their grant")
    void P_11_1_hrCannotScheduleThePlanMeeting() throws Exception {
        Department eng = org.department("Engineering");
        AppUser jane = org.userIn(eng, "jane-hrtest", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(eng, "john-hrtest", Role.EMPLOYEE);
        john.setManager(jane);
        AppUser hr = org.userIn(org.department("People"), "grace-hr1", Role.EMPLOYEE, Role.HR);
        org.flush();
        grants.grant(hr.getId(), eng.getId(), false, null, g -> g.getId());
        connect(hr, "grace-hr1");

        // HR read a development plan and write nothing to it (P-5.1). Calling the meeting that
        // agrees it would be writing to it by another route.
        mvc.perform(post(MEETINGS)
                        .header("Authorization", bearer("grace-hr1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody("PLAN_MEETING", john.getId(), at(monday(), "10:00"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-11.1: an employee cannot summon their own manager to a plan meeting")
    void P_11_1_theEmployeeCannotSummonTheirManager() throws Exception {
        Department eng = org.department("Engineering");
        AppUser jane = org.userIn(eng, "jane-summon", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(eng, "john-summon", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();
        connect(john, "john-summon");

        // SELF is deliberately absent from SCHEDULE_PLAN_MEETING. The scenario has the manager
        // calling this meeting, and an employee who could book it into their manager's diary
        // would be doing something no rule here grants.
        mvc.perform(post(MEETINGS)
                        .header("Authorization", bearer("john-summon"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody("PLAN_MEETING", john.getId(), at(monday(), "10:00"))))
                .andExpect(status().isForbidden());
    }

    // ================================================= P-11.2 the normalization meeting

    @Test
    @DisplayName("P-11.2: granted HR schedules the normalization meeting with the manager")
    void P_11_2_grantedHrSchedulesWithTheManager() throws Exception {
        Department eng = org.department("Engineering");
        AppUser jane = org.userIn(eng, "jane-norm", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(eng, "john-norm", Role.EMPLOYEE);
        john.setManager(jane);
        AppUser hr = org.userIn(org.department("People"), "grace-norm", Role.EMPLOYEE, Role.HR);
        org.flush();
        grants.grant(hr.getId(), eng.getId(), false, null, g -> g.getId());
        connect(hr, "grace-norm");

        // The subject is John, whose rating is being calibrated; the person invited is his
        // manager. He is in neither seat and is not named in the response.
        mvc.perform(post(MEETINGS)
                        .header("Authorization", bearer("grace-norm"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody("NORMALIZATION_MEETING", john.getId(), at(monday(), "10:00"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attendeeName").value(jane.getFullName()));
    }

    @Test
    @DisplayName("P-11.2: a manager cannot convene the calibration of their own judgment")
    void P_11_2_aManagerCannotConveneTheirOwnCalibration() throws Exception {
        Department eng = org.department("Engineering");
        AppUser jane = org.userIn(eng, "jane-convene", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(eng, "john-convene", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();
        connect(jane, "jane-convene");

        // DIRECT_MANAGER is absent from SCHEDULE_NORMALIZATION_MEETING. A manager who could
        // call this meeting could arrange the review of their own rating on their own terms.
        mvc.perform(post(MEETINGS)
                        .header("Authorization", bearer("jane-convene"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody("NORMALIZATION_MEETING", john.getId(), at(monday(), "10:00"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-2.1: HR without a grant over that department is refused")
    void P_2_1_hrOutsideTheirGrantIsRefused() throws Exception {
        Department eng = org.department("Engineering");
        Department sales = org.department("Sales");
        AppUser jane = org.userIn(eng, "jane-scope", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(eng, "john-scope", Role.EMPLOYEE);
        john.setManager(jane);
        AppUser hr = org.userIn(org.department("People"), "grace-scope", Role.EMPLOYEE, Role.HR);
        org.flush();
        grants.grant(hr.getId(), sales.getId(), false, null, g -> g.getId());
        connect(hr, "grace-scope");

        mvc.perform(post(MEETINGS)
                        .header("Authorization", bearer("grace-scope"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody("NORMALIZATION_MEETING", john.getId(), at(monday(), "10:00"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-2.2: an HR Head cannot convene the calibration of their own rating")
    void P_2_2_hrCannotConveneTheCalibrationOfTheirOwnRating() throws Exception {
        Department people = org.department("People");
        AppUser boss = org.userIn(people, "boss-cal", Role.EMPLOYEE, Role.MANAGER);
        AppUser head = org.userIn(people, "head-cal", Role.EMPLOYEE, Role.HR);
        head.setManager(boss);
        org.flush();
        // The widest grant there is: their own department, with the explicit override set.
        grants.grant(head.getId(), people.getId(), true, null, g -> g.getId());
        connect(head, "head-cal");

        // The override lifts the own-department block and never the own-review block. It is
        // decided at step 2, above anything a grant can reach. No rule about meetings was
        // written to get this: scoping the meeting to the reviewee is what produces it.
        mvc.perform(post(MEETINGS)
                        .header("Authorization", bearer("head-cal"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody("NORMALIZATION_MEETING", head.getId(), at(monday(), "10:00"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-1.5: Leadership is never the subject of a meeting, because they hold no rating")
    void P_1_5_leadershipIsNeverTheSubject() throws Exception {
        Department eng = org.department("Engineering");
        AppUser exec = org.user("exec-cal", Role.EMPLOYEE, Role.LEADERSHIP);
        AppUser hr = org.userIn(org.department("People"), "grace-exec", Role.EMPLOYEE, Role.HR);
        org.flush();
        grants.grant(hr.getId(), eng.getId(), false, null, g -> g.getId());
        connect(hr, "grace-exec");

        mvc.perform(post(MEETINGS)
                        .header("Authorization", bearer("grace-exec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody("NORMALIZATION_MEETING", exec.getId(), at(monday(), "10:00"))))
                .andExpect(status().isForbidden());
    }

    // ==================================================== free/busy is an access decision

    @Test
    @DisplayName("P-11.1: free/busy is refused to somebody who could not book the meeting")
    void P_11_1_freeBusyIsRefusedToSomebodyWhoCouldNotBook() throws Exception {
        Department eng = org.department("Engineering");
        AppUser elena = org.userIn(eng, "elena-fb", Role.EMPLOYEE, Role.MANAGER);
        AppUser diego = org.userIn(eng, "diego-fb", Role.EMPLOYEE);
        diego.setManager(elena);
        AppUser nosy = org.userIn(eng, "nosy-fb", Role.EMPLOYEE, Role.MANAGER);
        org.flush();
        connect(nosy, "nosy-fb");

        // Reading when somebody is free is reading their diary. It goes through the same
        // capability, against the same subject, as putting a meeting in it.
        mvc.perform(get(SLOTS)
                        .header("Authorization", bearer("nosy-fb"))
                        .param("type", "PLAN_MEETING")
                        .param("subjectId", diego.getId().toString())
                        .param("from", monday().toString())
                        .param("to", monday().toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("The other party's busy times never leave the server; only the intersection does")
    void onlyTheIntersectionIsReturned() throws Exception {
        Department eng = org.department("Engineering");
        AppUser jane = org.userIn(eng, "jane-busy", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(eng, "john-busy", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();
        connect(jane, "jane-busy");
        connect(john, "john-busy");

        LocalDate day = monday();
        when(calendar.busy(eq(tokenOf("john-busy")), any()))
                .thenReturn(List.of(new TimeSlot(at(day, "09:00"), at(day, "12:00"))));

        String body = mvc.perform(get(SLOTS)
                        .header("Authorization", bearer("jane-busy"))
                        .param("type", "PLAN_MEETING")
                        .param("subjectId", john.getId().toString())
                        .param("from", day.toString())
                        .param("to", day.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bothCalendarsChecked").value(true))
                // The morning is gone, which proves his calendar was consulted.
                .andExpect(jsonPath("$.slots[0].start").value(at(day, "12:00").toString()))
                .andReturn().getResponse().getContentAsString();

        // And nothing in the response describes when he was busy. The manager learns that
        // noon works, not that his morning was full.
        assertThat(body).doesNotContain("busy\":[");
        assertThat(body).doesNotContain(at(day, "09:00").toString());
    }

    @Test
    @DisplayName("Section 14 fallback: slots come from the organiser alone, and say so")
    void theFallbackIsReportedRatherThanHidden() throws Exception {
        Department eng = org.department("Engineering");
        AppUser jane = org.userIn(eng, "jane-solo", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(eng, "john-solo", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();
        connect(jane, "jane-solo");
        // John has not connected. There is no Workspace directory, so his calendar is simply
        // unavailable, and the manager is told rather than shown a proposal that is not one.

        mvc.perform(get(SLOTS)
                        .header("Authorization", bearer("jane-solo"))
                        .param("type", "PLAN_MEETING")
                        .param("subjectId", john.getId().toString())
                        .param("from", monday().toString())
                        .param("to", monday().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bothCalendarsChecked").value(false))
                .andExpect(jsonPath("$.slots").isNotEmpty());
    }

    // ================================================================= state, not access

    @Test
    @DisplayName("A second plan meeting is a conflict, not a denial")
    void aSecondPlanMeetingIsAConflict() throws Exception {
        Department eng = org.department("Engineering");
        AppUser jane = org.userIn(eng, "jane-dup", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(eng, "john-dup", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();
        connect(jane, "jane-dup");

        String request = scheduleBody("PLAN_MEETING", john.getId(), at(monday(), "10:00"));

        mvc.perform(post(MEETINGS).header("Authorization", bearer("jane-dup"))
                .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk());

        // She has permission; the state forbids the second booking. Same reading as a
        // duplicate peer submission, and a 403 here would say something untrue about her.
        mvc.perform(post(MEETINGS).header("Authorization", bearer("jane-dup"))
                .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Booking without a connected calendar is refused as a conflict")
    void bookingWithoutAConnectedCalendar() throws Exception {
        Department eng = org.department("Engineering");
        AppUser jane = org.userIn(eng, "jane-noconn", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(eng, "john-noconn", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();

        mvc.perform(post(MEETINGS)
                        .header("Authorization", bearer("jane-noconn"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody("PLAN_MEETING", john.getId(), at(monday(), "10:00"))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("A meeting length that is not a multiple of a quarter hour is a validation error")
    void anOddMeetingLengthIsRejected() throws Exception {
        Department eng = org.department("Engineering");
        AppUser jane = org.userIn(eng, "jane-len", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(eng, "john-len", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();
        connect(jane, "jane-len");

        mvc.perform(post(MEETINGS)
                        .header("Authorization", bearer("jane-len"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"PLAN_MEETING\",\"subjectId\":%d,\"start\":\"%s\",\"minutes\":22}"
                                .formatted(john.getId(), at(monday(), "10:00"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("A meeting cannot be booked in the past")
    void aMeetingCannotBeBookedInThePast() throws Exception {
        Department eng = org.department("Engineering");
        AppUser jane = org.userIn(eng, "jane-past", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(eng, "john-past", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();
        connect(jane, "jane-past");

        mvc.perform(post(MEETINGS)
                        .header("Authorization", bearer("jane-past"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody("PLAN_MEETING", john.getId(),
                                Instant.now().minusSeconds(3600))))
                .andExpect(status().isBadRequest());
    }

    // ============================================================== reading one's own list

    @Test
    @DisplayName("P-0.3: a meeting appears only to the two people in it")
    void P_0_3_aMeetingAppearsOnlyToItsParticipants() throws Exception {
        Department eng = org.department("Engineering");
        AppUser jane = org.userIn(eng, "jane-list", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(eng, "john-list", Role.EMPLOYEE);
        john.setManager(jane);
        AppUser bystander = org.userIn(eng, "mei-list", Role.EMPLOYEE);
        bystander.setManager(jane);
        org.flush();
        connect(jane, "jane-list");

        mvc.perform(post(MEETINGS).header("Authorization", bearer("jane-list"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(scheduleBody("PLAN_MEETING", john.getId(), at(monday(), "10:00"))))
                .andExpect(status().isOk());

        mvc.perform(get(MEETINGS + "/me").header("Authorization", bearer("john-list")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                // He is shown who he is meeting, not his own name back.
                .andExpect(jsonPath("$[0].withName").value(jane.getFullName()));

        // Her other report shares a manager, a department and a cycle with him, and sees
        // nothing. The caller's id is the whole WHERE clause.
        mvc.perform(get(MEETINGS + "/me").header("Authorization", bearer("mei-list")))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    @DisplayName("The organiser's calendar link is not handed to the attendee")
    void theOrganiserLinkIsNotHandedToTheAttendee() throws Exception {
        Department eng = org.department("Engineering");
        AppUser jane = org.userIn(eng, "jane-link", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(eng, "john-link", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();
        connect(jane, "jane-link");

        mvc.perform(post(MEETINGS).header("Authorization", bearer("jane-link"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(scheduleBody("PLAN_MEETING", john.getId(), at(monday(), "10:00"))))
                .andExpect(status().isOk());

        // Google issues the link per calendar. His copy of the event lives at an address of
        // his own, and handing him hers would be a link into her calendar.
        mvc.perform(get(MEETINGS + "/me").header("Authorization", bearer("john-link")))
                .andExpect(jsonPath("$[0].htmlLink").doesNotExist());

        mvc.perform(get(MEETINGS + "/me").header("Authorization", bearer("jane-link")))
                .andExpect(jsonPath("$[0].htmlLink").value("https://calendar.example/e/1"));
    }
}
