package com.possaas.modules.user.service;

import com.possaas.modules.user.application.port.TemporaryPasswordGenerator;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/** 12 characters without 0/O/1/l/I; always contains an upper-case letter, a lower-case letter and a digit. */
@Component
public class SecureRandomTemporaryPasswordGenerator implements TemporaryPasswordGenerator {
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnpqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String ALL = UPPER + LOWER + DIGITS;
    private static final int LENGTH = 12;

    private final SecureRandom random = new SecureRandom();

    @Override
    public String generate() {
        char[] value = new char[LENGTH];
        value[0] = pick(UPPER);
        value[1] = pick(LOWER);
        value[2] = pick(DIGITS);
        for (int i = 3; i < LENGTH; i++) value[i] = pick(ALL);
        for (int i = LENGTH - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char swap = value[i]; value[i] = value[j]; value[j] = swap;
        }
        return new String(value);
    }

    private char pick(String alphabet) { return alphabet.charAt(random.nextInt(alphabet.length())); }
}
