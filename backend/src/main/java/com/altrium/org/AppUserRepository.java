package com.altrium.org;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * {@link JpaSpecificationExecutor} is what lets scope filters be pushed into the generated
 * SQL rather than applied afterwards in Java (P-0.3). Filtering a fetched list would still
 * leak through pagination counts, which report what the query matched, not what survived.
 */
public interface AppUserRepository extends JpaRepository<AppUser, Long>,
        JpaSpecificationExecutor<AppUser> {

    /**
     * Resolves a validated JWT to the person it belongs to, fetching roles in the same query
     * so the per-request identity lookup costs one round trip rather than two.
     *
     * <p>Deliberately does <em>not</em> filter on {@code active}: a deactivated user must be
     * resolvable so the caller can be told 403 rather than treated as unknown, and so their
     * historical artifacts stay attributable (P-0.7).
     */
    @Query("""
            SELECT u FROM AppUser u
            LEFT JOIN FETCH u.roles
            WHERE u.asgardeoSubject = :subject
            """)
    Optional<AppUser> findByAsgardeoSubjectWithRoles(@Param("subject") String subject);

    Optional<AppUser> findByEmail(String email);

    boolean existsByAsgardeoSubject(String asgardeoSubject);

    boolean existsByEmail(String email);

    /**
     * Direct reports only (P-1.1) — never transitive. The whole manager side of the
     * authorization model is built on this one relationship.
     */
    List<AppUser> findByManagerId(Long managerId);

    long countByManagerId(Long managerId);
}
