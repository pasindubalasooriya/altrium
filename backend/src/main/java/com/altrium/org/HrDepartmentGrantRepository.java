package com.altrium.org;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
