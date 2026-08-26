package com.altrium.org;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
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

    /**
     * Both to-one associations are fetched with the page, because the user console shows the
     * department and manager name on every row. Without this each row triggers its own
     * query, which is invisible at 31 people and quietly quadratic later.
     *
     * <p>Only to-one associations belong here. Adding the roles collection would force
     * Hibernate to paginate in memory; roles are batch-fetched instead.
     */
    @Override
    @EntityGraph(attributePaths = {"department", "manager"})
    Page<AppUser> findAll(Specification<AppUser> spec, Pageable pageable);

    /**
     * Loads everything an authorization decision needs about a reviewee - roles for P-1.5,
     * department for P-2.1, manager for P-1.1 - in one query.
     *
     * <p>Join fetching is safe here because this returns a single row, not a page. It is
     * also necessary: the decision runs outside any transaction the caller may have opened,
     * and a lazy association would fail there rather than deny cleanly.
     *
     * <p>No {@code active} filter. A deactivated person's history stays readable to whoever
     * could read it (P-0.7), so their artifacts must still resolve a subject.
     */
    @Query("""
            SELECT u FROM AppUser u
            LEFT JOIN FETCH u.roles
            LEFT JOIN FETCH u.department
            LEFT JOIN FETCH u.manager
            WHERE u.id = :id
            """)
    Optional<AppUser> findByIdForAuthorization(@Param("id") Long id);

    /**
     * The ids a manager's queries carry in their {@code WHERE} clause (P-0.3, P-1.1).
     *
     * <p>Ids only, not entities: this runs on every scoped list request, and the decision
     * needs nothing else about those people.
     */
    @Query("SELECT u.id FROM AppUser u WHERE u.manager.id = :managerId")
    List<Long> findIdsByManagerId(@Param("managerId") Long managerId);

    Optional<AppUser> findByEmail(String email);

    boolean existsByAsgardeoSubject(String asgardeoSubject);

    boolean existsByEmail(String email);

    /**
     * Direct reports only (P-1.1) - never transitive. The whole manager side of the
     * authorization model is built on this one relationship.
     */
    List<AppUser> findByManagerId(Long managerId);

    long countByManagerId(Long managerId);

    /**
     * People who could be assigned as a peer reviewer for one subject (P-3.6).
     *
     * <p>Every exclusion the write path enforces is carried here in the {@code WHERE} clause,
     * so the list a manager is shown and the set the write will accept are the same set. A
     * candidate list assembled from a different rule would offer somebody the write then
     * refuses, which reads as a bug in the system rather than as the rule it is.
     *
     * <p>Excluded: the subject themselves, the subject's manager - who already writes the
     * manager review - and anybody deactivated (P-0.7).
     *
     * <p>Paged, and never a full roster in one response: nothing may be hardcoded to the size
     * of the organisation. Cross-department is permitted, so the candidate set is genuinely
     * everybody, which is precisely why it has to be paged and searchable rather than listed.
     */
    @Query("""
            SELECT u FROM AppUser u
            LEFT JOIN FETCH u.department
            WHERE u.active = true
              AND u.id <> :subjectId
              AND (:managerId IS NULL OR u.id <> :managerId)
              AND com.altrium.org.Role.SUPER_ADMIN NOT MEMBER OF u.roles
              AND (:name IS NULL OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', :name, '%')))
            ORDER BY u.fullName
            """)
    Page<AppUser> findPeerCandidates(@Param("subjectId") Long subjectId,
                                     @Param("managerId") Long managerId,
                                     @Param("name") String name,
                                     Pageable pageable);
}
