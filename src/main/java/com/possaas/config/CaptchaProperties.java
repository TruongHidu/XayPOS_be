package com.possaas.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** CAPTCHA on public endpoints (see {@code common.captcha.CaptchaVerifier}). Provider: Cloudflare Turnstile. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.captcha")
public class CaptchaProperties {
    /** false swaps in a verifier that accepts every request (dev without keys, tests). */
    private boolean enabled = false;
    private Turnstile turnstile = new Turnstile();

    @Getter
    @Setter
    public static class Turnstile {
        /** Server-side secret from the Cloudflare dashboard; required when enabled. Never sent to browsers. */
        private String secretKey;
        private String verifyUrl = "https://challenges.cloudflare.com/turnstile/v0/siteverify";
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration readTimeout = Duration.ofSeconds(5);
        /** When set, tokens must have been issued on this hostname. Leave blank with Cloudflare test keys. */
        private String expectedHostname;
    }
}
