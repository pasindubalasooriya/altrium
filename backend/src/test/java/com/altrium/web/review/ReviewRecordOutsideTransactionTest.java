package com.altrium.web.review;

import com.altrium.org.AppUser;
import com.altrium.org.AppUserRepository;
import com.altrium.org.Department;
import com.altrium.org.DepartmentRepository;
import com.altrium.org.OrgService;
import com.altrium.org.Role;
import com.altrium.review.CycleParticipant;
import com.altrium.review.CycleParticipantRepository;
import com.altrium.review.CycleStatus;
import com.altrium.review.ReviewCycle;
import com.altrium.review.ReviewCycleRepository;
import com.altrium.testsupport.OrgFixture;
import com.altrium.testsupport.StubJwtDecoderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reading one review record with <strong>no test transaction</strong>.
 *
 * <p>The second class of its kind, and it exists for the same reason as
 * {@code UserListingOutsideTransactionTest}: every other integration test is
 * {@code @Transactional}, which holds a Hibernate session open for the whole test. Mapping an
 * entity to a DTO after the service transaction has closed therefore works in those tests and
 * fails in a real request.
 *
 * <p>The bug this reproduces reached the browser. {@code GET /api/reviews/{id}} returned 500,
 * because the controller converted the record after the service returned and
 * {@code ReviewSummary.of} reads {@code participant.getCycle().label()} - a lazy proxy on a
 * closed session. Every one of the review tests passed throughout.
 *
 * <p>The fix is the pattern the list endpoint next to it already used: hand the mapper to the
 * service so conversion happens inside the transaction. This test is what keeps it that way,
 * because nothing else in the suite can tell the difference.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
class ReviewRecordOutsideTransactionTest {

    private static final String REVIEWS = "/api/reviews";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrgService org;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private DepartmentRepository departments;

    @Autowired
    private ReviewCycleRepository cycles;

    @Autowired
    private CycleParticipantRepository participants;

    private Long departmentId;
    private Long managerId;
    private Long subjectId;
    private Long cycleId;
    private Long participantId;

    @BeforeEach
    void setUp() {
        Department department = org.createDepartment("Lazy-Review-Dept-" + System.nanoTime());
        departmentId = department.getId();

        AppUser manager = org.createUser(
                OrgFixture.subjectOf("lazy-review-mgr"), "lazy-review-mgr@altrium.test",
                "Lazy Review Manager", departmentId, null, EnumSet.of(Role.MANAGER));
        managerId = manager.getId();

        AppUser subject = org.createUser(
                OrgFixture.subjectOf("lazy-review-sub"), "lazy-review-sub@altrium.test",
                "Lazy Review Subject", departmentId, managerId, EnumSet.noneOf(Role.class));
        subjectId = subject.getId();

        // A period far enough out that it cannot collide with the fixtures' own cycles.
        ReviewCycle cycle = new ReviewCycle(
                3000 + (int) (System.nanoTime() % 900), 1,
                LocalDate.of(2030, 1, 1), LocalDate.of(2030, 4, 30));
        cycle.setStatus(CycleStatus.OPEN);
        cycle.setOpenedAt(Instant.now());
        cycleId = cycles.save(cycle).getId();

        participantId = participants
                .save(new CycleParticipant(cycle, subject, department))
                .getId();
    }

    @AfterEach
    void tearDown() {
        // Nothing rolls back here. Null-guarded, because a setUp that fails part way through
        // still runs this, and throwing would leave the rest of the rows behind.
        if (participantId != null) {
            participants.findById(participantId).ifPresent(participants::delete);
        }
        if (cycleId != null) {
            cycles.findById(cycleId).ifPresent(cycles::delete);
        }
        if (subjectId != null) {
            users.findById(subjectId).ifPresent(users::delete);
        }
        if (managerId != null) {
            users.findById(managerId).ifPresent(users::delete);
        }
        if (departmentId != null) {
            departments.findById(departmentId).ifPresent(departments::delete);
        }
    }

    @Test
    @DisplayName("the subject reads their own record without a LazyInitializationException")
    void subjectReadsOwnRecord() throws Exception {
        mvc.perform(get(REVIEWS + "/" + subjectId)
                        .param("cycleId", cycleId.toString())
                        .header("Authorization", "Bearer " + OrgFixture.tokenFor("lazy-review-sub")))
                .andExpect(status().isOk())
                // The cycle label is the field that blew up: it comes from a lazy proxy on the
                // participant, so it is the one worth asserting rather than merely a 200.
                .andExpect(jsonPath("$.summary.cycleLabel").isNotEmpty())
                .andExpect(jsonPath("$.summary.subjectId").value(subjectId))
                .andExpect(jsonPath("$.summary.departmentName").isNotEmpty());
    }

    @Test
    @DisplayName("the manager reads a report's record, resolving the same lazy associations")
    void managerReadsReportRecord() throws Exception {
        mvc.perform(get(REVIEWS + "/" + subjectId)
                        .param("cycleId", cycleId.toString())
                        .header("Authorization", "Bearer " + OrgFixture.tokenFor("lazy-review-mgr")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.cycleLabel").isNotEmpty())
                .andExpect(jsonPath("$.summary.managerId").value(managerId));
    }

    @Test
    @DisplayName("the review list resolves them too, outside a transaction")
    void listResolvesTheSameAssociations() throws Exception {
        mvc.perform(get(REVIEWS)
                        .param("cycleId", cycleId.toString())
                        .header("Authorization", "Bearer " + OrgFixture.tokenFor("lazy-review-mgr")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].cycleLabel").isNotEmpty())
                .andExpect(jsonPath("$.content[0].departmentName").isNotEmpty());
    }

    @Test
    @DisplayName("a denial is still a denial here, not a 500 dressed as one")
    void denialIsStillFourOhThree() throws Exception {
        // The whole point of the fix is that mapping moved inside the transaction. If that had
        // been done by widening the transaction around the controller instead, an unrelated
        // caller would start seeing 500s where they had been getting 403s.
        mvc.perform(get(REVIEWS + "/" + managerId)
                        .param("cycleId", cycleId.toString())
                        .header("Authorization", "Bearer " + OrgFixture.tokenFor("lazy-review-sub")))
                .andExpect(status().isForbidden());
    }
}
