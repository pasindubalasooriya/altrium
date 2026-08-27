package com.altrium.org;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface HrDepartmentGrantRepository extends JpaRepository<HrDepartmentGrant, Long> {

    /**
     * Every grant held by one HR user. Read on **every** HR request (P-2.5), never at login,
     * so that a grant change applies on the caller's very next request.
     *
     * <p>The department is fetched with it because the scope decision compares department
     * ids and the console shows their names.
     */
    @EntityGraph(attributePaths = {"department"})
    List<HrDepartmentGrant> findByHrUserId(Long hrUserId);

    Optional<HrDepartmentGrant> findByHrUserIdAndDepartmentId(Long hrUserId, Long departmentId);

    boolean existsByHrUserIdAndDepartmentId(Long hrUserId, Long departmentId);

    /**
     * Whether anybody could sign off a rating for somebody in this department.
     *
     * <p>Used by the release gate (P-4.8), which requires HR sign-off before a manager may
     * share a rating - and must not require one that nobody could ever give. It answers "is
     * there an HR user with real authority here, other than the person being reviewed?" and
     * mirrors the scope rules exactly: the grant must be active, and where the department is
     * the HR user's own it counts only if the grant is explicit (P-2.4).
     *
     * <p>The subject is excluded because P-2.2 is absolute: an HR user never exercises HR
     * authority over their own record, so their own grant can never be the one that unlocks
     * their own release.
     */
    @Query("select count(g) > 0 from HrDepartmentGrant g"
            + " where g.department.id = :departmentId"
            + " and g.hrUser.id <> :subjectId"
            + " and g.hrUser.active = true"
            + " and (g.hrUser.department is null"
            + "      or g.hrUser.department.id <> :departmentId"
            + "      or g.explicitGrant = true)")
    boolean anyHrCovers(@Param("departmentId") Long departmentId,
                        @Param("subjectId") Long subjectId);
}
