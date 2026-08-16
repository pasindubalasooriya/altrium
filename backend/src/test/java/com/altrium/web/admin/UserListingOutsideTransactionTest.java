package com.altrium.web.admin;

import com.altrium.org.AppUser;
import com.altrium.org.AppUserRepository;
import com.altrium.org.Department;
import com.altrium.org.DepartmentRepository;
import com.altrium.org.OrgService;
import com.altrium.org.Role;
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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Listing users with <strong>no test transaction</strong>.
 *
 * <p>This class exists because of a bug the rest of the suite could not see. Every other
 * integration test is {@code @Transactional}, which keeps a Hibernate session open for the
 * whole test — so mapping an entity to a DTO after the service transaction had closed
 * worked there, and failed with {@code LazyInitializationException} the moment a real
 * request did it. The tests passed; the endpoint returned 500.
 *
 * <p>Omitting {@code @Transactional} reproduces production exactly: the request runs in its
 * own transaction and the session closes when the service returns. The cost is manual
 * cleanup, since nothing rolls back.
 *
 * <p>Worth repeating for any endpoint that returns data assembled from lazy associations.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
class UserListingOutsideTransactionTest {

    private static final String USERS = "/api/admin/users";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrgService org;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private DepartmentRepository departments;

    private final List<Long> createdUsers = new ArrayList<>();
    private Long departmentId;
    private Long adminId;
    private Long reportId;

    @BeforeEach
    void setUp() {
        Department department = org.createDepartment("Lazy-Test-Dept-" + System.nanoTime());
        departmentId = department.getId();

        AppUser admin = org.createUser(
                OrgFixture.subjectOf("lazy-admin"), "lazy-admin@altrium.test", "Lazy Admin",
                departmentId, null, EnumSet.of(Role.SUPER_ADMIN, Role.MANAGER));
        adminId = admin.getId();
        createdUsers.add(adminId);

        // A user with both a department and a manager, so the row exercises both lazy
        // to-one associations the response includes.
        AppUser report = org.createUser(
                OrgFixture.subjectOf("lazy-report"), "lazy-report@altrium.test", "Lazy Report",
                departmentId, adminId, EnumSet.noneOf(Role.class));
        reportId = report.getId();
        createdUsers.add(reportId);
    }

    @AfterEach
    void tearDown() {
        // Nothing rolls back here. Delete reports before managers, or the self-referencing
        // foreign key rejects the delete.
        users.findById(reportId).ifPresent(users::delete);
        users.findById(adminId).ifPresent(users::delete);
        departments.findById(departmentId).ifPresent(departments::delete);
        createdUsers.clear();
    }

    private String bearer() {
        return "Bearer " + OrgFixture.tokenFor("lazy-admin");
    }

    @Test
    @DisplayName("listing users resolves department, manager and roles outside a transaction")
    void listingResolvesLazyAssociations() throws Exception {
        mvc.perform(get(USERS)
                        .param("search", "Lazy Report")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].fullName").value("Lazy Report"))
                // The three fields that came from lazy associations and produced the 500.
                .andExpect(jsonPath("$.content[0].departmentName").isNotEmpty())
                .andExpect(jsonPath("$.content[0].managerName").value("Lazy Admin"))
                .andExpect(jsonPath("$.content[0].roles[0]").value("EMPLOYEE"));
    }

    @Test
    @DisplayName("fetching a single user resolves the same associations")
    void singleUserResolvesLazyAssociations() throws Exception {
        mvc.perform(get(USERS + "/" + reportId).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managerName").value("Lazy Admin"))
                .andExpect(jsonPath("$.departmentName").isNotEmpty());
    }

    @Test
    @DisplayName("P-1.1: direct reports resolve outside a transaction too")
    void P_1_1_directReportsResolveLazyAssociations() throws Exception {
        mvc.perform(get(USERS + "/" + adminId + "/direct-reports").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fullName").value("Lazy Report"))
                .andExpect(jsonPath("$[0].managerName").value("Lazy Admin"));
    }
}
