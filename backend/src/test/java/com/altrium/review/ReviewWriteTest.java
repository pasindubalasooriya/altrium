package com.altrium.review;

// The OrgFixture field below is named `org`, which shadows the `org` package inside this
// class, so every com.altrium.org type has to arrive by import rather than fully qualified.
import com.altrium.org.AppUser;
import com.altrium.org.Department;
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

import java.time.LocalDate;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Features 7 to 11 - writing the four review streams, over HTTP.
 *
 * <p>Two things are being proved here, and they are different. The denials are the usual thing:
 * a direct call with a minted JWT is refused. The others are round trips - somebody writes and
 * then somebody else reads - because a write feature can only be wrong in ways a write-only
 * test cannot see. The peer-anonymity cases in particular are worth nothing unless real
 * feedback has actually been written first.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class ReviewWriteTest {

    private static final String REVIEWS = "/api/reviews";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrgFixture org;

    @Autowired
    private ReviewFixture reviews;

    private String bearer(String handle) {
        return "Bearer " + OrgFixture.tokenFor(handle);
    }

    private String cycleParam(ReviewCycle cycle) {
        return cycle.getId().toString();
    }

    // ================================================================ feature 7: self-review

    @Test
    @DisplayName("P-3.1: the subject drafts and then submits their own self-review")
    void P_3_1_subjectWritesTheirOwnSelfReview() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.flush();

        mvc.perform(put(REVIEWS + "/self-review").param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("john"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"achievements":"shipped the migration","challenges":"on-call was heavy"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").value(false));

        mvc.perform(put(REVIEWS + "/self-review")
                        .param("cycleId", cycleParam(cycle)).param("submit", "true")
                        .header("Authorization", bearer("john"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"achievements":"shipped the migration","goals":"lead a project"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").value(true));
    }

    @Test
    @DisplayName("P-3.1: a submitted self-review can no longer be changed")
    void P_3_1_selfReviewIsFinalOnceSubmitted() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.selfReview(cycle, john, "shipped the migration");
        reviews.flush();

        // 409, not 403. He has every right to write his own self-review; it is the submitted
        // record that refuses. Editing it afterwards would let him rewrite what his manager
        // has already read and acted on.
        mvc.perform(put(REVIEWS + "/self-review").param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("john"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"achievements":"actually I did rather more than that"}"""))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("P-6.2: nothing may be written while the cycle is not open")
    void P_6_2_writesAreRefusedOutsideAnOpenCycle() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        org.flush();

        ReviewCycle cycle = reviews.configuredCycle(1, LocalDate.of(2040, 1, 1));
        reviews.participant(cycle, john);
        reviews.flush();

        // 409 rather than 403, because it refuses everybody identically - the subject
        // included - and so says nothing at all about who is asking.
        mvc.perform(put(REVIEWS + "/self-review").param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("john"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"achievements":"early"}"""))
                .andExpect(status().isConflict());
    }

    // ================================================================ feature 8: peer assignment

    @Test
    @DisplayName("P-3.6: the manager assigns exactly two peers, cross-department allowed")
    void P_3_6_managerAssignsExactlyTwoPeers() throws Exception {
        Department engineering = org.department("Engineering");
        Department support = org.department("Support");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        AppUser omar = org.userIn(support, "omar", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.flush();

        // Omar is in another department on purpose. The scenario wants colleagues who have
        // actually worked with the subject, and those are not always in the same team.
        mvc.perform(put(REVIEWS + "/" + john.getId() + "/peers").param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"peerIds\":[" + aisha.getId() + "," + omar.getId() + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("P-3.6: one peer is refused, and so are three")
    void P_3_6_exactlyTwoOrNothing() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        AppUser mei = org.userIn(engineering, "mei", Role.EMPLOYEE);
        AppUser omar = org.userIn(engineering, "omar", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.flush();

        String peers = REVIEWS + "/" + john.getId() + "/peers";

        mvc.perform(put(peers).param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"peerIds\":[" + aisha.getId() + "]}"))
                .andExpect(status().isBadRequest());

        mvc.perform(put(peers).param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"peerIds\":[" + aisha.getId() + "," + mei.getId()
                                + "," + omar.getId() + "]}"))
                .andExpect(status().isBadRequest());

        // The same person twice is one peer, not two. Counted distinctly, or the unique key
        // would be the thing that noticed, as a 500.
        mvc.perform(put(peers).param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"peerIds\":[" + aisha.getId() + "," + aisha.getId() + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("P-3.6: the manager is never their own report's peer, nor is the subject")
    void P_3_6_managerAndSubjectAreExcludedAsPeers() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.flush();

        String peers = REVIEWS + "/" + john.getId() + "/peers";

        // She already writes the manager review. A second opinion from the same person is not
        // a second opinion.
        mvc.perform(put(peers).param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"peerIds\":[" + aisha.getId() + "," + elena.getId() + "]}"))
                .andExpect(status().isBadRequest());

        mvc.perform(put(peers).param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"peerIds\":[" + aisha.getId() + "," + john.getId() + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("P-3.12: an HR user cannot be assigned as a peer outside their own department")
    void P_3_12_hrIsRefusedAsAPeerOutsideTheirDepartment() throws Exception {
        Department engineering = org.department("Engineering");
        Department people = org.department("People");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        AppUser samuel = org.userIn(people, "samuel", Role.EMPLOYEE);
        AppUser kevin = org.userIn(people, "kevin", Role.EMPLOYEE, Role.HR);
        AppUser hana = org.userIn(people, "hana", Role.EMPLOYEE, Role.HR);
        john.setManager(elena);
        samuel.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.participant(cycle, samuel);
        reviews.flush();

        // Refused on the write and not only absent from the candidate list, because the list is
        // a convenience and this is the rule - an id typed straight into the request lands here.
        // Note there is no grant anywhere in this test: the rule is the role and the department,
        // not today's grants, which the Super Admin could widen tomorrow and turn an assignment
        // already made into a conflict.
        mvc.perform(put(REVIEWS + "/" + john.getId() + "/peers").param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"peerIds\":[" + aisha.getId() + "," + kevin.getId() + "]}"))
                .andExpect(status().isBadRequest());

        // Inside HR they can and they should. Samuel sits in People with both of them.
        mvc.perform(put(REVIEWS + "/" + samuel.getId() + "/peers").param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"peerIds\":[" + kevin.getId() + "," + hana.getId() + "]}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("P-0.7: a deactivated colleague cannot be assigned as a peer")
    void P_0_7_deactivatedPeerIsRefused() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        AppUser gone = org.deactivated("gone", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.flush();

        // Assigning one would guarantee feedback that never arrives, and John would be short a
        // peer review through no fault of anybody who could fix it.
        mvc.perform(put(REVIEWS + "/" + john.getId() + "/peers").param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"peerIds\":[" + aisha.getId() + "," + gone.getId() + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("P-1.2: a manager cannot assign peers for somebody who is not their report")
    void P_1_2_nonManagerCannotAssignPeers() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser tom = org.userIn(engineering, "tom", Role.EMPLOYEE, Role.MANAGER);
        AppUser omar = org.userIn(engineering, "omar", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        AppUser mei = org.userIn(engineering, "mei", Role.EMPLOYEE);
        omar.setManager(tom);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, omar);
        reviews.flush();

        // Elena is a manager, in the same department, and holds the Manager role. None of that
        // is the rule: the rule is whose manager she is (P-1.1).
        mvc.perform(put(REVIEWS + "/" + omar.getId() + "/peers").param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"peerIds\":[" + aisha.getId() + "," + mei.getId() + "]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-3.3: the subject cannot ask who was assigned to review them")
    void P_3_3_subjectCannotListTheirOwnPeers() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        AppUser mei = org.userIn(engineering, "mei", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.assignPeer(cycle, john, aisha, elena);
        reviews.assignPeer(cycle, john, mei, elena);
        reviews.flush();

        // Refused by the ordinary route rather than by a special rule about anonymity: this
        // endpoint needs ASSIGN_PEERS, whose only grounds are DIRECT_MANAGER, and the subject
        // is not their own manager. The count would be as damaging as the names.
        mvc.perform(get(REVIEWS + "/" + john.getId() + "/peers").param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("john")))
                .andExpect(status().isForbidden());

        mvc.perform(get(REVIEWS + "/" + john.getId() + "/peers").param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("P-3.4: a peer sees whom they must review, and never who reviews them")
    void P_3_4_peerSeesOnlyTheirOwnWorkload() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        AppUser mei = org.userIn(engineering, "mei", Role.EMPLOYEE);
        john.setManager(elena);
        aisha.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.participant(cycle, aisha);
        reviews.assignPeer(cycle, john, aisha, elena);
        reviews.assignPeer(cycle, john, mei, elena);
        // Aisha is also a reviewee, with her own peers. None of that may reach her.
        reviews.assignPeer(cycle, aisha, mei, elena);
        reviews.flush();

        // One entry: John, whom she must review. Her own reviewers do not appear, and neither
        // does the other person reviewing John - a peer who knew that would be one
        // conversation away from the subject knowing it too.
        mvc.perform(get(REVIEWS + "/my-peer-assignments").param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("aisha")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].subjectName").value("john"))
                .andExpect(jsonPath("$[0].submitted").value(false));
    }

    // ================================================================ features 9 and 10

    @Test
    @DisplayName("P-3.4: only an assigned peer may write, and only about their subject")
    void P_3_4_unassignedColleagueCannotWrite() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        AppUser omar = org.userIn(engineering, "omar", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.assignPeer(cycle, john, aisha, elena);
        reviews.flush();

        // Omar works alongside John and was not asked. Unsolicited feedback is not 360
        // feedback; it is a way to put an opinion in somebody's file.
        mvc.perform(post(REVIEWS + "/" + john.getId() + "/peer-review")
                        .param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("omar"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"feedback":"I have thoughts","rating":"NEEDS_IMPROVEMENT"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-3.5: a peer submits once; the second attempt is 409, not 403")
    void P_3_5_secondSubmissionIsRefusedAsAConflict() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.assignPeer(cycle, john, aisha, elena);
        reviews.flush();

        String url = REVIEWS + "/" + john.getId() + "/peer-review";
        String body = """
                {"feedback":"great to pair with","rating":"EXCEEDS_EXPECTATIONS"}""";

        mvc.perform(post(url).param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("aisha"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        // 403 would be wrong and misleading: she has the permission, and it is the record that
        // forbids the second write. She would otherwise go asking to be given access she holds.
        mvc.perform(post(url).param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("aisha"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("P-3.2/P-3.3: submitted feedback reaches the manager by name, and the subject never")
    void P_3_2_feedbackReachesTheManagerAndNotTheSubject() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.assignPeer(cycle, john, aisha, elena);
        reviews.flush();

        mvc.perform(post(REVIEWS + "/" + john.getId() + "/peer-review")
                        .param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("aisha"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"feedback":"sometimes overcommits","rating":"MEETS_EXPECTATIONS"}"""))
                .andExpect(status().isCreated());

        // The round trip is the point. Anonymity that has only been tested against an empty
        // table has not been tested.
        mvc.perform(get(REVIEWS + "/" + john.getId()).param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.peerReviews[0].peerName").value("aisha"))
                .andExpect(jsonPath("$.peerReviews[0].feedback").value("sometimes overcommits"));

        mvc.perform(get(REVIEWS + "/" + john.getId()).param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.peerReviews").doesNotExist());
    }

    @Test
    @DisplayName("P-3.6: peers cannot be swapped once feedback has been written")
    void P_3_6_peersAreFixedOnceFeedbackArrives() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        AppUser mei = org.userIn(engineering, "mei", Role.EMPLOYEE);
        AppUser omar = org.userIn(engineering, "omar", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.assignPeer(cycle, john, aisha, elena);
        reviews.assignPeer(cycle, john, mei, elena);
        reviews.peerReview(cycle, john, aisha, "great to pair with");
        reviews.flush();

        // Replacing Aisha now would strand what she wrote: the row would sit in the table
        // unreadable through any endpoint, which is worse than either keeping it or deleting
        // it, because nobody would know it was there.
        mvc.perform(put(REVIEWS + "/" + john.getId() + "/peers").param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"peerIds\":[" + mei.getId() + "," + omar.getId() + "]}"))
                .andExpect(status().isConflict());
    }

    // ================================================================ feature 11

    @Test
    @DisplayName("P-3.7: the manager writes and submits, and the subject then reads it")
    void P_3_7_managerReviewReachesTheSubject() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        // Both peers first: submitting a manager review waits for them by Product Owner ruling
        // (PeerFeedbackGate). Seeded rather than driven through the API, because what this test
        // is about is the manager review reaching the subject, not how the peer stream filled.
        reviews.peerReview(cycle, john, org.userIn(engineering, "peer-a-3-7", Role.EMPLOYEE), "Reliable");
        reviews.peerReview(cycle, john, org.userIn(engineering, "peer-b-3-7", Role.EMPLOYEE), "Collaborative");
        reviews.flush();

        mvc.perform(put(REVIEWS + "/" + john.getId() + "/manager-review")
                        .param("cycleId", cycleParam(cycle)).param("submit", "true")
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"feedback":"a strong quarter, ready for more scope"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").value(true));

        // Submitted, and not yet John's to read. P-4.4 gives the subject their rating **and**
        // their manager's feedback, and gives them together: the feedback is the assessment in
        // words, so handing it over first would tell him the outcome before anybody had decided
        // to tell him. This assertion used to run the other way round.
        mvc.perform(get(REVIEWS + "/" + john.getId()).param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managerReview").doesNotExist())
                .andExpect(jsonPath("$.visibleSections").value(not(hasItem("MANAGER_REVIEW"))))
                .andExpect(jsonPath("$.finalRating").doesNotExist());

        // Elena reads what she wrote throughout - the gate constrains the subject only, and
        // somebody has to be able to see the review they are about to release.
        mvc.perform(get(REVIEWS + "/" + john.getId()).param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managerReview.feedback")
                        .value("a strong quarter, ready for more scope"));

        mvc.perform(put(REVIEWS + "/" + john.getId() + "/rating")
                        .param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":\"MEETS_EXPECTATIONS\"}"))
                .andExpect(status().isOk());

        // Setting the rating is not sharing it. Still nothing for John.
        mvc.perform(get(REVIEWS + "/" + john.getId()).param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managerReview").doesNotExist())
                .andExpect(jsonPath("$.finalRating").doesNotExist());

        mvc.perform(post(REVIEWS + "/" + john.getId() + "/rating/release")
                        .param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("elena")))
                .andExpect(status().isOk());

        // Release opens both at once, which is the whole point of gating them together.
        mvc.perform(get(REVIEWS + "/" + john.getId()).param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("john")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managerReview.feedback")
                        .value("a strong quarter, ready for more scope"))
                .andExpect(jsonPath("$.managerReview.managerName").value("elena"))
                .andExpect(jsonPath("$.finalRating.rating").value("MEETS_EXPECTATIONS"));
    }

    @Test
    @DisplayName("P-1.2: a manager cannot write a review for somebody who is not their report")
    void P_1_2_managerCannotWriteNonReportReview() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser tom = org.userIn(engineering, "tom", Role.EMPLOYEE, Role.MANAGER);
        AppUser omar = org.userIn(engineering, "omar", Role.EMPLOYEE);
        omar.setManager(tom);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, omar);
        reviews.flush();

        mvc.perform(put(REVIEWS + "/" + omar.getId() + "/manager-review")
                        .param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"feedback":"I have opinions about Tom's team"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-2.1: HR may read a manager review in scope and may not write one")
    void P_2_1_hrCannotWriteAManagerReview() throws Exception {
        Department engineering = org.department("Engineering");
        Department peopleOps = org.department("People Operations");
        AppUser hana = org.userIn(peopleOps, "hana", Role.EMPLOYEE, Role.HR);
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.flush();

        // Reading and writing are different capabilities with different grounds, and holding
        // the first has never implied the second. WRITE_MANAGER_REVIEW simply does not list
        // HR_IN_SCOPE, so no grant of any width reaches this.
        mvc.perform(put(REVIEWS + "/" + john.getId() + "/manager-review")
                        .param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("hana"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"feedback":"HR's own assessment"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-3.7: a submitted manager review can no longer be changed")
    void P_3_7_managerReviewIsFinalOnceSubmitted() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.managerReview(cycle, john, elena, "strong quarter");
        reviews.flush();

        // The subject may already have read it (P-3.7), and a silently edited assessment is
        // not one they can respond to.
        mvc.perform(put(REVIEWS + "/" + john.getId() + "/manager-review")
                        .param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"feedback":"on reflection, less strong"}"""))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Section 7: a manager under review is peered by their own manager, one level up")
    void section_7_managerRevieweeIsHandledByTheSameRule() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser richard = org.user("richard", Role.EMPLOYEE, Role.LEADERSHIP);
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser tom = org.userIn(engineering, "tom", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        elena.setManager(richard);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, elena);
        reviews.flush();

        // Elena is the reviewee, so her peers are chosen by her manager. Richard is Leadership,
        // and needs no separate mechanism to do it: he is her direct manager, which is all
        // ASSIGN_PEERS asks. Her own report John is an allowed choice - scenario section 7
        // permits a manager's peers to be same-level managers or their own reports.
        mvc.perform(put(REVIEWS + "/" + elena.getId() + "/peers").param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("richard"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"peerIds\":[" + tom.getId() + "," + john.getId() + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // And she cannot choose her own.
        mvc.perform(put(REVIEWS + "/" + elena.getId() + "/peers").param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"peerIds\":[" + tom.getId() + "," + john.getId() + "]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-3.13: Leadership are refused as peers on the write as well")
    void P_3_13_leadershipRefusedAsPeer() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser richard = org.user("richard", Role.EMPLOYEE, Role.LEADERSHIP);
        AppUser elena = org.userIn(engineering, "elena", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha", Role.EMPLOYEE);
        AppUser mei = org.userIn(engineering, "mei", Role.EMPLOYEE);
        elena.setManager(richard);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.flush();

        String peers = REVIEWS + "/" + john.getId() + "/peers";

        mvc.perform(put(peers).param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"peerIds\":[" + aisha.getId() + "," + richard.getId() + "]}"))
                .andExpect(status().isBadRequest());

        // Two ordinary colleagues are still fine, so the rule has not taken the pool with it.
        mvc.perform(put(peers).param("cycleId", cycleParam(cycle))
                        .header("Authorization", bearer("elena"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"peerIds\":[" + aisha.getId() + "," + mei.getId() + "]}"))
                .andExpect(status().isOk());
    }
}
