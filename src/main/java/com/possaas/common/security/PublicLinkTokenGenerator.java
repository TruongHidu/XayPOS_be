package com.possaas.common.security;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Random public link identifiers; possessing one does not grant mutation permissions. */
@Component
public class PublicLinkTokenGenerator {
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");
    private final SecureRandom random = new SecureRandom();

    public String generate() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static boolean isValid(String token) {
        return token != null && FORMAT.matcher(token).matches();
    }
}
