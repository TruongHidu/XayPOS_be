package com.possaas.modules.auth.service;

import com.possaas.modules.user.entity.User;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Checks a password with exactly one hash comparison whether or not the account exists, so the response time of
 * a login does not tell an attacker which e-mails are registered.
 */
@Component
public class CredentialVerifier {
    private final PasswordEncoder passwordEncoder;
    /** Hash of a random secret nobody knows; compared against when there is no account. */
    private final String unknownAccountHash;

    public CredentialVerifier(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
        this.unknownAccountHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /** True only when {@code user} exists and the password matches its hash. */
    public boolean matches(String rawPassword, User user) {
        String hash = user == null ? unknownAccountHash : user.getPasswordHash();
        boolean matched = passwordEncoder.matches(rawPassword, hash);
        return user != null && matched;
    }
}
