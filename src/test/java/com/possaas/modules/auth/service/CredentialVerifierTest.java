package com.possaas.modules.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.possaas.modules.user.entity.User;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class CredentialVerifierTest {
    private final CountingEncoder encoder = new CountingEncoder();
    private final CredentialVerifier verifier = new CredentialVerifier(encoder);

    @Test
    void anUnknownAccountStillCostsOneHashComparison() {
        int before = encoder.matches.get();

        assertThat(verifier.matches("any-password", null)).isFalse();

        assertThat(encoder.matches.get()).isEqualTo(before + 1);
    }

    @Test
    void anExistingAccountMatchesOnlyItsOwnPassword() {
        User user = new User();
        user.setPasswordHash(encoder.encode("StrongPassword123!"));

        assertThat(verifier.matches("StrongPassword123!", user)).isTrue();
        assertThat(verifier.matches("wrong", user)).isFalse();
    }

    /** Low BCrypt cost keeps the test fast; the comparison count is what matters. */
    private static final class CountingEncoder implements PasswordEncoder {
        private final PasswordEncoder delegate = new BCryptPasswordEncoder(4);
        final AtomicInteger matches = new AtomicInteger();

        @Override
        public String encode(CharSequence rawPassword) {
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            matches.incrementAndGet();
            return delegate.matches(rawPassword, encodedPassword);
        }
    }
}
