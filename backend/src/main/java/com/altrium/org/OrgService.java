package com.altrium.org;

import com.altrium.config.ConflictApiException;
import com.altrium.config.NotFoundApiException;
import com.altrium.config.ValidationApiException;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Organisational structure: people, departments and reporting lines (feature 2).
 *
 * <p>Every method here is Super Admin territory (P-9.1, P-9.2). The role gate lives on the
 * controller; what lives here are the structural invariants that must hold no matter who is
 * calling, because the authorization layer is built on top of them and quietly breaks if
 * they do not.
 */
@Service
@Transactional
public class OrgService {

    /**
     * A safety stop while walking a reporting chain. The chain should be a handful of levels
     * deep in a 100-person company; if walking it exceeds this, the data is already corrupt
     * and looping forever would take the server down rather than report the problem.
     */
    private static final int MAX_CHAIN_DEPTH = 100;

    private final AppUserRepository users;
    private final DepartmentRepository departments;

    public OrgService(AppUserRepository users, DepartmentRepository departments) {
        this.users = users;
        this.departments = departments;
    }

    // ---------------------------------------------------------------- departments

    public Department createDepartment(String name) {
        if (departments.existsByName(name)) {
            throw new ConflictApiException("A department named '" + name + "' already exists");
        }
        return departments.save(new Department(name));
    }

    @Transactional(readOnly = true)
    public List<Department> listDepartments() {
        return departments.findAll();
    }

    public Department renameDepartment(Long id, String name) {
        Department department = departments.findById(id)
                .orElseThrow(() -> new NotFoundApiException("No such department"));
        departments.findByName(name)
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new ConflictApiException("A department named '" + name + "' already exists");
                });
        department.setName(name);
        return department;
    }

    // ---------------------------------------------------------------- users

    public AppUser createUser(String asgardeoSubject,
                              String email,
                              String fullName,
                              Long departmentId,
                              Long managerId,
                              Set<Role> roles) {

        if (users.existsByAsgardeoSubject(asgardeoSubject)) {
            throw new ConflictApiException("That Asgardeo subject is already linked to a user");
        }
        if (users.existsByEmail(email)) {
            throw new ConflictApiException("A user with that email already exists");
        }

        AppUser user = new AppUser(asgardeoSubject, email, fullName);
        user.setDepartment(departmentId == null ? null : requireDepartment(departmentId));
        user.setRoles(normaliseRoles(roles));

        // Persist before assigning a manager: the loop check walks the stored chain, and an
        // unsaved user has no identity to compare against.
        AppUser saved = users.save(user);

        if (managerId != null) {
            assignManager(saved, requireUser(managerId));
        }
        return saved;
    }

    public AppUser updateUser(Long id, String email, String fullName, Long departmentId, Set<Role> roles) {
        AppUser user = requireUser(id);

        users.findByEmail(email)
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new ConflictApiException("A user with that email already exists");
                });

        user.setEmail(email);
        user.setFullName(fullName);
        user.setDepartment(departmentId == null ? null : requireDepartment(departmentId));
        user.setRoles(normaliseRoles(roles));
        return user;
    }

    /**
     * Sets or clears a reporting line.
     *
     * <p>{@code managerId} of null detaches the user, which is how the top of the chain is
     * expressed — Leadership report to nobody.
     */
    public AppUser setManager(Long userId, Long managerId) {
        AppUser user = requireUser(userId);
        if (managerId == null) {
            user.setManager(null);
            return user;
        }
        assignManager(user, requireUser(managerId));
        return user;
    }

    /**
     * Soft delete (P-0.7, constraint 8). The row is never removed: deleting it would destroy
     * the history and carry-over pillar and orphan every artifact the person authored.
     *
     * <p>Anyone reporting to them is left dangling deliberately rather than silently
     * re-pointed. Guessing a new manager would rewrite the org chart as a side effect of a
     * deactivation, and the whole manager side of the authorization model reads from that
     * chart. The Super Admin is told how many people need reassigning.
     */
    public DeactivationResult deactivate(Long userId) {
        AppUser user = requireUser(userId);
        user.setActive(false);
        return new DeactivationResult(user, users.countByManagerId(userId));
    }

    public AppUser reactivate(Long userId) {
        AppUser user = requireUser(userId);
        user.setActive(true);
        return user;
    }

    /**
     * A page of users, filtered in SQL.
     *
     * <p>Never loads the organisation into memory: nothing may be hardcoded to
     * organisational size, and a console that works at 100 people by fetching everything
     * stops working the moment it grows.
     */
    @Transactional(readOnly = true)
    public Page<AppUser> listUsers(String search, Long departmentId, Boolean active, Pageable pageable) {
        return users.findAll(userFilter(search, departmentId, active), pageable);
    }

    @Transactional(readOnly = true)
    public AppUser get(Long id) {
        return requireUser(id);
    }

    @Transactional(readOnly = true)
    public List<AppUser> directReports(Long managerId) {
        return users.findByManagerId(managerId);
    }

    // ---------------------------------------------------------------- internals

    private Specification<AppUser> userFilter(String search, Long departmentId, Boolean active) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("fullName")), pattern),
                        cb.like(cb.lower(root.get("email")), pattern)));
            }
            if (departmentId != null) {
                predicates.add(cb.equal(root.get("department").get("id"), departmentId));
            }
            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Assigns a reporting line, rejecting anything that would create a cycle (P-1.4).
     *
     * <p>This is not a tidiness check. {@code isManagerOf} and every direct-reports query the
     * authorization layer runs assume the reporting graph is acyclic; a loop makes chain
     * traversal non-terminating and can hang the server on an ordinary request. The check
     * walks up from the proposed manager and refuses if it arrives back at the user.
     */
    private void assignManager(AppUser user, AppUser manager) {
        if (manager.getId().equals(user.getId())) {
            throw new ValidationApiException("A user cannot report to themselves");
        }
        if (!manager.isActive()) {
            throw new ValidationApiException("A deactivated user cannot be assigned as a manager");
        }

        Set<Long> visited = new HashSet<>();
        AppUser ancestor = manager;
        int depth = 0;

        while (ancestor != null) {
            if (ancestor.getId().equals(user.getId())) {
                throw new ValidationApiException(
                        "That assignment would create a reporting loop: " + user.getFullName()
                                + " already appears above " + manager.getFullName());
            }
            if (!visited.add(ancestor.getId())) {
                // Pre-existing corruption. Report it rather than spin.
                throw new ValidationApiException(
                        "The existing reporting chain already contains a loop; fix it before assigning");
            }
            if (++depth > MAX_CHAIN_DEPTH) {
                throw new ValidationApiException("Reporting chain is implausibly deep; refusing to assign");
            }
            ancestor = ancestor.getManager();
        }

        user.setManager(manager);
    }

    /** Every user is an Employee in addition to whatever else they hold (P-0.1). */
    private Set<Role> normaliseRoles(Set<Role> requested) {
        Set<Role> roles = EnumSet.of(Role.EMPLOYEE);
        if (requested != null) {
            roles.addAll(requested);
        }
        return roles;
    }

    private AppUser requireUser(Long id) {
        return users.findById(id).orElseThrow(() -> new NotFoundApiException("No such user"));
    }

    private Department requireDepartment(Long id) {
        return departments.findById(id).orElseThrow(() -> new NotFoundApiException("No such department"));
    }

    /**
     * @param danglingReports how many people still report to the deactivated user and need
     *                        reassigning by hand
     */
    public record DeactivationResult(AppUser user, long danglingReports) {
    }
}
