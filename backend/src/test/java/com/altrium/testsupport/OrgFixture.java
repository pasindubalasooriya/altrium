package com.altrium.testsupport;

import com.altrium.org.AppUser;
import com.altrium.org.Department;
import com.altrium.org.Role;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds just enough of an organisation for a test to have something to be denied against.
 *
 * <p>Separate from the Sprint-1 seed data, which is a fixed 30-person organisation for manual
 * testing. These fixtures are per-test and disposable.
 */
@Component
public class OrgFixture {

    private static final AtomicLong UNIQUE = new AtomicLong();

    private final EntityManager em;

    public OrgFixture(EntityManager em) {
        this.em = em;
    }

    public Department department(String name) {
        Department department = new Department(name + "-" + UNIQUE.incrementAndGet());
        em.persist(department);
        return department;
    }

    /** An active user with the given roles, in no department and reporting to nobody. */
    public AppUser user(String handle, Role... roles) {
        long n = UNIQUE.incrementAndGet();
        AppUser user = new AppUser(
                subjectOf(handle),
                handle + "-" + n + "@altrium.test",
                handle);
        Set<Role> assigned = roles.length == 0
                ? EnumSet.of(Role.EMPLOYEE)
                : EnumSet.copyOf(Set.of(roles));
        user.setRoles(assigned);
        em.persist(user);
        return user;
    }

    public AppUser userIn(Department department, String handle, Role... roles) {
        AppUser user = user(handle, roles);
        user.setDepartment(department);
        return user;
    }

    public AppUser deactivated(String handle, Role... roles) {
        AppUser user = user(handle, roles);
        user.setActive(false);
        return user;
    }

    public void flush() {
        em.flush();
        em.clear();
    }

    /** The Asgardeo {@code sub} the stub decoder will mint for this handle. */
    public static String subjectOf(String handle) {
        return "asgardeo-sub-" + handle;
    }

    /** A bearer token for this handle, carrying no role claims. */
    public static String tokenFor(String handle) {
        return subjectOf(handle);
    }

    /**
     * A bearer token whose Asgardeo {@code roles} claim asserts the given roles. Used to
     * prove the claim grants nothing on its own.
     */
    public static String tokenClaiming(String handle, Role... roles) {
        StringBuilder token = new StringBuilder(subjectOf(handle)).append(StubJwtDecoderConfig.ROLE_DELIMITER);
        for (int i = 0; i < roles.length; i++) {
            if (i > 0) {
                token.append(StubJwtDecoderConfig.ROLE_SEPARATOR);
            }
            // Asgardeo prefixes its roles; the converter must strip the prefix before matching.
            token.append("Application/").append(roles[i].name());
        }
        return token.toString();
    }
}
