package com.rightpath.service.rbac;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rightpath.entity.UserRole;
import com.rightpath.repository.RoleRepository;
import com.rightpath.repository.UserRoleRepository;
import com.rightpath.repository.UsersRepository;
import com.rightpath.rbac.RoleName;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class RbacAdminService {

    private final UsersRepository usersRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final RbacAuthorityService authorityService;

    public RbacAdminService(
            UsersRepository usersRepository,
            RoleRepository roleRepository,
            UserRoleRepository userRoleRepository,
            RbacAuthorityService authorityService
    ) {
        this.usersRepository = usersRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.authorityService = authorityService;
    }

    @Transactional
    public void assignRole(String userEmail, RoleName roleName) {
        var user = usersRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userEmail));
        var role = roleRepository.findByName(roleName)
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleName));

    var existing = userRoleRepository.findByUser_EmailAndRole_NameAndActiveTrue(userEmail, roleName).orElse(null);
    if (existing != null) {
            return;
        }

        UserRole link = new UserRole();
        link.setUser(user);
        link.setRole(role);
        link.setActive(true);
        userRoleRepository.save(link);
        log.info("Role {} assigned to {}", roleName, userEmail);
    }

    @Transactional
    public void removeRole(String userEmail, RoleName roleName) {
        var link = userRoleRepository.findByUser_EmailAndRole_NameAndActiveTrue(userEmail, roleName).orElse(null);
        if (link == null) {
            return;
        }
        link.setActive(false);
        userRoleRepository.save(link);
        log.info("Role {} removed from {}", roleName, userEmail);
    }

    @Transactional(readOnly = true)
    public Set<String> listSecurityAuthorities(String userEmail) {
        return authorityService.resolveAuthorities(userEmail);
    }

    @Transactional(readOnly = true)
    public Set<String> listRoleNames() {
        Set<String> out = new LinkedHashSet<>();
        for (RoleName rn : RoleName.values()) {
            out.add(rn.name());
        }
        return out;
    }
}
