package com.altrium.web.admin;

import com.altrium.org.AppUser;
import com.altrium.org.Department;
import com.altrium.org.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;

/**
 * Request and response shapes for Super Admin org management (feature 2).
 *
 * <p>Nothing here exposes review, rating or plan content. The Super Admin manages structure
 * and never reads what people wrote about each other (P-9.4).
 */
public final class OrgDtos {

    private OrgDtos() {
    }

    // ---------------------------------------------------------------- responses

    public record DepartmentView(Long id, String name) {
        public static DepartmentView of(Department department) {
            return new DepartmentView(department.getId(), department.getName());
        }
    }

    /**
     * A row in the user console.
     *
     * <p>Carries the manager's name as well as their id so the console need not issue a
     * lookup per row - the page would otherwise be one query plus one per person, which
     * degrades exactly as the organisation grows.
     */
    public record UserView(
            Long id,
            String email,
            String fullName,
            Long departmentId,
            String departmentName,
            Long managerId,
            String managerName,
            boolean active,
            List<String> roles) {

        public static UserView of(AppUser user) {
            return new UserView(
                    user.getId(),
                    user.getEmail(),
                    user.getFullName(),
                    user.getDepartment() == null ? null : user.getDepartment().getId(),
                    user.getDepartment() == null ? null : user.getDepartment().getName(),
                    user.getManager() == null ? null : user.getManager().getId(),
                    user.getManager() == null ? null : user.getManager().getFullName(),
                    user.isActive(),
                    user.getRoles().stream().map(Enum::name).sorted().toList());
        }
    }

    /** A page of users, with the totals the console needs to render paging controls. */
    public record PageView<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }

    public record DeactivationView(UserView user, long danglingReports, String warning) {
    }

    // ---------------------------------------------------------------- requests

    public record CreateUserRequest(
            @NotBlank @Size(max = 255) String asgardeoSubject,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 200) String fullName,
            Long departmentId,
            Long managerId,
            Set<Role> roles) {
    }

    public record UpdateUserRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 200) String fullName,
            Long departmentId,
            Set<Role> roles) {
    }

    /** Null {@code managerId} detaches the user, which is how the top of the chain is set. */
    public record SetManagerRequest(Long managerId) {
    }

    public record DepartmentRequest(@NotBlank @Size(max = 100) String name) {
    }
}
