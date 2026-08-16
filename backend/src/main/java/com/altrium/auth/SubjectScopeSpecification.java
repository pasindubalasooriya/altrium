package com.altrium.auth;

import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@link SubjectScope} into a {@code WHERE} clause, for any entity that has a
 * reviewee.
 *
 * <p>Generic on purpose. Self-reviews, peer reviews, manager reviews, ratings, PDPs and PIPs
 * are six tables that must all be scoped identically; writing the predicate six times would
 * mean six chances to write it differently, and the one that differs is the leak. Each
 * feature supplies only the path from its own root to the subject.
 *
 * <p>The generated predicate is a disjunction of the caller's grounds. When the caller has
 * none it is {@code cb.disjunction()} — false — so an unscoped caller gets an empty page and
 * an empty count. The failure direction matters: a bug here should return nothing, never
 * everything.
 */
public final class SubjectScopeSpecification {

    private SubjectScopeSpecification() {
    }

    /**
     * @param scope       the caller's visibility, resolved for this request
     * @param subjectPath dot-separated path from the query root to the subject association,
     *                    e.g. {@code "subject"}. Null or blank when the root <em>is</em> the
     *                    subject, as when listing people rather than artifacts
     */
    public static <T> Specification<T> subjectsIn(SubjectScope scope, String subjectPath) {
        return (root, query, cb) -> {
            if (scope.isEmpty()) {
                return cb.disjunction();
            }

            // An artifact always has a subject, so the join is inner; a missing subject would
            // be a broken row, not a row to be shown to everyone.
            From<?, ?> subject = root;
            if (subjectPath != null && !subjectPath.isBlank()) {
                for (String segment : subjectPath.split("\\.")) {
                    subject = subject.join(segment, JoinType.INNER);
                }
            }

            List<Predicate> anyOf = new ArrayList<>();

            if (scope.includeSelf()) {
                anyOf.add(cb.equal(subject.get("id"), scope.callerId()));
            }

            if (!scope.directReportIds().isEmpty()) {
                // The report ids travel in the query, exactly as the constraint requires.
                anyOf.add(subject.get("id").in(scope.directReportIds()));
            }

            if (!scope.hrDepartmentIds().isEmpty()) {
                // Left join: Leadership carry no department and must simply not match, rather
                // than an inner join silently dropping rows for unrelated reasons.
                anyOf.add(cb.and(
                        subject.join("department", JoinType.LEFT).get("id").in(scope.hrDepartmentIds()),
                        // P-2.2 in SQL. The own-review block survives into every list and
                        // every pagination count, not just the point decision.
                        cb.notEqual(subject.get("id"), scope.callerId())));
            }

            return cb.or(anyOf.toArray(new Predicate[0]));
        };
    }
}
