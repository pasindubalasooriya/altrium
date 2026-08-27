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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Listing users with <strong>no test transaction</strong>.
 *
 * <p>This class exists because of a bug the rest of the suite could not see. Every other
 * integration test is {@code @Transactional}, which keeps a Hibernate session open for the
 * whole test - so mapping an entity to a DTO after the service transaction had closed
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
                // SUPER_ADMIN alone: P-9.5 refuses it alongside a reviewing role, and this test
                // only ever needed the console access.
                departmentId, null, EnumSet.of(Role.SUPER_ADMIN));
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
        //
        // Null-guarded because a setUp that fails part way through still runs this, and an
        // exception here would leave whatever it had already created behind - which is exactly
        // how a stray department once outlived the run and broke an unrelated seed assertion
        // several classes later.
        if (reportId != null) {
            users.findById(reportId).ifPresent(users::delete);
        }
        if (adminId != null) {
            users.findById(adminId).ifPresent(users::delete);
        }
        if (departmentId != null) {
            departments.findById(departmentId).ifPresent(departments::delete);
        }
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

    // ---------------------------------------------------------------- the writes
    //
    // The reads above were covered; the writes were not, and they are the riskier half. Each
    // one loads a user, changes a field and returns it, without ever touching the roles
    // collection inside the transaction - so the collection is still uninitialised when
    // UserView reads it afterwards. Every one of these returned 500 before the mapper was
    // pushed into the service.

    @Test
    @DisplayName("deactivating returns a view that resolves outside the transaction")
    void deactivateResolvesLazyAssociations() throws Exception {
        mvc.perform(put(USERS + "/" + reportId + "/deactivate").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.active").value(false))
                .andExpect(jsonPath("$.user.roles[0]").value("EMPLOYEE"))
                .andExpect(jsonPath("$.user.departmentName").isNotEmpty());
    }

    @Test
    @DisplayName("reactivating does too")
    void reactivateResolvesLazyAssociations() throws Exception {
        mvc.perform(put(USERS + "/" + reportId + "/deactivate").header("Authorization", bearer()))
                .andExpect(status().isOk());

        mvc.perform(put(USERS + "/" + reportId + "/reactivate").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.roles[0]").value("EMPLOYEE"));
    }

    @Test
    @DisplayName("reassigning a reporting line does too")
    void setManagerResolvesLazyAssociations() throws Exception {
        mvc.perform(put(USERS + "/" + reportId + "/manager")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"managerId\":null}")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managerName").doesNotExist())
                .andExpect(jsonPath("$.roles[0]").value("EMPLOYEE"))
                .andExpect(jsonPath("$.departmentName").isNotEmpty());
    }
}
