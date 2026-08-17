package com.altrium.web.admin;

import com.altrium.org.OrgService;
import com.altrium.org.Role;
import com.altrium.web.admin.OrgDtos.CreateUserRequest;
import com.altrium.web.admin.OrgDtos.DeactivationView;
import com.altrium.web.admin.OrgDtos.PageView;
import com.altrium.web.admin.OrgDtos.SetManagerRequest;
import com.altrium.web.admin.OrgDtos.UpdateUserRequest;
import com.altrium.web.admin.OrgDtos.UserView;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * User and reporting-line management (feature 2). Super Admin only (P-9.1).
 *
 * <p>The role gate is expressible as an annotation here precisely because this endpoint has
 * no relationship dimension - a Super Admin manages the whole organisation. That is the
 * exception, not the pattern: every review and plan endpoint depends on who reports to whom,
 * which no annotation can express, and routes through the authorization component instead.
 *
 * <p>What this controller may never gain is a route into review, rating or plan content
 * (P-9.4). The Super Admin already grants HR their departments; letting them read reviews as
 * well would leave nobody the model constrains.
 */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class UserAdminController {

    /**
     * Caps how much a caller can demand in one page. Without it, {@code ?size=100000} turns
     * a paged endpoint back into "load everything", which is the thing paging exists to stop.
     */
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 25;

    private final OrgService org;

    public UserAdminController(OrgService org) {
        this.org = org;
    }

    @GetMapping
    @Operation(summary = "A page of users, filtered in SQL")
    public PageView<UserView> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Role role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {

        // The mapper is handed to the service so conversion happens inside its transaction.
        // Mapping entities out here would touch lazy proxies on a closed session.
        Page<UserView> result = org.listUsers(search, departmentId, active, role,
                PageRequest.of(Math.max(page, 0),
                        Math.clamp(size, 1, MAX_PAGE_SIZE),
                        Sort.by("fullName").ascending()),
                UserView::of);

        return new PageView<>(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    @GetMapping("/{id}")
    public UserView get(@PathVariable Long id) {
        return org.get(id, UserView::of);
    }

    @GetMapping("/{id}/direct-reports")
    @Operation(summary = "Direct reports only - never transitive (P-1.1)")
    public java.util.List<UserView> directReports(@PathVariable Long id) {
        return org.directReports(id, UserView::of);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserView create(@Valid @RequestBody CreateUserRequest request) {
        return UserView.of(org.createUser(
                request.asgardeoSubject(),
                request.email(),
                request.fullName(),
                request.departmentId(),
                request.managerId(),
                request.roles() == null ? java.util.Set.<Role>of() : request.roles()));
    }

    @PutMapping("/{id}")
    public UserView update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        return UserView.of(org.updateUser(
                id,
                request.email(),
                request.fullName(),
                request.departmentId(),
                request.roles() == null ? java.util.Set.<Role>of() : request.roles()));
    }

    @PutMapping("/{id}/manager")
    @Operation(summary = "Set or clear a reporting line; rejects loops (P-1.4)")
    public UserView setManager(@PathVariable Long id, @RequestBody SetManagerRequest request) {
        return UserView.of(org.setManager(id, request.managerId()));
    }

    /**
     * Soft delete (P-0.7). Never a DELETE, because nothing is deleted - the verb would
     * misdescribe what happens and invite someone to implement the real thing later.
     */
    @PutMapping("/{id}/deactivate")
    public DeactivationView deactivate(@PathVariable Long id) {
        OrgService.DeactivationResult result = org.deactivate(id);
        String warning = result.danglingReports() == 0
                ? null
                : result.danglingReports() + " user(s) still report to this person and need reassigning";
        return new DeactivationView(UserView.of(result.user()), result.danglingReports(), warning);
    }

    @PutMapping("/{id}/reactivate")
    public UserView reactivate(@PathVariable Long id) {
        return UserView.of(org.reactivate(id));
    }
}
