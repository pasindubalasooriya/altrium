package com.altrium.review;

// The OrgFixture field below is named `org`, which shadows the `org` package inside this
// class, so every com.altrium.org type has to arrive by import rather than fully qualified.
import com.altrium.org.AppUser;
import com.altrium.org.Department;
import com.altrium.org.Role;
import com.altrium.testsupport.OrgFixture;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The peer-candidate list (P-3.6), added because the manager console had no way to choose two
 * people: the only endpoint that lists users is the Super Admin's.
 *
 * <p>It is a directory, so the gate on it matters more than usual. It is guarded by
 * {@code ASSIGN_PEERS} - the capability of the write it feeds - which means the roster is
 * reachable only by the person entitled to pick from it, and only in the act of picking for
 * one named subject. There is no route to it that is not an act of assignment.
 *
 * <p>The exclusions are asserted against the write path deliberately. A candidate list built
 * from a different rule would offer somebody the write then refuses, and that reads as a bug
 * rather than as the rule it is.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class PeerCandidateTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrgFixture org;

    private record Team(AppUser manager, AppUser subject, AppUser colleague, AppUser dormant) {
    }

    /** John reports to Jane. Mei is a colleague; Tara is deactivated. */
    private Team team() {
        Department engineering = org.department("Engineering-candidates");
        AppUser jane = org.userIn(engineering, "jane-cand", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john-cand", Role.EMPLOYEE);
        AppUser mei = org.userIn(engineering, "mei-cand", Role.EMPLOYEE);
        AppUser tara = org.deactivated("tara-cand", Role.EMPLOYEE);
        john.setManager(jane);
        mei.setManager(jane);
        org.flush();
        return new Team(jane, john, mei, tara);
    }

    private String candidates(Long subjectId) {
        return "/api/reviews/" + subjectId + "/peer-candidates";
    }

    @Test
    @DisplayName("P-3.6: the subject's manager sees candidates, excluding the subject and themselves")
    void P_3_6_managerSeesCandidatesWithoutSubjectOrThemselves() throws Exception {
        Team team = team();

        mvc.perform(get(candidates(team.subject().getId()))
                        .header("Authorization", "Bearer " + OrgFixture.tokenFor("jane-cand")))
                .andExpect(status().isOk())
                // Mei is assignable.
                .andExpect(content().string(containsString(team.colleague().getFullName())))
                // The subject is not their own peer, and their manager already writes the
                // manager review - both refused by the write, so both absent from the list.
                .andExpect(content().string(not(containsString(team.subject().getFullName()))))
                .andExpect(content().string(not(containsString(team.manager().getFullName()))));
    }

    @Test
    @DisplayName("P-0.7: a deactivated colleague is not offered as a peer")
    void P_0_7_deactivatedUserIsNotACandidate() throws Exception {
        Team team = team();

        mvc.perform(get(candidates(team.subject().getId()))
                        .header("Authorization", "Bearer " + OrgFixture.tokenFor("jane-cand")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(team.dormant().getFullName()))));
    }

    @Test
    @DisplayName("P-3.6: the subject cannot list their own candidates")
    void P_3_6_subjectCannotSeeTheirOwnCandidates() throws Exception {
        Team team = team();

        // The same refusal as listing the assigned peers: knowing the pool they were drawn
        // from is a step towards knowing who wrote what (P-3.3).
        mvc.perform(get(candidates(team.subject().getId()))
                        .header("Authorization", "Bearer " + OrgFixture.tokenFor("john-cand")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-1.2: a manager cannot list candidates for somebody who is not their report")
    void P_1_2_managerCannotSeeCandidatesForANonReport() throws Exception {
        Team team = team();
        Department sales = org.department("Sales-candidates");
        AppUser grace = org.userIn(sales, "grace-cand", Role.EMPLOYEE, Role.MANAGER);
        AppUser ben = org.userIn(sales, "ben-cand", Role.EMPLOYEE);
        ben.setManager(grace);
        org.flush();

        // Grace is a manager, but not John's. The directory is not open to managers as a
        // class; it is open to the person assigning peers for this one subject.
        mvc.perform(get(candidates(team.subject().getId()))
                        .header("Authorization", "Bearer " + OrgFixture.tokenFor("grace-cand")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-9.4: the Super Admin has no route to the roster through here")
    void P_9_4_superAdminCannotListPeerCandidates() throws Exception {
        Team team = team();
        org.user("devin-cand", Role.EMPLOYEE, Role.SUPER_ADMIN);
        org.flush();

        // They have their own user console. This endpoint is part of the review machinery,
        // and no review capability names SUPER_ADMIN.
        mvc.perform(get(candidates(team.subject().getId()))
                        .header("Authorization", "Bearer " + OrgFixture.tokenFor("devin-cand")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the name filter is applied in the query, so the count describes the candidates")
    void nameFilterIsAppliedInTheQuery() throws Exception {
        Team team = team();

        mvc.perform(get(candidates(team.subject().getId()))
                        .param("name", "mei-cand")
                        .header("Authorization", "Bearer " + OrgFixture.tokenFor("jane-cand")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].fullName").value(team.colleague().getFullName()));
    }

    @Test
    @DisplayName("the response carries a name and a department, and nothing else about anybody")
    void candidatesCarryNoReviewContent() throws Exception {
        Team team = team();

        mvc.perform(get(candidates(team.subject().getId()))
                        .param("name", "mei-cand")
                        .header("Authorization", "Bearer " + OrgFixture.tokenFor("jane-cand")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].departmentName")
                        .value(team.colleague().getDepartment().getName()))
                // No email, no reporting line, and nothing whatever about anybody's review.
                .andExpect(content().string(not(containsString("email"))))
                .andExpect(content().string(not(containsString("managerId"))))
                .andExpect(content().string(not(containsString("rating"))));
    }

    @Test
    @DisplayName("P-3.12: an HR user is offered as a peer only inside their own department")
    void P_3_12_hrIsACandidateOnlyWithinTheirOwnDepartment() throws Exception {
        Department engineering = org.department("Engineering-hr");
        Department people = org.department("People-hr");
        AppUser elena = org.userIn(engineering, "elena-hr", Role.EMPLOYEE, Role.MANAGER);
        AppUser aisha = org.userIn(engineering, "aisha-hr", Role.EMPLOYEE);
        AppUser samuel = org.userIn(people, "samuel-hr", Role.EMPLOYEE);
        AppUser kevin = org.userIn(people, "kevin-hr", Role.EMPLOYEE, Role.HR);
        aisha.setManager(elena);
        samuel.setManager(elena);
        org.flush();

        // Kevin is in People. Reviewing Aisha in Engineering would have him writing input to a
        // rating HR then calibrates, and reading his own peer review back while doing it.
        mvc.perform(get(candidates(aisha.getId()))
                        .header("Authorization", "Bearer " + OrgFixture.tokenFor("elena-hr")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(kevin.getFullName()))));

        // Inside HR they can and they should: Samuel sits in People alongside him, and a
        // colleague who works with somebody every day is exactly whom peer feedback is for.
        mvc.perform(get(candidates(samuel.getId()))
                        .header("Authorization", "Bearer " + OrgFixture.tokenFor("elena-hr")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(kevin.getFullName())));
    }

    @Test
    @DisplayName("P-3.13: Leadership are not peer reviewers, and are not offered as candidates")
    void P_3_13_leadershipDoNotWritePeerFeedback() throws Exception {
        Department engineering = org.department("Engineering-lead");
        AppUser richard = org.user("richard-lead", Role.EMPLOYEE, Role.LEADERSHIP);
        AppUser elena = org.userIn(engineering, "elena-lead", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john-lead", Role.EMPLOYEE);
        AppUser aisha = org.userIn(engineering, "aisha-lead", Role.EMPLOYEE);
        elena.setManager(richard);
        john.setManager(elena);
        org.flush();

        // Not offered. Leadership sit a tier or two above the people being reviewed, so a
        // remark from them is not what section 5 means by a colleague's view.
        mvc.perform(get(candidates(john.getId()))
                        .header("Authorization", "Bearer " + OrgFixture.tokenFor("elena-lead")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(richard.getFullName()))))
                // And the exclusion has not swallowed the ordinary colleague beside him.
                .andExpect(content().string(containsString(aisha.getFullName())));
    }
}
