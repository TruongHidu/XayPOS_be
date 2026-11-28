package com.possaas.infrastructure.captcha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.possaas.common.captcha.CaptchaResult;
import com.possaas.common.captcha.CaptchaResult.Outcome;
import com.possaas.config.CaptchaProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TurnstileCaptchaVerifierTest {
    private static final String URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";
    private final CaptchaProperties properties = new CaptchaProperties();
    private MockRestServiceServer server;
    private TurnstileCaptchaVerifier verifier;

    @BeforeEach
    void setup() {
        properties.setEnabled(true);
        properties.getTurnstile().setSecretKey("test-secret");
        rebuild();
    }

    private void rebuild() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        verifier = new TurnstileCaptchaVerifier(properties, builder);
    }

    @Test
    void aSuccessfulVerdictPassesAndTheFormCarriesSecretTokenAndAddress() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
            .andExpect(request -> {
                assertThat(request.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED)).isTrue();
                String body = ((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8);
                assertThat(body).contains("secret=test-secret", "response=tok", "remoteip=203.0.113.7");
            })
            .andRespond(json("{\"success\":true,\"hostname\":\"example.com\",\"error-codes\":[]}"));

        assertThat(verifier.verify("tok", "203.0.113.7", "register")).isEqualTo(CaptchaResult.passed());
        server.verify();
    }

    @Test
    void anUnknownAddressIsNotSentToCloudflare() {
        server.expect(requestTo(URL))
            .andExpect(request -> assertThat(((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8))
                .doesNotContain("remoteip"))
            .andRespond(json("{\"success\":true}"));

        assertThat(verifier.verify("tok", "unknown", "register").outcome()).isEqualTo(Outcome.PASSED);
    }

    @Test
    void badExpiredOrReusedTokensAreRejected() {
        for (String code : new String[] {"invalid-input-response", "timeout-or-duplicate", "bad-request"}) {
            rebuild();
            server.expect(requestTo(URL)).andRespond(json("{\"success\":false,\"error-codes\":[\"" + code + "\"]}"));

            CaptchaResult result = verifier.verify("tok", "203.0.113.7", "register");

            assertThat(result.outcome()).as(code).isEqualTo(Outcome.REJECTED);
            assertThat(result.reasons()).containsExactly(code);
        }
    }

    @Test
    void aVerdictReturnedWithA4xxStatusIsStillRead() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_JSON).body("{\"success\":false,\"error-codes\":[\"bad-request\"]}"));

        assertThat(verifier.verify("tok", "203.0.113.7", "register").outcome()).isEqualTo(Outcome.REJECTED);
    }

    @Test
    void ourOwnSecretProblemsAndProviderErrorsFailClosedAsUnavailable() {
        for (String code : new String[] {"invalid-input-secret", "missing-input-secret", "internal-error"}) {
            rebuild();
            server.expect(requestTo(URL)).andRespond(json("{\"success\":false,\"error-codes\":[\"" + code + "\"]}"));

            assertThat(verifier.verify("tok", "203.0.113.7", "register").outcome()).as(code).isEqualTo(Outcome.UNAVAILABLE);
        }
    }

    @Test
    void anUnreachableOrFailingProviderIsUnavailable() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThat(verifier.verify("tok", "203.0.113.7", "register").outcome()).isEqualTo(Outcome.UNAVAILABLE);

        rebuild();
        server.expect(requestTo(URL)).andRespond(withException(new IOException("connect timed out")));
        assertThat(verifier.verify("tok", "203.0.113.7", "register").outcome()).isEqualTo(Outcome.UNAVAILABLE);
    }

    @Test
    void aTokenIssuedForAnotherHostnameOrActionIsRejected() {
        properties.getTurnstile().setExpectedHostname("pos.example.vn");
        rebuild();
        server.expect(requestTo(URL)).andRespond(json("{\"success\":true,\"hostname\":\"evil.example\"}"));
        assertThat(verifier.verify("tok", "203.0.113.7", "register").reasons()).containsExactly("hostname-mismatch");

        rebuild();
        server.expect(requestTo(URL)).andRespond(json("{\"success\":true,\"hostname\":\"POS.example.vn\",\"action\":\"login\"}"));
        assertThat(verifier.verify("tok", "203.0.113.7", "register").reasons()).containsExactly("action-mismatch");

        rebuild();
        server.expect(requestTo(URL)).andRespond(json("{\"success\":true,\"hostname\":\"pos.example.vn\",\"action\":\"register\"}"));
        assertThat(verifier.verify("tok", "203.0.113.7", "register").outcome()).isEqualTo(Outcome.PASSED);
    }

    @Test
    void missingOrOversizedTokensNeverReachCloudflare() {
        assertThat(verifier.verify(null, "203.0.113.7", "register").outcome()).isEqualTo(Outcome.MISSING);
        assertThat(verifier.verify("  ", "203.0.113.7", "register").outcome()).isEqualTo(Outcome.MISSING);
        assertThat(verifier.verify("x".repeat(TurnstileCaptchaVerifier.MAX_TOKEN_LENGTH + 1), "203.0.113.7", "register")
            .outcome()).isEqualTo(Outcome.REJECTED);
        server.verify();
    }

    @Test
    void enablingWithoutASecretFailsAtStartup() {
        properties.getTurnstile().setSecretKey(" ");
        assertThatThrownBy(() -> new TurnstileCaptchaVerifier(properties, RestClient.builder()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TURNSTILE_SECRET_KEY");
    }

    private static org.springframework.test.web.client.ResponseCreator json(String body) {
        return withSuccess(body, MediaType.APPLICATION_JSON);
    }
}
