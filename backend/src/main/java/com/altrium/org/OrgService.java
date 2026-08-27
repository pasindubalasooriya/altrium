package com.altrium.org;

import com.altrium.config.ConflictApiException;
import com.altrium.config.NotFoundApiException;
import com.altrium.config.ValidationApiException;
import com.altrium.review.CohortMember;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
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
import java.util.function.Function;

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
     * expressed - Leadership report to nobody.
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
     * A page of users, filtered in SQL and mapped to a response shape.
     *
     * <p>Never loads the organisation into memory: nothing may be hardcoded to
     * organisational size, and a console that works at 100 people by fetching everything
     * stops working the moment it grows.
     *
     * <p>The caller passes the mapper rather than receiving entities, so the conversion runs
     * <em>inside</em> this transaction. Returning entities instead would hand the controller
     * lazy proxies belonging to a closed session - which fails only outside a transaction,
     * meaning tests that wrap themselves in one would never see it.
     */
    @Transactional(readOnly = true)
    public <T> Page<T> listUsers(String search, Long departmentId, Boolean active, Role role,
                                 Boolean inCohort, Pageable pageable, Function<AppUser, T> mapper) {
        return users.findAll(userFilter(search, departmentId, active, role, inCohort), pageable)
                .map(mapper);
    }

    @Transactional(readOnly = true)
    public <T> T get(Long id, Function<AppUser, T> mapper) {
        return mapper.apply(requireUser(id));
    }

    /** Direct reports only (P-1.1) - never transitive. */
    @Transactional(readOnly = true)
    public <T> List<T> directReports(Long managerId, Function<AppUser, T> mapper) {
        return users.findByManagerId(managerId).stream().map(mapper).toList();
    }

    /** Entity-returning variant for callers already inside a transaction, such as tests. */
    @Transactional(readOnly = true)
    public List<AppUser> directReports(Long managerId) {
        return users.findByManagerId(managerId);
    }

    // ---------------------------------------------------------------- internals

    private Specification<AppUser> userFilter(String search, Long departmentId, Boolean active,
                                              Role role, Boolean inCohort) {
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
            if (role != null) {
                // In the query rather than applied to a fetched page. Filtering afterwards
                // would page over everybody and then discard, so "the HR users" would mean
                // "the HR users on this page of everybody" - which stops being the same thing
                // the moment the organisation outgrows one page.
                predicates.add(cb.isMember(role, root.get("roles")));
            }
            if (inCohort != null) {
                // "Who is not in a cohort yet" - the roster the cohort screen offers, which
                // would otherwise be assembled by fetching everybody and dropping the ones
                // already placed. That is the same mistake as the role filter: it would silently
                // mean "the unassigned people on this page", and would tend to report an empty
                // list rather than an error once the organisation outgrows one page.
                //
                // A correlated EXISTS rather than a join, because a join would need distinct and
                // would make the total count wrong the moment the membership table gained a
                // second row per person. It cannot today - the unique key on user_id forbids it -
                // but a count that is only correct because of a constraint elsewhere is a count
                // waiting to be wrong.
                Subquery<Long> membership = query.subquery(Long.class);
                Root<CohortMember> member = membership.from(CohortMember.class);
                membership.select(cb.literal(1L))
                        .where(cb.equal(member.get("user").get("id"), root.get("id")));
                predicates.add(inCohort ? cb.exists(membership) : cb.not(cb.exists(membership)));
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

    /**
     * The roles that may not be combined with {@link Role#SUPER_ADMIN} (P-9.5).
     *
     * <p>{@link Role#EMPLOYEE} is deliberately absent from this set, and that is not a loophole.
     * Employee is not a job here; it is the marker that says the caller is provisioned and
     * active, and {@code SecurityConfig} requires it on every authenticated endpoint. A Super
     * Admin without it could not call the administration console either, so the account would
     * hold a role it could not use.
     *
     * <p>What P-9.5 actually removes is being a <em>reviewee</em>, and that is enforced in
     * {@code AuthorizationService} at step 2 rather than by taking the marker away.
     */
    private static final Set<Role> NOT_WITH_SUPER_ADMIN =
            EnumSet.of(Role.MANAGER, Role.HR, Role.LEADERSHIP);

    /**
     * Every user is an Employee in addition to whatever else they hold (P-0.1), except that
     * the Super Admin holds nothing else (P-9.5).
     */
    private Set<Role> normaliseRoles(Set<Role> requested) {
        Set<Role> roles = EnumSet.of(Role.EMPLOYEE);
        if (requested != null) {
            roles.addAll(requested);
        }
        if (roles.contains(Role.SUPER_ADMIN)) {
            Set<Role> clashes = EnumSet.copyOf(roles);
            clashes.retainAll(NOT_WITH_SUPER_ADMIN);
            if (!clashes.isEmpty()) {
                // The Super Admin grants HR their departments and configures the cycles. Holding
                // a reviewing role on top would let one account arrange the scope and then act
                // inside it, which is the separation the whole grant mechanism exists to keep.
                throw new ValidationApiException(
                        "The Super Admin is a dedicated account and holds no other role. Remove "
                                + clashes.stream().map(Enum::name).sorted().toList()
                                + " or remove SUPER_ADMIN.");
            }
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

    // ---------------------------------------------------------------- mapping inside the transaction
    //
    // Each write above returns an entity, which is what internal callers and tests want. A
    // controller wants a DTO, and building one reads associations that are still lazy when the
    // service returns: the department and manager of a user loaded by id, and the roles
    // collection, which none of these methods touches on its way through.
    //
    // Converting in the controller therefore threw LazyInitializationException on a closed
    // session - a 500, not a denial - while every test passed, because tests hold a session open
    // for their whole run. These overloads apply the mapper here instead, where the transaction
    // is still open. UserListingOutsideTransactionTest is what proves it, by running without one.
    //
    // The entity-returning versions are kept rather than replaced, in the same way
    // directReports keeps both: a caller already inside a transaction has no need of this.

    public <T> T createUser(String asgardeoSubject, String email, String fullName,
                            Long departmentId, Long managerId, Set<Role> roles,
                            Function<AppUser, T> mapper) {
        return mapper.apply(createUser(asgardeoSubject, email, fullName, departmentId, managerId, roles));
    }

    public <T> T updateUser(Long id, String email, String fullName, Long departmentId,
                            Set<Role> roles, Function<AppUser, T> mapper) {
        return mapper.apply(updateUser(id, email, fullName, departmentId, roles));
    }

    public <T> T setManager(Long userId, Long managerId, Function<AppUser, T> mapper) {
        return mapper.apply(setManager(userId, managerId));
    }

    public <T> T reactivate(Long userId, Function<AppUser, T> mapper) {
        return mapper.apply(reactivate(userId));
    }

    public <T> T deactivate(Long userId, Function<DeactivationResult, T> mapper) {
        return mapper.apply(deactivate(userId));
    }

    public <T> T createDepartment(String name, Function<Department, T> mapper) {
        return mapper.apply(createDepartment(name));
    }

    public <T> T renameDepartment(Long id, String name, Function<Department, T> mapper) {
        return mapper.apply(renameDepartment(id, name));
    }
}
