package com.altrium.org;

import com.altrium.config.ConflictApiException;
import com.altrium.config.NotFoundApiException;
import com.altrium.config.ValidationApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Function;

/**
 * Granting and revoking HR department access (P-9.2). Super Admin only.
 *
 * <p>These grants are the sole input to HR's department scope, so this service is where the
 * conflict-of-interest control is actually configured. It stays deliberately thin: the
 * decisions about what a grant <em>means</em> - the own-department block and the explicit
 * override - belong to the resolver that reads them per request, not to the writer.
 */
@Service
@Transactional
public class HrGrantService {

    private final HrDepartmentGrantRepository grants;
    private final AppUserRepository users;
    private final DepartmentRepository departments;

    public HrGrantService(HrDepartmentGrantRepository grants,
                          AppUserRepository users,
                          DepartmentRepository departments) {
        this.grants = grants;
        this.users = users;
        this.departments = departments;
    }

    public <T> T grant(Long hrUserId, Long departmentId, boolean explicitGrant,
                       Long grantedByUserId, Function<HrDepartmentGrant, T> mapper) {

        AppUser hrUser = users.findById(hrUserId)
                .orElseThrow(() -> new NotFoundApiException("No such user"));

        // A grant to someone without the HR role would sit in the table doing nothing, and
        // would read like access that had been given. Refuse it rather than store a lie.
        if (!hrUser.getRoles().contains(Role.HR)) {
            throw new ValidationApiException(
                    hrUser.getFullName() + " does not hold the HR role, so cannot be granted a department");
        }
        if (!hrUser.isActive()) {
            throw new ValidationApiException("A deactivated user cannot be granted a department");
        }

        Department department = departments.findById(departmentId)
                .orElseThrow(() -> new NotFoundApiException("No such department"));

        if (grants.existsByHrUserIdAndDepartmentId(hrUserId, departmentId)) {
            throw new ConflictApiException(
                    hrUser.getFullName() + " already holds a grant for " + department.getName()
                            + "; revoke it or update the existing grant");
        }

        AppUser grantedBy = grantedByUserId == null ? null : users.findById(grantedByUserId).orElse(null);

        return mapper.apply(grants.save(new HrDepartmentGrant(hrUser, department, explicitGrant, grantedBy)));
    }

    /**
     * Changes whether an existing grant lifts the own-department block (P-2.4).
     *
     * <p>Separate from granting because promoting someone to HR Head is a different decision
     * from giving them a department, and should be visible as such.
     */
    public <T> T setExplicit(Long hrUserId, Long departmentId, boolean explicitGrant,
                             Function<HrDepartmentGrant, T> mapper) {
        HrDepartmentGrant grant = grants.findByHrUserIdAndDepartmentId(hrUserId, departmentId)
                .orElseThrow(() -> new NotFoundApiException("No such grant"));
        grant.setExplicitGrant(explicitGrant);
        return mapper.apply(grant);
    }

    /**
     * Revokes a grant.
     *
     * <p>Genuinely deletes the row, unlike a user. The soft-delete rule exists to preserve
     * history and keep authored artifacts attributable; a grant authors nothing, and a
     * revoked grant left lying around as an inactive row is a standing invitation to read it
     * back by mistake. It takes effect on the caller's next request (P-2.5).
     */
    public void revoke(Long hrUserId, Long departmentId) {
        HrDepartmentGrant grant = grants.findByHrUserIdAndDepartmentId(hrUserId, departmentId)
                .orElseThrow(() -> new NotFoundApiException("No such grant"));
        grants.delete(grant);
    }

    @Transactional(readOnly = true)
    public <T> List<T> listFor(Long hrUserId, Function<HrDepartmentGrant, T> mapper) {
        return grants.findByHrUserId(hrUserId).stream().map(mapper).toList();
    }
}
