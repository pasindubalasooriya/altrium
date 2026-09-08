package com.altrium.review;

// The OrgFixture field below is named `org`, which shadows the `org` package inside this
// class, so every com.altrium.org type has to arrive by import rather than fully qualified.
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Features 12 to 14 - the final rating, its calibration, and the employee's view of it.
 *
 * <p>One row, four actors, and the interesting failures are all about ordering: a manager
 * setting a rating back after HR moved it, a rating changing after the employee has been told,
 * an HR Head reaching their own. Each is a state the row can be in rather than a person the
 * caller can be, which is why they are 409 and the access denials are 403.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class RatingTest {

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

    private static String rating(Rating value) {
        return "{\"rating\":\"" + value.name() + "\"}";
    }

    // ================================================================ feature 12: the manager chooses

    @Test
    @DisplayName("P-4.1: the manager sets the final rating for a direct report")
    void P_4_1_managerSetsTheFinalRating() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        // Section 5 step 4 has the manager read the peer ratings before setting the final one,
        // and PeerFeedbackGate now enforces that they exist to be read. Seeded, because this
        // test is about the rating and not about how the peer stream filled.
        reviews.peerReview(cycle, john, org.userIn(engineering, "peer-a-4-1", Role.EMPLOYEE), "Reliable");
        reviews.peerReview(cycle, john, org.userIn(engineering, "peer-b-4-1", Role.EMPLOYEE), "Collaborative");
        reviews.flush();

        mvc.perform(put(REVIEWS + "/" + john.getId() + "/rating")
                        .param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rating(Rating.EXCEEDS_EXPECTATIONS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value("EXCEEDS_EXPECTATIONS"))
                // Set is not shared. The employee learns nothing until somebody releases it.
                .andExpect(jsonPath("$.released").value(false));
    }

    /**
     * Setting the rating is what submits it for sign-off, so it cannot be taken back.
     *
     * <p>The window this closes was real: HR could be looking at a figure the manager was still
     * free to change underneath them, and nothing recorded that it had moved. The calibration
     * trail answers "who decided this and when", and a rating revised between submission and
     * sign-off is a decision the trail cannot see.
     *
     * <p>409 and not 403. Elena holds {@code SET_FINAL_RATING} on John throughout - she is his
     * manager and that does not change. It is the record's state that refuses, which is the
     * same distinction a second peer submission draws.
     */
    @Test
    @DisplayName("P-4.1: the manager cannot change a rating once it is submitted for calibration")
    void P_4_1_ratingIsFinalOnceSubmittedForCalibration() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena-once", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john-once", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.peerReview(cycle, john, org.userIn(engineering, "peer-a-once", Role.EMPLOYEE), "Reliable");
        reviews.peerReview(cycle, john, org.userIn(engineering, "peer-b-once", Role.EMPLOYEE), "Collaborative");
        reviews.flush();

        mvc.perform(put(REVIEWS + "/" + john.getId() + "/rating")
                        .param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("elena-once"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rating(Rating.MEETS_EXPECTATIONS)))
                .andExpect(status().isOk());

        // No HR involvement yet: it is with them, and that alone is what refuses.
        mvc.perform(put(REVIEWS + "/" + john.getId() + "/rating")
                        .param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("elena-once"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rating(Rating.EXCEEDS_EXPECTATIONS)))
                .andExpect(status().isConflict());

        // And the refusal actually held - the stored figure is still the one she submitted,
        // not the one the second call tried to write. Asserted through the record, because a
        // 409 alone would not catch a version that refused *after* writing.
        mvc.perform(get(REVIEWS + "/" + john.getId())
                        .param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("elena-once")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finalRating.rating").value("MEETS_EXPECTATIONS"));
    }

    @Test
    @DisplayName("P-4.1: nobody but the subject's own manager sets the rating")
    void P_4_1_onlyTheDirectManagerSetsTheRating() throws Exception {
        Department engineering = org.department("Engineering");
        Department peopleOps = org.department("People Operations");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser tom = org.userIn(engineering, "tom", Role.EMPLOYEE, Role.MANAGER);
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();
        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.flush();

        String url = REVIEWS + "/" + john.getId() + "/rating";

        // HR hold a grant covering his department and may read everything about him. Setting
        // the rating is a different capability with different grounds, and HR_IN_SCOPE is not
        // among them - HR normalise a manager's judgment, they do not substitute for it.
        mvc.perform(put(url).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("hana"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rating(Rating.NEEDS_IMPROVEMENT)))
                .andExpect(status().isForbidden());

        // Another manager in the same department.
        mvc.perform(put(url).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("tom"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rating(Rating.EXCEEDS_EXPECTATIONS)))
                .andExpect(status().isForbidden());

        // And the subject, who has the most obvious motive of anyone.
        mvc.perform(put(url).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("john"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rating(Rating.EXCEEDS_EXPECTATIONS)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-4.1: no rating appears until a manager chooses one, however the peers rated")
    void P_4_1_nothingIsComputedFromThePeerRatings() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        AppUser mei = org.userIn(engineering, "mei", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.peerReview(cycle, john, aisha, "great to pair with");
        reviews.peerReview(cycle, john, mei, "sometimes overcommits");
        reviews.flush();

        // Two peer ratings exist and the manager can read both. There is still no final
        // rating, because one is chosen rather than derived - and there is no endpoint that
        // would offer an average, because an average shown beside the choice becomes a
        // default that has to be argued away (P-4.1).
        mvc.perform(get(REVIEWS + "/" + john.getId()).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.peerReviews.length()").value(2))
                .andExpect(jsonPath("$.finalRating").doesNotExist());
    }

    // ================================================================ feature 13: calibration

    @Test
    @DisplayName("P-4.3: HR calibrates, and the audit row records what it was and who moved it")
    void P_4_3_calibrationRecordsBeforeAfterAndActor() throws Exception {
        Department engineering = org.department("Engineering");
        Department peopleOps = org.department("People Operations");
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();
        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.unreleasedRating(cycle, john, elena, Rating.EXCEEDS_EXPECTATIONS);
        reviews.flush();

        mvc.perform(put(REVIEWS + "/" + john.getId() + "/rating/calibration")
                        .param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("hana"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating":"MEETS_EXPECTATIONS",
                                 "note":"normalised against the rest of Engineering"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("EXCEEDS_EXPECTATIONS"))
                .andExpect(jsonPath("$.to").value("MEETS_EXPECTATIONS"))
                .andExpect(jsonPath("$.by").value("hana"));

        // The before-value is what makes the change visible at all. Without it, a calibrated
        // rating is indistinguishable from a manager who simply chose differently.
        mvc.perform(get(REVIEWS + "/" + john.getId() + "/rating/calibration")
                        .param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].from").value("EXCEEDS_EXPECTATIONS"));
    }

    @Test
    @DisplayName("P-0.6/P-2.2: an HR Head cannot calibrate their own rating, explicit grant and all")
    void P_2_2_hrHeadCannotCalibrateTheirOwnRating() throws Exception {
        Department peopleOps = org.department("People Operations");
        AppUser richard = org.user("richard", Role.EMPLOYEE, Role.LEADERSHIP);
        AppUser kevin = org.userIn(peopleOps, "kevin", Role.EMPLOYEE, Role.HR);
        kevin.setManager(richard);
        org.flush();
        grants.grant(kevin.getId(), peopleOps.getId(), true, null, g -> g.getId());

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, kevin);
        reviews.unreleasedRating(cycle, kevin, richard, Rating.MEETS_EXPECTATIONS);
        reviews.flush();

        // The rule-ordering test, in the place it matters most. His explicit grant covers his
        // own department, which is exactly the block P-2.4 lifts - and the own-review block is
        // decided a step earlier, so no grant reaches it. Marking your own homework is the one
        // thing the whole evaluation order exists to prevent.
        mvc.perform(put(REVIEWS + "/" + kevin.getId() + "/rating/calibration")
                        .param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("kevin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rating(Rating.EXCEEDS_EXPECTATIONS)))
                .andExpect(status().isForbidden());

        // Nor may he read the trail of somebody else's decision about him.
        mvc.perform(get(REVIEWS + "/" + kevin.getId() + "/rating/calibration")
                        .param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("kevin")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-4.3: the manager cannot calibrate, and the subject cannot read the trail")
    void P_4_3_calibrationIsHrsAloneAndInvisibleToTheSubject() throws Exception {
        Department engineering = org.department("Engineering");
        Department peopleOps = org.department("People Operations");
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();
        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.unreleasedRating(cycle, john, elena, Rating.MEETS_EXPECTATIONS);
        reviews.flush();

        // Calibration is the segregation of duties: the manager judges, HR normalises. A
        // manager able to calibrate could simply overwrite their own rating twice.
        mvc.perform(put(REVIEWS + "/" + john.getId() + "/rating/calibration")
                        .param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rating(Rating.EXCEEDS_EXPECTATIONS)))
                .andExpect(status().isForbidden());

        mvc.perform(put(REVIEWS + "/" + john.getId() + "/rating/calibration")
                        .param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("hana"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rating(Rating.EXCEEDS_EXPECTATIONS)))
                .andExpect(status().isOk());

        // P-4.4 gives John his rating and his manager's feedback and nothing else. "Elena said
        // Meets, HR moved it to Exceeds" is neither, and would undermine the conversation
        // Elena has to hold with him. READ_RATING_AUDIT has no SELF grounds at all.
        mvc.perform(get(REVIEWS + "/" + john.getId() + "/rating/calibration")
                        .param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("john")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-4.3: the manager cannot set the rating back after HR has moved it")
    void P_4_3_calibrationIsNotAdvisory() throws Exception {
        Department engineering = org.department("Engineering");
        Department peopleOps = org.department("People Operations");
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();
        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.unreleasedRating(cycle, john, elena, Rating.EXCEEDS_EXPECTATIONS);
        reviews.flush();

        mvc.perform(put(REVIEWS + "/" + john.getId() + "/rating/calibration")
                        .param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("hana"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rating(Rating.MEETS_EXPECTATIONS)))
                .andExpect(status().isOk());

        // 409, not 403: Elena still holds SET_FINAL_RATING and it is the record that refuses.
        // Were this allowed, normalisation would be a suggestion, and the audit row would
        // record a change that no longer held - worse than no audit, because it reads as
        // authoritative.
        mvc.perform(put(REVIEWS + "/" + john.getId() + "/rating")
                        .param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rating(Rating.EXCEEDS_EXPECTATIONS)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("P-4.3: calibrating to the value it already holds is refused")
    void P_4_3_noOpCalibrationIsRefused() throws Exception {
        Department engineering = org.department("Engineering");
        Department peopleOps = org.department("People Operations");
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();
        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.unreleasedRating(cycle, john, elena, Rating.MEETS_EXPECTATIONS);
        reviews.flush();

        // Every row in the audit table is supposed to mean "somebody moved this". HR agreeing
        // with the manager is the normal case and leaves no trace, which is right.
        mvc.perform(put(REVIEWS + "/" + john.getId() + "/rating/calibration")
                        .param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("hana"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rating(Rating.MEETS_EXPECTATIONS)))
                .andExpect(status().isBadRequest());
    }

    // ================================================================ feature 14: release and read

    @Test
    @DisplayName("P-4.4: before release the employee cannot tell a withheld rating from none at all")
    void P_4_4_unreleasedRatingIsIndistinguishableFromNoRating() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        john.setManager(elena);
        aisha.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.participant(cycle, aisha);
        // John has a rating that has not been shared. Aisha has none at all.
        reviews.unreleasedRating(cycle, john, elena, Rating.NEEDS_IMPROVEMENT);
        reviews.managerReview(cycle, john, elena, "a difficult quarter");
        reviews.flush();

        // The two responses are identical, and that is the point. Distinguishing them would
        // tell John a rating had been decided and was being kept from him, which is the fact
        // P-4.4 exists to withhold. His manager's feedback is held back with it - feedback
        // arriving first would tell him the outcome without telling him the outcome.
        mvc.perform(get(REVIEWS + "/my-rating").param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(false))
                .andExpect(jsonPath("$.rating").doesNotExist())
                .andExpect(jsonPath("$.managerFeedback").doesNotExist());

        mvc.perform(get(REVIEWS + "/my-rating").param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("aisha")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(false))
                .andExpect(jsonPath("$.rating").doesNotExist());
    }

    @Test
    @DisplayName("P-4.4: once released, the rating and the manager's feedback arrive together")
    void P_4_4_releasedRatingReachesTheEmployee() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.unreleasedRating(cycle, john, elena, Rating.MEETS_EXPECTATIONS);
        reviews.managerReview(cycle, john, elena, "solid, and ready for more scope");
        reviews.peerReview(cycle, john, aisha, "sometimes overcommits");
        reviews.flush();

        mvc.perform(post(REVIEWS + "/" + john.getId() + "/rating/release")
                        .param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(true));

        // Scenario section 5 step 6 shares the two together, and nothing else travels with
        // them. The peer feedback that fed the manager's judgment stays where it was.
        mvc.perform(get(REVIEWS + "/my-rating").param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(true))
                .andExpect(jsonPath("$.rating").value("MEETS_EXPECTATIONS"))
                .andExpect(jsonPath("$.managerFeedback").value("solid, and ready for more scope"));

        mvc.perform(get(REVIEWS + "/" + john.getId()).param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.peerReviews").doesNotExist());
    }

    @Test
    @DisplayName("P-4.4: a released rating can no longer be changed or calibrated")
    void P_4_4_releaseFreezesTheRating() throws Exception {
        Department engineering = org.department("Engineering");
        Department peopleOps = org.department("People Operations");
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();
        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.releasedRating(cycle, john, elena, Rating.MEETS_EXPECTATIONS);
        reviews.flush();

        // He has been told this number. Changing it behind him would mean the rating he holds
        // and the rating on file are different things.
        mvc.perform(put(REVIEWS + "/" + john.getId() + "/rating")
                        .param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rating(Rating.NEEDS_IMPROVEMENT)))
                .andExpect(status().isConflict());

        mvc.perform(put(REVIEWS + "/" + john.getId() + "/rating/calibration")
                        .param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("hana"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rating(Rating.EXCEEDS_EXPECTATIONS)))
                .andExpect(status().isConflict());

        // And releasing twice is a conflict rather than a quiet success: the second caller
        // believes they are doing something, and should be told they are not.
        mvc.perform(post(REVIEWS + "/" + john.getId() + "/rating/release")
                        .param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("P-2.6: Leadership release the HR Head's rating, as their manager")
    void P_2_6_leadershipReleasesTheHrHeadsRating() throws Exception {
        Department peopleOps = org.department("People Operations");
        AppUser richard = org.user("richard", Role.EMPLOYEE, Role.LEADERSHIP);
        AppUser kevin = org.userIn(peopleOps, "kevin", Role.EMPLOYEE, Role.HR);
        kevin.setManager(richard);
        org.flush();
        grants.grant(kevin.getId(), peopleOps.getId(), true, null, g -> g.getId());

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, kevin);
        reviews.unreleasedRating(cycle, kevin, richard, Rating.EXCEEDS_EXPECTATIONS);
        reviews.managerReview(cycle, kevin, richard, "steady stewardship of the function");
        reviews.flush();

        // This is why RELEASE_RATING lists DIRECT_MANAGER as well as HR_IN_SCOPE. P-2.2
        // withholds HR grounds on one's own case, so an HR-only release would leave the HR
        // Head's rating permanently unreleasable - reviewed by Leadership under P-2.6 and
        // never told the outcome. Richard releases it as his manager, and no special case is
        // needed anywhere.
        mvc.perform(post(REVIEWS + "/" + kevin.getId() + "/rating/release")
                        .param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("richard")))
                .andExpect(status().isOk());

        // And he reads it exactly as any other employee does, on SELF grounds.
        mvc.perform(get(REVIEWS + "/my-rating").param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("kevin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value("EXCEEDS_EXPECTATIONS"))
                .andExpect(jsonPath("$.managerFeedback").value("steady stewardship of the function"));
    }

    @Test
    @DisplayName("P-9.4: the Super Admin sets no rating and reads none")
    void P_9_4_superAdminIsAbsentFromRatingsEntirely() throws Exception {
        Department engineering = org.department("Engineering");
        org.userIn(engineering, "devin", Role.EMPLOYEE, Role.SUPER_ADMIN);
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.releasedRating(cycle, john, elena, Rating.MEETS_EXPECTATIONS);
        reviews.flush();

        // They configured the cycle this rating belongs to and grant HR the departments it is
        // calibrated in. Reading it on top of that would make the role omnipotent.
        mvc.perform(put(REVIEWS + "/" + john.getId() + "/rating")
                        .param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("devin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rating(Rating.NEEDS_IMPROVEMENT)))
                .andExpect(status().isForbidden());

        mvc.perform(get(REVIEWS + "/" + john.getId() + "/rating/calibration")
                        .param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("devin")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-1.5: Leadership hold no rating, so none can be set for them")
    void P_1_5_leadershipIsNeverRated() throws Exception {
        AppUser board = org.user("board", Role.EMPLOYEE, Role.LEADERSHIP);
        AppUser richard = org.user("richard", Role.EMPLOYEE, Role.LEADERSHIP);
        richard.setManager(board);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.flush();

        // Refused at creation rather than hidden on read. Richard's manager is asking, which
        // would be grounds for anybody else - it is who Richard is that refuses.
        mvc.perform(put(REVIEWS + "/" + richard.getId() + "/rating")
                        .param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("board"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rating(Rating.EXCEEDS_EXPECTATIONS)))
                .andExpect(status().isForbidden());
    }

    // ================================================================ P-4.8: HR sign-off

    /** A subject with a manager, two peer reviews in, and an HR user granted their department. */
    private record SignOff(ReviewCycle cycle, AppUser subject, AppUser manager, AppUser hr) {
    }

    private SignOff signOffScene(String tag) {
        Department engineering = org.department("Engineering-" + tag);
        Department people = org.department("People-" + tag);
        AppUser elena = org.userIn(engineering, "elena-" + tag, Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john-" + tag, Role.EMPLOYEE);
        AppUser kevin = org.userIn(people, "kevin-" + tag, Role.EMPLOYEE, Role.HR);
        john.setManager(elena);
        org.flush();
        grants.grant(kevin.getId(), engineering.getId(), false, null, g -> g.getId());

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.peerReview(cycle, john, org.userIn(engineering, "pa-" + tag, Role.EMPLOYEE), "Reliable");
        reviews.peerReview(cycle, john, org.userIn(engineering, "pb-" + tag, Role.EMPLOYEE), "Collaborative");
        reviews.flush();

        return new SignOff(cycle, john, elena, kevin);
    }

    private void setRating(SignOff s, String tag) throws Exception {
        mvc.perform(put(REVIEWS + "/" + s.subject().getId() + "/rating")
                        .param("cycleId", s.cycle().getId().toString())
                        .header("Authorization", bearer("elena-" + tag))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rating(Rating.MEETS_EXPECTATIONS)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("P-4.8: a rating cannot be shared until HR have signed it off")
    void P_4_8_releaseWaitsForHrSignOff() throws Exception {
        SignOff s = signOffScene("gate");
        setRating(s, "gate");

        String release = REVIEWS + "/" + s.subject().getId() + "/rating/release";
        String cycleId = s.cycle().getId().toString();

        // 409 and not 403: Elena holds RELEASE_RATING throughout, and it is the state of the
        // sign-off that refuses. A denial would tell her she is the wrong person, which is false.
        mvc.perform(post(release).param("cycleId", cycleId)
                        .header("Authorization", bearer("elena-gate")))
                .andExpect(status().isConflict());

        mvc.perform(post(REVIEWS + "/" + s.subject().getId() + "/rating/approval")
                        .param("cycleId", cycleId)
                        .header("Authorization", bearer("kevin-gate")))
                .andExpect(status().isOk())
                // Recorded as a calibration row whose before and after are equal - the table
                // exists to say who touched this rating, and an approval is exactly that.
                .andExpect(jsonPath("$.from").value("MEETS_EXPECTATIONS"))
                .andExpect(jsonPath("$.to").value("MEETS_EXPECTATIONS"))
                .andExpect(jsonPath("$.by").value("kevin-gate"));

        mvc.perform(post(release).param("cycleId", cycleId)
                        .header("Authorization", bearer("elena-gate")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("P-4.8: calibrating is a sign-off too - HR need not then also approve")
    void P_4_8_calibrationSignsOffAsWell() throws Exception {
        SignOff s = signOffScene("calib");
        setRating(s, "calib");
        String cycleId = s.cycle().getId().toString();

        mvc.perform(put(REVIEWS + "/" + s.subject().getId() + "/rating/calibration")
                        .param("cycleId", cycleId)
                        .header("Authorization", bearer("kevin-calib"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":\"EXCEEDS_EXPECTATIONS\",\"note\":\"normalised\"}"))
                .andExpect(status().isOk());

        // HR have been through it and moved it, which is the stronger form of having looked.
        mvc.perform(post(REVIEWS + "/" + s.subject().getId() + "/rating/release")
                        .param("cycleId", cycleId)
                        .header("Authorization", bearer("elena-calib")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("P-4.8: approving an unchanged rating is its own act, and only once")
    void P_4_8_approvalIsSeparateFromCalibrationAndHappensOnce() throws Exception {
        SignOff s = signOffScene("once");
        setRating(s, "once");
        String cycleId = s.cycle().getId().toString();
        String approval = REVIEWS + "/" + s.subject().getId() + "/rating/approval";

        // Calibrating to the value it already holds is still refused, and now says where to go.
        mvc.perform(put(REVIEWS + "/" + s.subject().getId() + "/rating/calibration")
                        .param("cycleId", cycleId)
                        .header("Authorization", bearer("kevin-once"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rating(Rating.MEETS_EXPECTATIONS)))
                .andExpect(status().isBadRequest());

        mvc.perform(post(approval).param("cycleId", cycleId)
                        .header("Authorization", bearer("kevin-once")))
                .andExpect(status().isOk());

        // A second sign-off is a 409, not a silent success: the caller believes they are doing
        // something, and the first one is already what unlocked the release.
        mvc.perform(post(approval).param("cycleId", cycleId)
                        .header("Authorization", bearer("kevin-once")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("P-2.2/P-4.8: an HR user cannot sign off their own rating, explicit grant or not")
    void P_4_8_hrCannotSignOffTheirOwnRating() throws Exception {
        Department people = org.department("People-self");
        AppUser richard = org.user("richard-self", Role.EMPLOYEE, Role.LEADERSHIP);
        AppUser kevin = org.userIn(people, "kevin-self", Role.EMPLOYEE, Role.HR);
        kevin.setManager(richard);
        org.flush();
        grants.grant(kevin.getId(), people.getId(), true, null, g -> g.getId());

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, kevin);
        reviews.peerReview(cycle, kevin, org.userIn(people, "pa-self", Role.EMPLOYEE), "Reliable");
        reviews.peerReview(cycle, kevin, org.userIn(people, "pb-self", Role.EMPLOYEE), "Steady");
        reviews.flush();

        mvc.perform(put(REVIEWS + "/" + kevin.getId() + "/rating")
                        .param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("richard-self"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rating(Rating.MEETS_EXPECTATIONS)))
                .andExpect(status().isOk());

        // The explicit grant lifts the own-department block and never the own-review block.
        mvc.perform(post(REVIEWS + "/" + kevin.getId() + "/rating/approval")
                        .param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("kevin-self")))
                .andExpect(status().isForbidden());

        // And because he is the only HR user with authority over his own department, nobody
        // could sign it off. The gate stands aside rather than stranding the rating - his
        // manager shares it, which is the alternative to a number that never reaches him.
        mvc.perform(post(REVIEWS + "/" + kevin.getId() + "/rating/release")
                        .param("cycleId", cycle.getId().toString())
                        .header("Authorization", bearer("richard-self")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("P-4.7/P-4.8: the manager is told whether HR have signed off; the subject is not")
    void P_4_8_signOffStateIsWithheldFromTheSubject() throws Exception {
        SignOff s = signOffScene("view");
        setRating(s, "view");
        String cycleId = s.cycle().getId().toString();
        String record = REVIEWS + "/" + s.subject().getId();

        mvc.perform(get(record).param("cycleId", cycleId)
                        .header("Authorization", bearer("elena-view")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finalRating.signedOffByHr").value(false));

        mvc.perform(post(REVIEWS + "/" + s.subject().getId() + "/rating/approval")
                        .param("cycleId", cycleId)
                        .header("Authorization", bearer("kevin-view")))
                .andExpect(status().isOk());
        mvc.perform(post(REVIEWS + "/" + s.subject().getId() + "/rating/release")
                        .param("cycleId", cycleId)
                        .header("Authorization", bearer("elena-view")))
                .andExpect(status().isOk());

        mvc.perform(get(record).param("cycleId", cycleId)
                        .header("Authorization", bearer("elena-view")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finalRating.signedOffByHr").value(true));

        // John now reads his released rating, and learns nothing about who signed it off.
        // Whether HR were through it is part of the calibration trail, and READ_RATING_AUDIT
        // has no SELF grounds (P-4.7). Absent, not false - a false is an answer.
        mvc.perform(get(record).param("cycleId", cycleId)
                        .header("Authorization", bearer("john-view")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finalRating.rating").value("MEETS_EXPECTATIONS"))
                .andExpect(jsonPath("$.finalRating.signedOffByHr").doesNotExist());
    }

    @Test
    @DisplayName("P-4.8: the list says whose turn a rating is, and never tells the subject")
    void P_4_8_theListSaysWhoseTurnTheRatingIs() throws Exception {
        SignOff s = signOffScene("stage");
        String cycleId = s.cycle().getId().toString();
        String subject = "$.content[?(@.subjectId == " + s.subject().getId() + ")].ratingStage";

        // Elena's own list. Nothing set yet.
        mvc.perform(get(REVIEWS).param("cycleId", cycleId)
                        .header("Authorization", bearer("elena-stage")))
                .andExpect(status().isOk())
                .andExpect(jsonPath(subject).value(hasItem("NOT_SET")));

        setRating(s, "stage");

        // Her turn is over and Kevin's has begun. Both are told the same thing, because both
        // have to act on it - he signs off, she waits.
        mvc.perform(get(REVIEWS).param("cycleId", cycleId)
                        .header("Authorization", bearer("elena-stage")))
                .andExpect(jsonPath(subject).value(hasItem("AWAITING_SIGN_OFF")));
        mvc.perform(get(REVIEWS).param("cycleId", cycleId)
                        .header("Authorization", bearer("kevin-stage")))
                .andExpect(jsonPath(subject).value(hasItem("AWAITING_SIGN_OFF")));

        // John's own row on his own list carries nothing. Whether HR have been through his
        // rating is the calibration trail, which has no SELF ground (P-4.7) - and "a rating
        // exists and is sitting with HR" is exactly what release exists to withhold.
        mvc.perform(get(REVIEWS).param("cycleId", cycleId)
                        .header("Authorization", bearer("john-stage")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].ratingStage").doesNotExist());

        mvc.perform(post(REVIEWS + "/" + s.subject().getId() + "/rating/approval")
                        .param("cycleId", cycleId)
                        .header("Authorization", bearer("kevin-stage")))
                .andExpect(status().isOk());

        // Back to Elena, and the flag says so.
        mvc.perform(get(REVIEWS).param("cycleId", cycleId)
                        .header("Authorization", bearer("elena-stage")))
                .andExpect(jsonPath(subject).value(hasItem("SIGNED_OFF")));

        mvc.perform(post(REVIEWS + "/" + s.subject().getId() + "/rating/release")
                        .param("cycleId", cycleId)
                        .header("Authorization", bearer("elena-stage")))
                .andExpect(status().isOk());

        // Nobody's turn now, so neither of them is prompted again.
        mvc.perform(get(REVIEWS).param("cycleId", cycleId)
                        .header("Authorization", bearer("elena-stage")))
                .andExpect(jsonPath(subject).value(hasItem("SHARED")));
        mvc.perform(get(REVIEWS).param("cycleId", cycleId)
                        .header("Authorization", bearer("kevin-stage")))
                .andExpect(jsonPath(subject).value(hasItem("SHARED")));
    }
}
