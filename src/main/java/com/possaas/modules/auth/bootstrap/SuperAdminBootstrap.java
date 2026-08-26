package com.possaas.modules.auth.bootstrap;

import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.authorization.entity.Role;
import com.possaas.modules.authorization.repository.RoleRepository;
import com.possaas.modules.user.entity.User;
import com.possaas.modules.user.repository.UserRepository;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix = "app.bootstrap.super-admin", name = "enabled", havingValue = "true")
public class SuperAdminBootstrap implements ApplicationRunner {
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final String email;
    private final String password;
    private final String name;

    public SuperAdminBootstrap(
        UserRepository userRepository,
        RoleRepository roleRepository,
        PasswordEncoder passwordEncoder,
        AuditService auditService,
        @Value("${app.bootstrap.super-admin.email:}") String email,
        @Value("${app.bootstrap.super-admin.password:}") String password,
        @Value("${app.bootstrap.super-admin.name:System Administrator}") String name
    ) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.email = email;
        this.password = password;
        this.name = name;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        validateConfiguration(normalizedEmail);
        Role superAdminRole = roleRepository.findByCodeAndRestaurantIdIsNull("SUPER_ADMIN")
            .orElseThrow(() -> new IllegalStateException("SUPER_ADMIN role is missing"));
        User existing = userRepository.findByEmailAndDeletedAtIsNull(normalizedEmail).orElse(null);
        if (existing != null) {
            if (existing.getRestaurantId() == null && existing.getRoleId().equals(superAdminRole.getId())) {
                return;
            }
            throw new IllegalStateException("Bootstrap email is already used by a non-SUPER_ADMIN account");
        }

        User user = new User();
        user.setRestaurantId(null);
        user.setRoleId(superAdminRole.getId());
        user.setName(name.trim());
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(password));
        user = userRepository.saveAndFlush(user);
        auditService.record(
            null,
            user.getId(),
            "SUPER_ADMIN_BOOTSTRAPPED",
            "users",
            user.getId(),
            null,
            null,
            null
        );
    }

    private void validateConfiguration(String normalizedEmail) {
        if (normalizedEmail.isBlank() || !normalizedEmail.contains("@")) {
            throw new IllegalStateException("SUPER_ADMIN_EMAIL must be a valid email address");
        }
        if (password.length() < 12 || password.length() > 100) {
            throw new IllegalStateException("SUPER_ADMIN_PASSWORD must contain 12 to 100 characters");
        }
        if (name.isBlank() || name.length() > 150) {
            throw new IllegalStateException("SUPER_ADMIN_NAME must contain 1 to 150 characters");
        }
    }
}
