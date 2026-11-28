package com.possaas.modules.auth.application.port;

/**
 * Temporarily locks an account after too many wrong passwords, whichever addresses they come from. Callers pass
 * the normalized e-mail and must treat unknown and existing e-mails alike, so a lock never reveals which exists.
 */
public interface LoginAttemptGuard {
    /** Throws {@code RateLimitExceededException} (429 LOGIN_TEMPORARILY_LOCKED) while the e-mail is locked. */
    void assertNotLocked(String email);

    void recordFailure(String email);

    void recordSuccess(String email);
}
