package com.possaas.infrastructure.captcha;

import com.possaas.common.captcha.CaptchaResult;
import com.possaas.common.captcha.CaptchaVerifier;
import com.possaas.config.CaptchaProperties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Cloudflare Turnstile server-side validation (siteverify). Fails closed: when Cloudflare cannot answer, or rejects
 * our own secret, the result is UNAVAILABLE and the request is refused rather than let through unverified.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.captcha", name = "enabled", havingValue = "true")
public class TurnstileCaptchaVerifier implements CaptchaVerifier {
    /** Turnstile tokens are at most 2048 characters; anything longer is rejected without calling Cloudflare. */
    static final int MAX_TOKEN_LENGTH = 2048;
    private static final Set<String> SECRET_ERRORS = Set.of("missing-input-secret", "invalid-input-secret");
    private static final String PROVIDER_ERROR = "internal-error";

    private final CaptchaProperties.Turnstile settings;
    private final RestClient client;

    @Autowired
    public TurnstileCaptchaVerifier(CaptchaProperties properties) {
        this(properties, RestClient.builder().requestFactory(timeouts(properties.getTurnstile())));
    }

    TurnstileCaptchaVerifier(CaptchaProperties properties, RestClient.Builder client) {
        this.settings = properties.getTurnstile();
        if (settings.getSecretKey() == null || settings.getSecretKey().isBlank()) {
            throw new IllegalStateException(
                "app.captcha.enabled=true requires app.captcha.turnstile.secret-key (TURNSTILE_SECRET_KEY)");
        }
        this.client = client.build();
    }

    private static SimpleClientHttpRequestFactory timeouts(CaptchaProperties.Turnstile settings) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(settings.getConnectTimeout());
        factory.setReadTimeout(settings.getReadTimeout());
        return factory;
    }

    @Override
    public CaptchaResult verify(String token, String clientIp, String expectedAction) {
        if (token == null || token.isBlank()) return CaptchaResult.missing();
        if (token.length() > MAX_TOKEN_LENGTH) return CaptchaResult.rejected(List.of("token-too-long"));

        var form = new LinkedMultiValueMap<String, String>();
        form.add("secret", settings.getSecretKey());
        form.add("response", token);
        if (clientIp != null && !clientIp.isBlank() && !"unknown".equals(clientIp)) form.add("remoteip", clientIp);

        Map<?, ?> body;
        try {
            body = client.post().uri(settings.getVerifyUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                // A 4xx still carries a JSON verdict (e.g. bad-request); only 5xx and transport errors throw.
                .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> { })
                .body(Map.class);
        } catch (RestClientException failure) {
            log.warn("Turnstile siteverify could not be reached: {}", failure.getMessage());
            return CaptchaResult.unavailable(List.of("siteverify-unreachable"));
        }
        return interpret(body, expectedAction);
    }

    private CaptchaResult interpret(Map<?, ?> body, String expectedAction) {
        if (body == null) return CaptchaResult.unavailable(List.of("empty-response"));
        List<String> codes = errorCodes(body.get("error-codes"));

        if (!Boolean.TRUE.equals(body.get("success"))) {
            if (codes.stream().anyMatch(SECRET_ERRORS::contains)) {
                log.error("Turnstile refused our secret key {}: check TURNSTILE_SECRET_KEY", codes);
                return CaptchaResult.unavailable(codes);
            }
            if (codes.contains(PROVIDER_ERROR)) return CaptchaResult.unavailable(codes);
            return CaptchaResult.rejected(codes.isEmpty() ? List.of("unsuccessful") : codes);
        }

        String expectedHostname = settings.getExpectedHostname();
        if (expectedHostname != null && !expectedHostname.isBlank()
                && !(body.get("hostname") instanceof String hostname && hostname.equalsIgnoreCase(expectedHostname))) {
            return CaptchaResult.rejected(List.of("hostname-mismatch"));
        }
        // Cloudflare test keys return no action; a real widget rendered with an action always returns it.
        if (body.get("action") instanceof String action && !action.isBlank()
                && expectedAction != null && !action.equals(expectedAction)) {
            return CaptchaResult.rejected(List.of("action-mismatch"));
        }
        return CaptchaResult.passed();
    }

    private static List<String> errorCodes(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }
}
