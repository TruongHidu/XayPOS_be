package com.possaas.modules.table.service;

import com.possaas.common.exception.ConflictException;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class TableQrTokenGenerator {
    private final SecureRandom random = new SecureRandom();

    public String generate() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String rotate(String previous) {
        for (int attempt = 0; attempt < 3; attempt++) {
            String token = generate();
            if (!token.equals(previous)) return token;
        }
        throw new ConflictException("QR_TOKEN_CONFLICT", "Could not allocate a new QR token; retry");
    }
}
