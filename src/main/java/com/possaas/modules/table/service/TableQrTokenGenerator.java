package com.possaas.modules.table.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.common.security.PublicLinkTokenGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class TableQrTokenGenerator {
    private final PublicLinkTokenGenerator tokens;

    public TableQrTokenGenerator() { this(new PublicLinkTokenGenerator()); }

    @Autowired
    public TableQrTokenGenerator(PublicLinkTokenGenerator tokens) { this.tokens = tokens; }

    public String generate() {
        return tokens.generate();
    }

    public String rotate(String previous) {
        for (int attempt = 0; attempt < 3; attempt++) {
            String token = generate();
            if (!token.equals(previous)) return token;
        }
        throw new ConflictException("QR_TOKEN_CONFLICT", "Could not allocate a new QR token; retry");
    }
}
