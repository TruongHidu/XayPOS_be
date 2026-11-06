package com.possaas.modules.subscriptionpurchase.application.port;

import java.util.Optional;

/** Trusted receiving account for a providerAccountKey frozen on an attempt; never derived from transaction payloads. */
public interface PlatformAccountRegistry {
    Optional<String> accountNumber(String providerAccountKey);
}
