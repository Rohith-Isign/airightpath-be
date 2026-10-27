package com.rightpath.controller;

import java.util.LinkedHashSet;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.rightpath.dto.ApiResponse;
import com.rightpath.dto.MessageResponse;
import com.rightpath.dto.RoleAssignmentRequest;
import com.rightpath.dto.UserRbacViewResponse;
import com.rightpath.service.rbac.RbacAdminService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/admin/rbac")
public class RbacAdminController {

    private final RbacAdminService rbacAdminService;

    public RbacAdminController(RbacAdminService rbacAdminService) {
        this.rbacAdminService = rbacAdminService;
    }

    @PostMapping("/assign-role")
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    public ResponseEntity<ApiResponse<MessageResponse>> assignRole(@Valid @RequestBody RoleAssignmentRequest req) {
        rbacAdminService.assignRole(req.userEmail(), req.role());
        return ResponseEntity.ok(ApiResponse.ok(new MessageResponse("Role assigned")));
    }

    @PostMapping("/remove-role")
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    public ResponseEntity<ApiResponse<MessageResponse>> removeRole(@Valid @RequestBody RoleAssignmentRequest req) {
        rbacAdminService.removeRole(req.userEmail(), req.role());
        return ResponseEntity.ok(ApiResponse.ok(new MessageResponse("Role removed")));
    }

    /**
     * The roles and permissions held by one account.
     *
     * <p>Guarded by {@code USER_LIST}, not {@code USER_READ}. Candidates are
     * seeded with {@code USER_READ} so they can read their own profile, which
     * meant any candidate could pass any colleague's or any other candidate's
     * address here and read back their exact roles and permissions — a map of
     * who is worth attacking and of which authority names the application
     * checks. {@code USER_LIST} is the permission that already means "may see
     * other people's user records", and candidates do not hold it.</p>
     *
     * <p>Reading your <em>own</em> entry stays open to any authenticated user:
     * it tells the caller nothing they could not infer from what the UI already
     * lets them do, and keeping it available means tightening this endpoint
     * costs a candidate nothing. Compared case-insensitively because an address
     * that differs only in case is the same account.</p>
     */
    @GetMapping("/user")
    @PreAuthorize("hasAuthority('USER_LIST') or #email.equalsIgnoreCase(authentication.name)")
    public ResponseEntity<ApiResponse<UserRbacViewResponse>> getUserRbac(
            @RequestParam("email") @NotBlank @Email String email
    ) {
        var authorities = rbacAdminService.listSecurityAuthorities(email);
        var roles = new LinkedHashSet<String>();
        var perms = new LinkedHashSet<String>();
        for (String a : authorities) {
            if (a == null) {
                continue;
            }
            if (a.startsWith("ROLE_")) {
                roles.add(a.substring("ROLE_".length()));
            } else {
                perms.add(a);
            }
        }
        return ResponseEntity.ok(ApiResponse.ok(new UserRbacViewResponse(email, roles, perms)));
    }

    /**
     * Every role name the system defines.
     *
     * <p>Also moved off {@code USER_READ}: it is only useful for filling in a
     * role picker, which is staff-only, and it named the privileged roles to
     * anyone who asked.</p>
     */
    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('USER_LIST')")
    public ResponseEntity<ApiResponse<java.util.Set<String>>> listRoles() {
        return ResponseEntity.ok(ApiResponse.ok(rbacAdminService.listRoleNames()));
    }
}
