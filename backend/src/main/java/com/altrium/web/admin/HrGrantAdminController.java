package com.altrium.web.admin;

import com.altrium.auth.CurrentUserService;
import com.altrium.org.HrDepartmentGrant;
import com.altrium.org.HrGrantService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Granting HR users their departments (P-9.2). Super Admin only.
 *
 * <p>This endpoint configures the conflict-of-interest control, so who may call it matters
 * as much as what it does. HR must not reach it: an HR user able to grant themselves a
 * department could lift their own scoping, and one able to set the explicit flag could lift
 * their own-department block and start overseeing their own colleagues' reviews.
 */
@RestController
@RequestMapping("/api/admin/hr-grants")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class HrGrantAdminController {

    private final HrGrantService grants;
    private final CurrentUserService currentUser;

    public HrGrantAdminController(HrGrantService grants, CurrentUserService currentUser) {
        this.grants = grants;
        this.currentUser = currentUser;
    }

    public record GrantView(
            Long id,
            Long hrUserId,
            String hrUserName,
            Long departmentId,
            String departmentName,
            boolean explicitGrant,
            String grantedByName,
            Instant grantedAt) {

        static GrantView of(HrDepartmentGrant grant) {
            return new GrantView(
                    grant.getId(),
                    grant.getHrUser().getId(),
                    grant.getHrUser().getFullName(),
                    grant.getDepartment().getId(),
                    grant.getDepartment().getName(),
                    grant.isExplicitGrant(),
                    grant.getGrantedBy() == null ? null : grant.getGrantedBy().getFullName(),
                    grant.getGrantedAt());
        }
    }

    public record GrantRequest(
            @NotNull Long hrUserId,
            @NotNull Long departmentId,
            /*
             * Lifts the own-department block for this pairing only (P-2.4) — the HR Head
             * mechanism. It never lifts the own-review block (P-2.2), which has no override.
             */
            boolean explicitGrant) {
    }

    public record ExplicitRequest(boolean explicitGrant) {
    }

    @GetMapping("/{hrUserId}")
    @Operation(summary = "Every department granted to one HR user")
    public List<GrantView> list(@PathVariable Long hrUserId) {
        return grants.listFor(hrUserId, GrantView::of);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Grant an HR user a department")
    public GrantView grant(@Valid @RequestBody GrantRequest request) {
        return grants.grant(
                request.hrUserId(),
                request.departmentId(),
                request.explicitGrant(),
                currentUser.require().id(),
                GrantView::of);
    }

    @PutMapping("/{hrUserId}/{departmentId}/explicit")
    @Operation(summary = "Set or clear the explicit-grant flag (the HR Head mechanism, P-2.4)")
    public GrantView setExplicit(@PathVariable Long hrUserId,
                                 @PathVariable Long departmentId,
                                 @RequestBody ExplicitRequest request) {
        return grants.setExplicit(hrUserId, departmentId, request.explicitGrant(), GrantView::of);
    }

    /**
     * A real delete, unlike deactivating a user. A grant authors nothing and carries no
     * history worth keeping, and a revoked grant left as an inactive row is an invitation to
     * read it back by mistake.
     */
    @DeleteMapping("/{hrUserId}/{departmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke a grant; applies on the holder's very next request (P-2.5)")
    public void revoke(@PathVariable Long hrUserId, @PathVariable Long departmentId) {
        grants.revoke(hrUserId, departmentId);
    }
}
