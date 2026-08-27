package com.altrium.review;

import com.altrium.org.AppUser;
import com.altrium.org.Department;
import com.altrium.org.HrGrantService;
import com.altrium.org.Role;
import com.altrium.testsupport.OrgFixture;
import com.altrium.testsupport.ReviewFixture;
import com.altrium.testsupport.StubJwtDecoderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

// Imported statically because the OrgFixture field below is named `org`, which shadows the
// `org` package inside this class and makes any fully-qualified org.* reference unresolvable.
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Feature 4 - view permitted reviews, over HTTP.
 *
 * <p>These call the endpoints directly with a minted JWT. That is the whole point of writing
 * them this way: a test driving a UI proves a button is hidden, which is not access control.
 * Everything asserted here holds against a caller with a valid token, a known id and no
 * front end at all.
 *
 * <p>{@link com.altrium.auth.AuthorizationServiceTest} proves the rules and their ordering;
 * this proves the endpoints actually route through them, and that the SQL scoping produces
 * honest pagination counts.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class PermittedReviewReadTest {

    private static final String REVIEWS = "/api/reviews";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrgFixture org;

    @Autowired
    private ReviewFixture reviews;

    @Autowired
    private HrGrantService grants;

    private String bearer(String handle) {
        return "Bearer " + OrgFixture.tokenFor(handle);
    }

    // ------------------------------------------------------------------ the scoped list

    @Test
    @DisplayName("P-0.3: a manager's list is their direct reports, and the count agrees")
    void P_0_3_managerListIsScopedInSql() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser tom = org.userIn(engineering, "tom", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        AppUser omar = org.userIn(engineering, "omar", Role.EMPLOYEE);
        john.setManager(elena);
        aisha.setManager(elena);
        omar.setManager(tom);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        for (AppUser person : new AppUser[]{elena, tom, john, aisha, omar}) {
            reviews.participant(cycle, person);
        }
        reviews.flush();

        // Elena sees her two reports and herself. Tom and Tom's report are five rows in the
        // same table and the same cycle, and never appear.
        mvc.perform(get(REVIEWS).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isOk())
                // The count is the assertion that matters: filtering in Java would leave
                // this at 5 while returning 3 rows, and paging would leak the difference.
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[?(@.subjectName == 'omar')]").isEmpty())
                .andExpect(jsonPath("$.content[?(@.subjectName == 'tom')]").isEmpty());
    }

    @Test
    @DisplayName("P-2.1/P-2.3: HR sees granted departments and not their own")
    void P_2_1_hrListIsScopedToGrantedDepartments() throws Exception {
        Department engineering = org.department("Engineering");
        Department peopleOps = org.department("People Operations");
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        AppUser samuel = org.userIn(peopleOps, "samuel", Role.EMPLOYEE);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        org.flush();
        // Granted Engineering, plus her own department without the explicit flag - which
        // therefore buys her nothing (P-2.3).
        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());
        grants.grant(hana.getId(), peopleOps.getId(), false, null, g -> g.getId());

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.participant(cycle, samuel);
        reviews.participant(cycle, hana);
        reviews.flush();

        // Two rows: john, through the Engineering grant, and hana herself, because she is an
        // employee with a review like anyone (P-0.1, P-2.2). Samuel is the one who proves the
        // rule - same department as hana, covered by a grant she actually holds, and still
        // out of reach because that grant carries no explicit flag (P-2.3).
        mvc.perform(get(REVIEWS).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("hana")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[?(@.subjectName == 'john')]").isNotEmpty())
                .andExpect(jsonPath("$.content[?(@.subjectName == 'hana')]").isNotEmpty())
                .andExpect(jsonPath("$.content[?(@.subjectName == 'samuel')]").isEmpty());
    }

    @Test
    @DisplayName("P-2.2: the HR Head's own record is theirs to read, but carries no HR authority")
    void P_2_2_hrHeadSeesOwnRecordAsAnEmployeeOnly() throws Exception {
        Department peopleOps = org.department("People Operations");
        AppUser richard = org.user("richard", Role.EMPLOYEE, Role.LEADERSHIP);
        AppUser kevin = org.userIn(peopleOps, "kevin", Role.EMPLOYEE, Role.HR);
        AppUser samuel = org.userIn(peopleOps, "samuel", Role.EMPLOYEE);
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        kevin.setManager(richard);
        org.flush();
        grants.grant(kevin.getId(), peopleOps.getId(), true, null, g -> g.getId());

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, kevin);
        reviews.participant(cycle, samuel);
        // Leadership conduct the HR Head's review (P-2.6), and he is entitled to the outcome.
        reviews.managerReview(cycle, kevin, richard, "steady stewardship of the function");
        reviews.releasedRating(cycle, kevin, richard, Rating.EXCEEDS_EXPECTATIONS);
        reviews.peerReview(cycle, kevin, hana, "could delegate more");
        reviews.flush();

        // He is in his own list, as an employee with a review like anyone else.
        mvc.perform(get(REVIEWS).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("kevin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        // And his own record gives him what P-4.4 gives every subject - and nothing his HR
        // grant might otherwise have added. The peer feedback written about him is absent,
        // because no subject reads peer feedback, and his explicit grant does not change that.
        mvc.perform(get(REVIEWS + "/" + kevin.getId()).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("kevin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finalRating.rating").value("EXCEEDS_EXPECTATIONS"))
                .andExpect(jsonPath("$.managerReview.feedback").value("steady stewardship of the function"))
                .andExpect(jsonPath("$.peerReviews").doesNotExist());
    }

    @Test
    @DisplayName("P-9.4: the Super Admin's review list contains only themselves")
    void P_9_4_superAdminSeesNoReviewsButTheirOwn() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser devin = org.userIn(engineering, "devin", Role.EMPLOYEE, Role.SUPER_ADMIN);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, devin);
        reviews.participant(cycle, john);
        reviews.flush();

        // Administering the platform is a role, not a place in the hierarchy. Devin is an
        // ordinary engineer with his own review; the role adds nobody else.
        mvc.perform(get(REVIEWS).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("devin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].subjectName").value("devin"));
    }

    @Test
    @DisplayName("P-7.1: Leadership sees no individual reviews in the list")
    void P_7_1_leadershipSeesNoIndividualReviews() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser richard = org.user("richard", Role.EMPLOYEE, Role.LEADERSHIP);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.flush();

        mvc.perform(get(REVIEWS).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("richard")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ------------------------------------------------------------------ the single record

    @Test
    @DisplayName("P-1.2: a manager cannot open a non-report's record by id")
    void P_1_2_managerCannotOpenNonReportRecord() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser tom = org.userIn(engineering, "tom", Role.EMPLOYEE, Role.MANAGER);
        AppUser omar = org.userIn(engineering, "omar", Role.EMPLOYEE);
        omar.setManager(tom);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, omar);
        reviews.flush();

        // The id is real and the record exists. Being absent from the list is not enough -
        // the direct route has to be refused too, or the id is the way around the list.
        mvc.perform(get(REVIEWS + "/" + omar.getId()).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-0.5: an id that does not exist is refused exactly like one that does")
    void P_0_5_unknownIdIsIndistinguishableFromForbidden() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        org.flush();
        ReviewCycle cycle = reviews.openCycle();
        reviews.flush();

        // 404 here and 403 for a real person's record would let a caller enumerate the
        // organisation one identifier at a time.
        mvc.perform(get(REVIEWS + "/987654321").param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("john")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-3.3: the subject's own record carries no peer section at all")
    void P_3_3_subjectNeverReceivesPeerFeedback() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        AppUser mei = org.userIn(engineering, "mei", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.selfReview(cycle, john, "shipped the migration");
        reviews.peerReview(cycle, john, aisha, "great to pair with");
        reviews.peerReview(cycle, john, mei, "sometimes overcommits");
        reviews.flush();

        // Not redacted, not counted, not hinted at: absent. A "peerReviews": [] would tell
        // John the table exists and nobody had written yet, which is itself information.
        mvc.perform(get(REVIEWS + "/" + john.getId()).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selfReview.achievements").value("shipped the migration"))
                .andExpect(jsonPath("$.peerReviews").doesNotExist())
                .andExpect(jsonPath("$.visibleSections").value(not(hasItem("PEER_REVIEWS"))));
    }

    @Test
    @DisplayName("P-3.2: the manager sees peer feedback with its author named")
    void P_3_2_managerSeesPeerAuthorship() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.peerReview(cycle, john, aisha, "great to pair with");
        reviews.flush();

        // Anonymity is a rule about who reads, not about what is stored. Accountability for
        // a malicious review depends on the manager seeing exactly who wrote it.
        mvc.perform(get(REVIEWS + "/" + john.getId()).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.peerReviews[0].peerName").value("aisha"))
                .andExpect(jsonPath("$.peerReviews[0].feedback").value("great to pair with"));
    }

    @Test
    @DisplayName("P-4.4: the subject sees their rating only once it is released")
    void P_4_4_ratingIsWithheldUntilReleased() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.unreleasedRating(cycle, john, elena, Rating.EXCEEDS_EXPECTATIONS);
        reviews.flush();

        // Still being calibrated across the department: not John's to read yet.
        mvc.perform(get(REVIEWS + "/" + john.getId()).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finalRating").doesNotExist());

        // The manager who set it must see it before release, or they could not set it.
        mvc.perform(get(REVIEWS + "/" + john.getId()).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finalRating.rating").value("EXCEEDS_EXPECTATIONS"));
    }

    @Test
    @DisplayName("P-4.4: a released rating reaches the subject, with the manager's feedback")
    void P_4_4_releasedRatingReachesTheSubject() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.managerReview(cycle, john, elena, "strong quarter");
        reviews.releasedRating(cycle, john, elena, Rating.MEETS_EXPECTATIONS);
        reviews.flush();

        mvc.perform(get(REVIEWS + "/" + john.getId()).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finalRating.rating").value("MEETS_EXPECTATIONS"))
                .andExpect(jsonPath("$.managerReview.feedback").value("strong quarter"))
                // Everything else in the cycle stays out of reach.
                .andExpect(jsonPath("$.peerReviews").doesNotExist());
    }

    @Test
    @DisplayName("P-2.5: a grant revoked mid-session applies to the very next request")
    void P_2_5_revokedGrantAppliesWithoutReLogin() throws Exception {
        Department engineering = org.department("Engineering");
        Department peopleOps = org.department("People Operations");
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        org.flush();
        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.flush();

        mvc.perform(get(REVIEWS + "/" + john.getId()).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("hana")))
                .andExpect(status().isOk());

        grants.revoke(hana.getId(), engineering.getId());

        // Same token, no re-login. If grants were cached at login this would still succeed
        // until the token expired, which for a conflict-of-interest control is the whole
        // failure mode.
        mvc.perform(get(REVIEWS + "/" + john.getId()).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("hana")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-0.5: an unauthenticated caller gets 401, never data")
    void P_0_5_unauthenticatedIsRejected() throws Exception {
        ReviewCycle cycle = reviews.openCycle();
        reviews.flush();

        mvc.perform(get(REVIEWS).param("cycleId", cycle.getId().toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("visibleSections names grounds, not content: an empty section the manager may read is still named")
    void visibleSectionsNameGroundsRatherThanContent() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.flush();

        // Nothing has been written for John at all. Elena may read every section of his record
        // regardless, and the response has to say so - otherwise her screen tells her she has
        // no access to her own report's self-review, which is both false and alarming.
        mvc.perform(get(REVIEWS + "/" + john.getId()).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selfReview").doesNotExist())
                .andExpect(jsonPath("$.peerReviews").doesNotExist())
                .andExpect(jsonPath("$.visibleSections").value(hasItem("SELF_REVIEW")))
                .andExpect(jsonPath("$.visibleSections").value(hasItem("MANAGER_REVIEW")))
                .andExpect(jsonPath("$.visibleSections").value(hasItem("PEER_REVIEWS")));

        // John's own record of the same empty cycle. The peer section is not named, because he
        // has no grounds for it - and that is a statement about him, not about whether anyone
        // has written. There is still no count and no content to read either way (P-3.3).
        mvc.perform(get(REVIEWS + "/" + john.getId()).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibleSections").value(hasItem("SELF_REVIEW")))
                .andExpect(jsonPath("$.visibleSections").value(not(hasItem("PEER_REVIEWS"))));
    }

    @Test
    @DisplayName("P-4.4: the rating section is the exception - it is named only when one arrives")
    void theRatingSectionStaysTiedToWhatArrived() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.unreleasedRating(cycle, john, elena, Rating.MEETS_EXPECTATIONS);
        reviews.flush();

        // A rating exists and has not been released. If grounds were reported here, John would
        // be told he may see a rating and that none is there - which separates "not rated yet"
        // from "rated, not shared". Release exists precisely to control that difference.
        mvc.perform(get(REVIEWS + "/" + john.getId()).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finalRating").doesNotExist())
                .andExpect(jsonPath("$.visibleSections").value(not(hasItem("FINAL_RATING"))));
    }

    @Test
    @DisplayName("P-3.3: the peer count on the review list reaches the manager and never the subject")
    void P_3_3_peerCountIsWithheldFromTheSubjectOnTheList() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        john.setManager(elena);
        elena.setManager(org.user("priya", Role.EMPLOYEE, Role.LEADERSHIP));
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.participant(cycle, elena);
        reviews.peerReview(cycle, john, aisha, "great to pair with");
        // A peer review about Elena herself, so her own row would carry a count of 1 if the
        // scoping were wrong. Without this the last assertion below would pass on an empty
        // table and prove nothing.
        reviews.peerReview(cycle, elena, aisha, "clear about priorities");
        reviews.flush();

        // The page is sorted by name, so Elena's own row is first and John's is second. Asserted
        // by index rather than by a JSONPath filter, because a filter reports a missing property
        // as [null] and would pass whether the field was omitted or present and empty.
        mvc.perform(get(REVIEWS).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[1].subjectId").value(john.getId()))
                // Elena is told about John, because she is the one who has to notice it arrived.
                .andExpect(jsonPath("$.content[1].peerReviewsSubmitted").value(1))
                .andExpect(jsonPath("$.content[0].subjectId").value(elena.getId()))
                // And her own row is withheld from her on that very same page. Being a manager
                // somewhere does not make her a manager of herself, and there is a peer review
                // about her in the table to prove the assertion is not passing on an empty one.
                .andExpect(jsonPath("$.content[0].peerReviewsSubmitted").doesNotExist());

        // John's own row is on this same page - the summary admits SELF - and it carries no
        // count at all. Not zero: a zero is the count, and one bit at a time is still a leak.
        mvc.perform(get(REVIEWS).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].subjectId").value(john.getId()))
                .andExpect(jsonPath("$.content[0].peerReviewsSubmitted").doesNotExist());

    }
}
