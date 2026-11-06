package com.possaas.infrastructure.realtime;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

/** Each authenticated socket has its own destination principal. */
public record RealtimePrincipal(
        String sessionId, UUID userId, UUID restaurantId, String role, Instant expiresAt) implements Principal {
    @Override public String getName() { return sessionId; }
}
