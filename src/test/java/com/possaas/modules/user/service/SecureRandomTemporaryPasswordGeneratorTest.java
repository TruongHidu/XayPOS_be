package com.possaas.modules.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SecureRandomTemporaryPasswordGeneratorTest {
    private final SecureRandomTemporaryPasswordGenerator generator = new SecureRandomTemporaryPasswordGenerator();

    @Test void passwordsAreReadableMixedAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String password = generator.generate();
            assertThat(password).hasSize(12).matches(".*[A-Z].*").matches(".*[a-z].*").matches(".*[2-9].*")
                .doesNotContainPattern("[0O1lI]");
            seen.add(password);
        }
        assertThat(seen).hasSize(500);
    }
}
