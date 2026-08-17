package com.altrium.web.hr;

import com.altrium.auth.HrScope;
import com.altrium.auth.HrScopeResolver;
import com.altrium.org.DepartmentRepository;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * What the calling HR user may currently oversee.
 *
 * <p>Exists for two reasons. The HR console needs to know which departments to offer before
 * it can show anything. And it makes the scope rules observable: an HR user who cannot see
 * their own department here learns why from the response rather than from an unexplained 403
 * later.
 *
 * <p>It reports scope, never content. Nothing about any review, rating or plan appears here.
 */
@RestController
@RequestMapping("/api/hr/scope")
@PreAuthorize("hasRole('HR')")
public class HrScopeController {

    private final HrScopeResolver scopeResolver;
    private final DepartmentRepository departments;

    public HrScopeController(HrScopeResolver scopeResolver, DepartmentRepository departments) {
        this.scopeResolver = scopeResolver;
        this.departments = departments;
    }

    public record ScopedDepartment(Long id, String name, boolean isOwnDepartment) {
    }

    /**
     * @param ownDepartmentExcluded true when the caller's own department is deliberately
     *                              outside their scope (P-2.3) - the ordinary HR case, and
     *                              the thing most likely to look like a bug if unexplained
     * @param note                  a plain-language reason, so the rule is legible in the UI
     */
    public record ScopeView(
            List<ScopedDepartment> departments,
            Long ownDepartmentId,
            boolean ownDepartmentExcluded,
            boolean ownDepartmentLiftedByExplicitGrant,
            String note) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "Departments this HR user may act in, resolved fresh for this request")
    public ScopeView myScope() {
        HrScope scope = scopeResolver.resolve();

        List<ScopedDepartment> visible = departments.findAllById(scope.departmentIds()).stream()
                .map(d -> new ScopedDepartment(d.getId(), d.getName(),
                        d.getId().equals(scope.ownDepartmentId())))
                .sorted(Comparator.comparing(ScopedDepartment::name))
                .toList();

        boolean ownExcluded = scope.ownDepartmentId() != null && !scope.covers(scope.ownDepartmentId());

        return new ScopeView(
                visible,
                scope.ownDepartmentId(),
                ownExcluded,
                scope.ownDepartmentLifted(),
                note(scope, ownExcluded));
    }

    private String note(HrScope scope, boolean ownExcluded) {
        if (scope.isEmpty()) {
            return "No departments granted yet. A Super Admin assigns them.";
        }
        if (scope.ownDepartmentLifted()) {
            return "Your own department is included by explicit grant. "
                    + "You still cannot access your own reviews.";
        }
        if (ownExcluded) {
            return "Your own department is excluded, so that nobody oversees reviews in the "
                    + "department they work in.";
        }
        return "Scope resolved for this request.";
    }
}
