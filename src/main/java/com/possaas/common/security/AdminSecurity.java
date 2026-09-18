package com.possaas.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component("adminSecurity")
public class AdminSecurity {
    public boolean isSystemSuperAdmin(Authentication authentication) {
        return authentication != null
            && authentication.isAuthenticated()
            && authentication.getPrincipal() instanceof CurrentUser currentUser
            && currentUser.isSuperAdmin();
    }
}
