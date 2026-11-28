package com.possaas.common.captcha;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of checking a CAPTCHA token. {@code reasons} carries provider error codes for logs only; it never
 * contains the token itself.
 */
public record CaptchaResult(Outcome outcome, List<String> reasons) {
    public enum Outcome {
        /** The token proves a human solved the challenge for this action. */
        PASSED,
        /** No token was sent. */
        MISSING,
        /** The token is invalid, expired, already used, or issued for another hostname/action. */
        REJECTED,
        /** The provider could not be asked (network, provider error, or our own misconfiguration). */
        UNAVAILABLE
    }

    public CaptchaResult {
        Objects.requireNonNull(outcome, "outcome");
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public static CaptchaResult passed() {
        return new CaptchaResult(Outcome.PASSED, List.of());
    }

    public static CaptchaResult missing() {
        return new CaptchaResult(Outcome.MISSING, List.of("missing-input-response"));
    }

    public static CaptchaResult rejected(List<String> reasons) {
        return new CaptchaResult(Outcome.REJECTED, reasons);
    }

    public static CaptchaResult unavailable(List<String> reasons) {
        return new CaptchaResult(Outcome.UNAVAILABLE, reasons);
    }
}
