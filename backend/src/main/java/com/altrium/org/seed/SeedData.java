package com.altrium.org.seed;

import com.altrium.org.AppUser;
import com.altrium.org.AppUserRepository;
import com.altrium.org.Department;
import com.altrium.org.HrDepartmentGrantRepository;
import com.altrium.org.HrGrantService;
import com.altrium.org.OrgService;
import com.altrium.org.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A repeatable sample organisation for manual testing: 31 people across 4 departments with
 * realistic reporting lines.
 *
 * <p>Off by default. Enable with {@code --altrium.seed.enabled=true}; see docs/local-setup.md.
 *
 * <p>Two things make this worth having rather than clicking users in by hand. Every tester
 * starts from identical conditions, so a denial that reproduces for one person reproduces for
 * everyone. And it is deep enough to expose the bugs a three-person test organisation hides:
 * skip-level reporting, a manager who is also a reviewee, an HR user inside the department
 * they oversee, and a deactivated user who must vanish from selection lists without vanishing
 * from history.
 *
 * <p>Built through {@link OrgService} rather than raw SQL, so the seed exercises the same
 * loop rejection (P-1.4) and role normalisation (P-0.1) the API does. If the seed runs
 * clean, those rules work.
 */
@Component
@ConditionalOnProperty(name = "altrium.seed.enabled", havingValue = "true")
public class SeedData implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedData.class);

    /**
     * The five people with real Asgardeo accounts, who can actually log in through the UI.
     * The rest carry synthetic subjects - creating 30 real accounts by hand would be console
     * work for no testing gain, and nothing below the login screen can tell the difference.
     */
    private static final String SUB_JOHN = "cdb99960-4f43-4892-81ac-92f46d8ee260";
    private static final String SUB_JANE = "626a2c84-f344-426b-a369-68645cabcf5c";
    private static final String SUB_KEVIN = "4ac3f0d9-3af4-4b0c-a4da-369b683f7ea1";
    private static final String SUB_RICHARD = "9eebabd1-064e-4e06-8e39-cf9be834f4b1";
    private static final String SUB_DEVIN = "f891d297-d2c7-4161-9650-ff00cdfc1cae";

    private final OrgService org;
    private final AppUserRepository users;
    private final HrGrantService hrGrants;
    private final HrDepartmentGrantRepository grantRepository;

    private final Map<String, Long> people = new HashMap<>();
    private final Map<String, Long> departmentIds = new HashMap<>();

    public SeedData(OrgService org,
                    AppUserRepository users,
                    HrGrantService hrGrants,
                    HrDepartmentGrantRepository grantRepository) {
        this.org = org;
        this.users = users;
        this.hrGrants = hrGrants;
        this.grantRepository = grantRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // The two halves are guarded separately so an existing organisation can still gain
        // its HR grants - the grants arrived a feature later than the people did.
        if (users.existsByAsgardeoSubject(SUB_RICHARD)) {
            log.info("Sample organisation already present; skipping people.");
            loadExistingIds();
        } else {
            seedPeople();
        }
        seedHrGrants();
    }

    private void seedPeople() {
        log.info("Seeding sample organisation...");

        Long engineering = department("Engineering");
        Long sales = department("Sales");
        Long finance = department("Finance");
        Long peopleOps = department("People Operations");

        // ---- Leadership -----------------------------------------------------------------
        // No department and no manager: they sit above the structure, are never reviewees,
        // and hold no PDP or PIP (P-7.2). The review chain terminates just below them.
        person("richard", SUB_RICHARD, "Richard Hale", null, null, Role.LEADERSHIP);
        person("priya", null, "Priya Raman", null, null, Role.LEADERSHIP);
        person("marcus", null, "Marcus Webb", null, null, Role.LEADERSHIP);

        // ---- Engineering ----------------------------------------------------------------
        person("elena", null, "Elena Vasquez", engineering, "priya", Role.MANAGER);
        person("jane", SUB_JANE, "Jane Okafor", engineering, "elena", Role.MANAGER);
        person("tom", null, "Tom Byrne", engineering, "elena", Role.MANAGER);

        person("john", SUB_JOHN, "John Alvarez", engineering, "jane");
        person("aisha", null, "Aisha Khan", engineering, "jane");
        person("diego", null, "Diego Santos", engineering, "jane");
        person("mei", null, "Mei Lin", engineering, "jane");

        person("omar", null, "Omar Haddad", engineering, "tom");
        person("sofia", null, "Sofia Rossi", engineering, "tom");
        person("liam", null, "Liam O'Connor", engineering, "tom");

        // A dedicated platform account (P-9.5), not a person who also administers. No
        // department and no manager, because both exist to place a reviewee - a department is
        // the unit of HR scoping and a manager is who reviews you, and neither applies to an
        // account that is never reviewed.
        //
        // This account grants the HR users their departments and configures the cycles. Giving
        // it a reviewing role on top would let one account arrange the scope and then act
        // inside it, which is the separation the grant mechanism exists to keep.
        person("devin", SUB_DEVIN, "Devin Marsh", null, null, Role.SUPER_ADMIN);

        // Deactivated (P-0.7): must disappear from peer selection, manager lists and new
        // cycles, while the row and any history survive.
        deactivated("tara", "Tara Fields", engineering, "tom");

        // ---- Sales ----------------------------------------------------------------------
        person("grace", null, "Grace Mwangi", sales, "richard", Role.MANAGER);
        person("ben", null, "Ben Carter", sales, "grace", Role.MANAGER);
        person("nadia", null, "Nadia Petrov", sales, "ben");
        person("carlos", null, "Carlos Mendes", sales, "ben");
        person("ruth", null, "Ruth Adeyemi", sales, "ben");
        person("yuki", null, "Yuki Tanaka", sales, "grace");
        person("adam", null, "Adam Novak", sales, "grace");

        // ---- Finance --------------------------------------------------------------------
        person("hassan", null, "Hassan Ali", finance, "marcus", Role.MANAGER);
        person("ingrid", null, "Ingrid Larsen", finance, "hassan");
        person("paolo", null, "Paolo Bianchi", finance, "hassan");
        person("fatima", null, "Fatima Zahra", finance, "hassan");
        person("wei", null, "Wei Chen", finance, "hassan");

        // ---- People Operations ----------------------------------------------------------
        // Kevin is the HR Head: the designated HR user who will hold the explicit grant over
        // his own department (P-2.4). His own review routes to Leadership (P-2.6), which is
        // why he reports to Richard rather than to another HR user.
        person("kevin", SUB_KEVIN, "Kevin Doyle", peopleOps, "richard", Role.HR, Role.MANAGER);

        // Ordinary HR, deliberately inside the department they would otherwise oversee.
        // Without an explicit grant they must be blocked here (P-2.3) - and blocked from
        // their own review no matter what grant they hold (P-2.2).
        person("hana", null, "Hana Iqbal", peopleOps, "kevin", Role.HR);
        person("rosa", null, "Rosa Delgado", peopleOps, "kevin", Role.HR);
        person("samuel", null, "Samuel Boateng", peopleOps, "kevin");

        log.info("Seeded {} people across 4 departments.", people.size());
    }

    /**
     * The HR grants that make the scoping rules demonstrable.
     *
     * <p>Guarded separately from the people, so an organisation seeded before this feature
     * existed still picks them up on the next run.
     */
    private void seedHrGrants() {
        if (grantRepository.count() > 0) {
            log.info("HR department grants already present; skipping.");
            return;
        }

        Long kevin = people.get("kevin");
        Long hana = people.get("hana");
        Long rosa = people.get("rosa");
        if (kevin == null || hana == null || rosa == null) {
            log.warn("HR users not found; skipping HR grants.");
            return;
        }

        Long engineering = departmentIds.get("Engineering");
        Long sales = departmentIds.get("Sales");
        Long finance = departmentIds.get("Finance");
        Long peopleOps = departmentIds.get("People Operations");

        // Kevin is the HR Head: an explicit grant over his own department (P-2.4), which is
        // how People Operations' reviews get overseen at all. It still leaves his own review
        // out of reach (P-2.2) - that block has no override.
        hrGrants.grant(kevin, peopleOps, true, null, g -> g.getId());

        // Hana oversees two departments, neither of them her own. The ordinary case.
        hrGrants.grant(hana, engineering, false, null, g -> g.getId());
        hrGrants.grant(hana, sales, false, null, g -> g.getId());

        // Rosa is granted Finance plus her own department, without the explicit flag. People
        // Operations is therefore excluded from her scope, which makes the difference between
        // a plain grant and an explicit one visible side by side with Kevin.
        hrGrants.grant(rosa, finance, false, null, g -> g.getId());
        hrGrants.grant(rosa, peopleOps, false, null, g -> g.getId());

        log.info("Seeded HR department grants.");
    }

    /** Re-reads ids for an organisation seeded on an earlier run. */
    private void loadExistingIds() {
        org.listDepartments().forEach(d -> departmentIds.put(d.getName(), d.getId()));
        for (String handle : List.of("kevin", "hana", "rosa")) {
            users.findByEmail(handle + "@altrium.test")
                    .ifPresent(user -> people.put(handle, user.getId()));
        }
    }

    private Long department(String name) {
        Department department = org.createDepartment(name);
        departmentIds.put(name, department.getId());
        return department.getId();
    }

    private void person(String handle, String subject, String fullName,
                        Long departmentId, String managerHandle, Role... roles) {
        Long managerId = managerHandle == null ? null : people.get(managerHandle);
        if (managerHandle != null && managerId == null) {
            throw new IllegalStateException(
                    "Seed order problem: '" + managerHandle + "' must be created before '" + handle + "'");
        }

        Set<Role> assigned = roles.length == 0 ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(Set.of(roles));

        AppUser created = org.createUser(
                subject == null ? "seed-" + handle : subject,
                handle + "@altrium.test",
                fullName,
                departmentId,
                managerId,
                assigned);

        people.put(handle, created.getId());
    }

    private void deactivated(String handle, String fullName, Long departmentId, String managerHandle) {
        person(handle, null, fullName, departmentId, managerHandle);
        org.deactivate(people.get(handle));
    }
}
