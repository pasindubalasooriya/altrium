package com.altrium.web.admin;

import com.altrium.org.OrgService;
import com.altrium.web.admin.OrgDtos.DepartmentRequest;
import com.altrium.web.admin.OrgDtos.DepartmentView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Departments (feature 2). Super Admin only (P-9.1).
 *
 * <p>Departments are the unit of HR scoping, so this is quietly load-bearing: every HR grant
 * points at a row created here, and the self-exclusion rules (P-2.3, P-2.4) compare against
 * the department a user sits in.
 *
 * <p>There is no delete. A department with people and history in it cannot be removed
 * without orphaning both, for the same reason users are only ever deactivated.
 */
@RestController
@RequestMapping("/api/admin/departments")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class DepartmentAdminController {

    private final OrgService org;

    public DepartmentAdminController(OrgService org) {
        this.org = org;
    }

    @GetMapping
    public List<DepartmentView> list() {
        return org.listDepartments().stream().map(DepartmentView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DepartmentView create(@Valid @RequestBody DepartmentRequest request) {
        return DepartmentView.of(org.createDepartment(request.name()));
    }

    @PutMapping("/{id}")
    public DepartmentView rename(@PathVariable Long id, @Valid @RequestBody DepartmentRequest request) {
        return DepartmentView.of(org.renameDepartment(id, request.name()));
    }
}
