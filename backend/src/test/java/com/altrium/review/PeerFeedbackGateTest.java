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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Product Owner's ruling: both of the manager's closing acts wait for both peer reviews.
 *
 * <p>Submitting a manager review and setting a final rating are refused until two peers have
 * submitted. This is a recorded deviation from scenario section 5, which lists the three
 * streams as parallel; {@link PeerFeedbackGate} carries the reasoning and the assumption it
 * rests on.
 *
 * <p>Every refusal here is a <strong>409</strong>, and the last two tests exist to keep it that
 * way. The manager holds the capability the whole time - a 403 would say they were the wrong
 * person, which is a different and false statement, and one that would be indistinguishable
 * from the genuine denials the rest of the suite proves.
 *
 * <p>The gate reads a count that P-3.3 forbids the subject to learn. The final test is the one
 * that matters for that: the subject provokes the same condition and is told nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class PeerFeedbackGateTest {

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

    /**
     * A manager, a direct report under review, and two assigned peers who have not written yet.
     */
    private record Scene(AppUser manager, AppUser subject, AppUser peerA, AppUser peerB,
                         ReviewCycle cycle) {}

    private Scene scene(String suffix) {
        Department engineering = org.department("Engineering-gate-" + suffix);
        AppUser elena = org.userIn(engineering, "elena-" + suffix, Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john-" + suffix, Role.EMPLOYEE);
        AppUser peerA = org.userIn(engineering, "peer-a-" + suffix, Role.EMPLOYEE);
        AppUser peerB = org.userIn(engineering, "peer-b-" + suffix, Role.EMPLOYEE);
        john.setManager(elena);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.assignPeer(cycle, john, peerA, elena);
        reviews.assignPeer(cycle, john, peerB, elena);
        reviews.flush();

        return new Scene(elena, john, peerA, peerB, cycle);
    }

    private String feedback(String text) {
        return "{\"feedback\":\"" + text + "\"}";
    }

    // ================================================================ the manager review

    @Test
    @DisplayName("submitting a manager review is refused with 409 while no peer has submitted")
    void managerReviewWaitsForBothPeers() throws Exception {
        Scene s = scene("none");

        mvc.perform(put(REVIEWS + "/" + s.subject().getId() + "/manager-review")
                        .param("cycleId", s.cycle().getId().toString()).param("submit", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(feedback("Strong year"))
                        .header("Authorization", bearer("elena-none")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("0 of 2")));
    }

    @Test
    @DisplayName("one peer is not enough; the ruling is both, not any")
    void onePeerIsNotEnough() throws Exception {
        Scene s = scene("one");
        reviews.peerReview(s.cycle(), s.subject(), s.peerA(), "Reliable");
        reviews.flush();

        mvc.perform(put(REVIEWS + "/" + s.subject().getId() + "/manager-review")
                        .param("cycleId", s.cycle().getId().toString()).param("submit", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(feedback("Strong year"))
                        .header("Authorization", bearer("elena-one")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("1 of 2")));
    }

    @Test
    @DisplayName("with both peers in, the manager review submits")
    void bothPeersOpensTheManagerReview() throws Exception {
        Scene s = scene("both");
        reviews.peerReview(s.cycle(), s.subject(), s.peerA(), "Reliable");
        reviews.peerReview(s.cycle(), s.subject(), s.peerB(), "Collaborative");
        reviews.flush();

        mvc.perform(put(REVIEWS + "/" + s.subject().getId() + "/manager-review")
                        .param("cycleId", s.cycle().getId().toString()).param("submit", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(feedback("Strong year"))
                        .header("Authorization", bearer("elena-both")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").value(true));
    }

    @Test
    @DisplayName("drafting waits too - the manager cannot write a word before both peers have")
    void draftingIsGatedAsWell() throws Exception {
        Scene s = scene("draft");

        // The stronger reading, by Product Owner ruling. A draft written before the peer
        // feedback arrives and submitted after it satisfies the timing while defeating the
        // purpose, because the manager's words are already on the page.
        mvc.perform(put(REVIEWS + "/" + s.subject().getId() + "/manager-review")
                        .param("cycleId", s.cycle().getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"Notes so far\"}")
                        .header("Authorization", bearer("elena-draft")))
                .andExpect(status().isConflict());

        // 409 and not 403 throughout: she holds WRITE_MANAGER_REVIEW the whole time, and a
        // denial would tell her she has no business reviewing her own report.
        mvc.perform(put(REVIEWS + "/" + s.subject().getId() + "/manager-review")
                        .param("cycleId", s.cycle().getId().toString()).param("submit", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(feedback("Strong year"))
                        .header("Authorization", bearer("elena-draft")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a blocked draft leaves no manager review row behind")
    void aBlockedDraftWritesNothing() throws Exception {
        Scene s = scene("norow");

        mvc.perform(put(REVIEWS + "/" + s.subject().getId() + "/manager-review")
                        .param("cycleId", s.cycle().getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"Notes so far\"}")
                        .header("Authorization", bearer("elena-norow")))
                .andExpect(status().isConflict());

        // The row used to be created before the gate was reached. An empty review sitting in
        // the table would be counted as started by the cycle monitoring, so a refused write
        // would have moved a completion figure.
        mvc.perform(get(REVIEWS + "/" + s.subject().getId())
                        .param("cycleId", s.cycle().getId().toString())
                        .header("Authorization", bearer("elena-norow")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managerReview").doesNotExist());
    }

    @Test
    @DisplayName("once both peers have submitted, the manager may draft as well as submit")
    void draftingOpensWithTheGate() throws Exception {
        Scene s = scene("open");
        reviews.peerReview(s.cycle(), s.subject(), s.peerA(), "Reliable");
        reviews.peerReview(s.cycle(), s.subject(), s.peerB(), "Collaborative");
        reviews.flush();

        mvc.perform(put(REVIEWS + "/" + s.subject().getId() + "/manager-review")
                        .param("cycleId", s.cycle().getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"Notes so far\"}")
                        .header("Authorization", bearer("elena-open")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").value(false));
    }

    // ================================================================ the final rating

    @Test
    @DisplayName("P-4.1: setting a final rating is refused with 409 until both peers have submitted")
    void P_4_1_ratingWaitsForBothPeers() throws Exception {
        Scene s = scene("rating");
        reviews.peerReview(s.cycle(), s.subject(), s.peerA(), "Reliable");
        reviews.flush();

        // Section 5 step 4 has the manager read the peer ratings and then set one final rating.
        // The system cannot observe the reading, so it enforces that there is something to read.
        mvc.perform(put(REVIEWS + "/" + s.subject().getId() + "/rating")
                        .param("cycleId", s.cycle().getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":\"MEETS_EXPECTATIONS\"}")
                        .header("Authorization", bearer("elena-rating")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("1 of 2")));
    }

    @Test
    @DisplayName("with both peers in, the rating is set")
    void bothPeersOpensTheRating() throws Exception {
        Scene s = scene("rated");
        reviews.peerReview(s.cycle(), s.subject(), s.peerA(), "Reliable");
        reviews.peerReview(s.cycle(), s.subject(), s.peerB(), "Collaborative");
        reviews.flush();

        mvc.perform(put(REVIEWS + "/" + s.subject().getId() + "/rating")
                        .param("cycleId", s.cycle().getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":\"EXCEEDS_EXPECTATIONS\"}")
                        .header("Authorization", bearer("elena-rated")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value("EXCEEDS_EXPECTATIONS"));
    }

    // ================================================================ what the gate must not leak

    @Test
    @DisplayName("P-3.3: the gate is a 409 for the manager and stays a 403 for everybody else")
    void P_3_3_theGateIsNotAWayIn() throws Exception {
        Scene s = scene("leak");
        reviews.peerReview(s.cycle(), s.subject(), s.peerA(), "Reliable");
        reviews.flush();

        // The subject provokes the same condition on the same endpoint. If the gate ran before
        // the authorization decision, the count would come back in the body - the peer count is
        // exactly what P-3.3 forbids the subject to learn, and it would arrive one digit at a
        // time from an endpoint nobody thought of as subject-facing.
        mvc.perform(put(REVIEWS + "/" + s.subject().getId() + "/rating")
                        .param("cycleId", s.cycle().getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":\"MEETS_EXPECTATIONS\"}")
                        .header("Authorization", bearer("john-leak")))
                .andExpect(status().isForbidden());

        // And the subject's own record still carries no peer section at all, gate or no gate.
        mvc.perform(get(REVIEWS + "/" + s.subject().getId())
                        .param("cycleId", s.cycle().getId().toString())
                        .header("Authorization", bearer("john-leak")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.peerReviews").doesNotExist());
    }

    @Test
    @DisplayName("an unrelated manager is refused with 403, not told how many peers have written")
    void anotherManagerLearnsNothing() throws Exception {
        Scene s = scene("other");
        AppUser mallory = org.user("mallory-other", Role.EMPLOYEE, Role.MANAGER);
        org.flush();

        mvc.perform(put(REVIEWS + "/" + s.subject().getId() + "/rating")
                        .param("cycleId", s.cycle().getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":\"MEETS_EXPECTATIONS\"}")
                        .header("Authorization", bearer("mallory-other")))
                .andExpect(status().isForbidden());

        // Named so the unused-variable warning does not hide the fact that Mallory is a real
        // manager, just not this subject's - the denial is relational, not role-based.
        assert mallory.getId() != null;
    }
}
