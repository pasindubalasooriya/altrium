package com.altrium.org;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

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
}
