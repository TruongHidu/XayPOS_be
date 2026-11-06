package com.possaas.modules.subscriptionpurchase.service;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Transfer reference PS + 20 HEX (80 random bits); global UNIQUE in DB, never derived from tenant data. */
@Component
public class PaymentReferenceGenerator {
    public static final Pattern FORMAT = Pattern.compile("PS[0-9A-F]{20}");
    private final RandomGenerator random;

    public PaymentReferenceGenerator() { this(new SecureRandom()); }
    PaymentReferenceGenerator(RandomGenerator random) { this.random = random; }

    public String next() {
        byte[] bytes = new byte[10];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) random.nextInt(256);
        return "PS" + HexFormat.of().withUpperCase().formatHex(bytes);
    }
}
