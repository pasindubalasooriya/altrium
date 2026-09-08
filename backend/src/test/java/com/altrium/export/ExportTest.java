package com.altrium.export;

import com.altrium.org.AppUser;
import com.altrium.org.Department;
import com.altrium.org.HrGrantService;
import com.altrium.org.Role;
import com.altrium.review.Rating;
import com.altrium.review.ReviewCycle;
import com.altrium.testsupport.OrgFixture;
import com.altrium.testsupport.ReviewFixture;
import com.altrium.testsupport.StubJwtDecoderConfig;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Feature 19 - PDF and XLSX exports, over HTTP.
 *
 * <p>An export is the one place where data stops being governed by this system, so the tests
 * that matter are about who may take a file and what is inside it. The bytes are opened and
 * read rather than merely counted: an export that returns 200 and a workbook containing another
 * department's rows would pass any test that only checked the status.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StubJwtDecoderConfig.class)
@Transactional
class ExportTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrgFixture org;

    @Autowired
    private ReviewFixture reviews;

    @Autowired
    private HrGrantService grants;

    @Autowired
    private JdbcTemplate jdbc;

    private String bearer(String handle) {
        return "Bearer " + OrgFixture.tokenFor(handle);
    }

    private String url(ReviewCycle cycle, String format) {
        return "/api/exports/cycles/" + cycle.getId() + "?format=" + format;
    }

    // ------------------------------------------------------------------ who may export

    @Test
    @DisplayName("P-8.1: a manager cannot export, though they may read every row of it")
    void P_8_1_managerCannotExport() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.releasedRating(cycle, john, jane, Rating.MEETS_EXPECTATIONS);
        reviews.flush();

        // Jane's dashboard shows all of this. The refusal is about the file, not the figures.
        mvc.perform(get(url(cycle, "xlsx")).header("Authorization", bearer("jane")))
                .andExpect(status().isForbidden());
        mvc.perform(get(url(cycle, "pdf")).header("Authorization", bearer("jane")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-8.1: an employee cannot export")
    void P_8_1_employeeCannotExport() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.flush();

        mvc.perform(get(url(cycle, "xlsx")).header("Authorization", bearer("john")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-9.4: the Super Admin cannot export, having no review content to export")
    void P_9_4_superAdminCannotExport() throws Exception {
        org.user("devin", Role.EMPLOYEE, Role.SUPER_ADMIN);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.flush();

        mvc.perform(get(url(cycle, "xlsx")).header("Authorization", bearer("devin")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("P-2.1: an HR user with no grants holds no export")
    void P_2_1_ungrantedHrCannotExport() throws Exception {
        org.userIn(org.department("People Operations"), "rosa", Role.EMPLOYEE, Role.HR);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.flush();

        // Nothing has been granted, so there is no scope for a file to cover. Refused rather
        // than served empty: an export is an action, and they hold no grounds for it.
        mvc.perform(get(url(cycle, "xlsx")).header("Authorization", bearer("rosa")))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ what is in the file

    @Test
    @DisplayName("P-8.2: an HR workbook carries granted departments and no others")
    void P_8_2_hrWorkbookIsScopedToGrants() throws Exception {
        Department engineering = org.department("Engineering");
        Department sales = org.department("Sales");
        AppUser hana = org.userIn(org.department("People Operations"), "hana", Role.EMPLOYEE, Role.HR);
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser grace = org.userIn(sales, "grace", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser nadia = org.userIn(sales, "nadia", Role.EMPLOYEE);
        john.setManager(jane);
        nadia.setManager(grace);
        org.flush();

        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.participant(cycle, nadia);
        reviews.releasedRating(cycle, john, jane, Rating.MEETS_EXPECTATIONS);
        reviews.releasedRating(cycle, nadia, grace, Rating.NEEDS_IMPROVEMENT);
        reviews.flush();

        byte[] file = mvc.perform(get(url(cycle, "xlsx")).header("Authorization", bearer("hana")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        List<String> progress = cells(file, "Progress");

        // Department names carry a unique suffix from the fixture, so they are compared against
        // the objects rather than typed out.
        assertThat(progress).contains(engineering.getName());
        // The department Hana was never granted, whose one participant has a rating in this
        // same cycle. A status check alone would not have caught this.
        assertThat(progress).doesNotContain(sales.getName());
        assertThat(progress).contains("Departments: " + engineering.getName());

        // And the distribution is scoped with it: Nadia's NEEDS_IMPROVEMENT is not in the file.
        List<String> ratings = cells(file, "Ratings");
        assertThat(ratings).contains("Needs improvement");
        int needsImprovement = ratings.indexOf("Needs improvement");
        assertThat(ratings.get(needsImprovement + 1)).isEqualTo("0");
    }

    @Test
    @DisplayName("P-7.5: a Leadership export carries totals and no per-department ratings")
    void P_7_5_leadershipExportIsTotalsOnly() throws Exception {
        Department engineering = org.department("Engineering");
        Department sales = org.department("Sales");
        org.user("richard", Role.EMPLOYEE, Role.LEADERSHIP);
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser grace = org.userIn(sales, "grace", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        AppUser nadia = org.userIn(sales, "nadia", Role.EMPLOYEE);
        john.setManager(jane);
        nadia.setManager(grace);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.participant(cycle, nadia);
        reviews.releasedRating(cycle, john, jane, Rating.MEETS_EXPECTATIONS);
        reviews.releasedRating(cycle, nadia, grace, Rating.NEEDS_IMPROVEMENT);
        reviews.flush();

        byte[] file = mvc.perform(get(url(cycle, "xlsx")).header("Authorization", bearer("richard")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        // Both departments appear in the progress table, which is allowed: those counts say a
        // rating exists, not what it is.
        List<String> progress = cells(file, "Progress");
        assertThat(progress).contains(engineering.getName(), sales.getName(),
                "The whole organisation");

        // The ratings sheet has exactly two columns, Rating and People. A department column is
        // what P-7.5 forbids, and a spreadsheet is where one would be added without thinking.
        List<String> ratings = cells(file, "Ratings");
        assertThat(ratings).containsSequence("Rating", "People");
        assertThat(ratings).doesNotContain(engineering.getName(), sales.getName(), "Department");
    }

    @Test
    @DisplayName("A PDF is produced, and is a PDF")
    void pdfIsRendered() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser hana = org.userIn(org.department("People Operations"), "hana", Role.EMPLOYEE, Role.HR);
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();

        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.releasedRating(cycle, john, jane, Rating.MEETS_EXPECTATIONS);
        reviews.flush();

        byte[] file = mvc.perform(get(url(cycle, "pdf")).header("Authorization", bearer("hana")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();

        // The template is parsed as XML by the renderer, so a malformed tag throws rather than
        // laying out badly. Checking the magic bytes proves the whole path ran.
        assertThat(new String(file, 0, 5)).isEqualTo("%PDF-");
        assertThat(file.length).isGreaterThan(1000);
    }

    @Test
    @DisplayName("An unknown format is a 400, not a refusal")
    void unknownFormatIsRejected() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser hana = org.userIn(org.department("People Operations"), "hana", Role.EMPLOYEE, Role.HR);
        org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        org.flush();

        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        ReviewCycle cycle = reviews.openCycle();
        reviews.flush();

        mvc.perform(get(url(cycle, "csv")).header("Authorization", bearer("hana")))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ the log

    @Test
    @DisplayName("Every export is recorded, with the ground it was granted on")
    void exportsAreLogged() throws Exception {
        Department engineering = org.department("Engineering");
        AppUser hana = org.userIn(org.department("People Operations"), "hana", Role.EMPLOYEE, Role.HR);
        AppUser jane = org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        AppUser john = org.userIn(engineering, "john", Role.EMPLOYEE);
        john.setManager(jane);
        org.flush();

        grants.grant(hana.getId(), engineering.getId(), false, null, g -> g.getId());

        ReviewCycle cycle = reviews.openCycle();
        reviews.participant(cycle, john);
        reviews.flush();

        mvc.perform(get(url(cycle, "xlsx")).header("Authorization", bearer("hana")))
                .andExpect(status().isOk());

        List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                "SELECT exported_by, format, grounds, scope_note, row_count"
                        + " FROM export_log WHERE cycle_id = ?", cycle.getId());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("exported_by")).isEqualTo(hana.getId());
        assertThat(rows.get(0).get("format")).isEqualTo("XLSX");
        // The decision, not the caller's roles. Hana holds HR; had she also held Leadership the
        // file would have been the other one, and the log has to say which she got.
        assertThat(rows.get(0).get("grounds")).isEqualTo("HR_IN_SCOPE");
        assertThat(rows.get(0).get("scope_note"))
                .isEqualTo("Departments: " + engineering.getName());
    }

    @Test
    @DisplayName("A refused export leaves no log row")
    void refusedExportIsNotLogged() throws Exception {
        Department engineering = org.department("Engineering");
        org.userIn(engineering, "jane", Role.EMPLOYEE, Role.MANAGER);
        org.flush();

        ReviewCycle cycle = reviews.openCycle();
        reviews.flush();

        mvc.perform(get(url(cycle, "xlsx")).header("Authorization", bearer("jane")))
                .andExpect(status().isForbidden());

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM export_log WHERE cycle_id = ?", Integer.class, cycle.getId());
        assertThat(count).isZero();
    }

    // ------------------------------------------------------------------ helpers

    /** Every cell of a sheet as text, so a test can ask what is and is not in the file. */
    private static List<String> cells(byte[] xlsx, String sheetName) throws Exception {
        List<String> values = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = workbook.getSheet(sheetName);
            assertThat(sheet).as("sheet " + sheetName).isNotNull();
            sheet.forEach(row -> row.forEach(cell -> values.add(switch (cell.getCellType()) {
                case STRING -> cell.getStringCellValue();
                case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
                default -> "";
            })));
        }
        return values;
    }
}
