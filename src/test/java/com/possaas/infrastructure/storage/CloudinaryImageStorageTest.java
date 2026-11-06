package com.possaas.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.possaas.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class CloudinaryImageStorageTest {
    private static final String URL = "https://api.cloudinary.com/v1_1/demo/image/upload";
    private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000L);
    private final CloudinaryProperties properties = new CloudinaryProperties();
    private MockRestServiceServer server;
    private CloudinaryImageStorage storage;

    @BeforeEach
    void setup() {
        properties.setCloudName("demo");
        properties.setApiKey("key-1");
        properties.setApiSecret("shh");
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        storage = new CloudinaryImageStorage(properties, Clock.fixed(NOW, ZoneOffset.UTC), builder);
    }

    @Test
    void signatureMatchesCloudinarysPublishedExample() {
        assertThat(CloudinaryImageStorage.signature(
                "eager=w_400,h_300,c_pad|w_260,h_200,c_crop&public_id=sample_image&timestamp=1315060510", "abcd"))
                .isEqualTo("bfd09f95f331f558cbd1320e67aa8d488770583e");
    }

    @Test
    void sendsASignedRequestAndReturnsTheSecureUrl() {
        String signature = CloudinaryImageStorage.signature("folder=pos-saas/r1/menu&timestamp=1700000000", "shh");
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    var body = ((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.ISO_8859_1);
                    assertThat(request.getHeaders().getContentType().isCompatibleWith(MediaType.MULTIPART_FORM_DATA)).isTrue();
                    assertThat(body).contains("key-1", "pos-saas/r1/menu", "1700000000", signature, "pixels");
                    assertThat(body).doesNotContain("shh");
                })
                .andRespond(withSuccess("{\"secure_url\":\"https://res.cloudinary.com/demo/a.jpg\",\"public_id\":\"a\"}",
                        MediaType.APPLICATION_JSON));
        assertThat(storage.upload("pixels".getBytes(StandardCharsets.UTF_8), "r1/menu"))
                .isEqualTo("https://res.cloudinary.com/demo/a.jpg");
        server.verify();
    }

    @Test
    void aRejectedUploadBecomesItsOwnError() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertFails(HttpStatus.BAD_GATEWAY, "IMAGE_UPLOAD_FAILED");
    }

    @Test
    void aResponseWithoutAUrlIsAFailure() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertFails(HttpStatus.BAD_GATEWAY, "IMAGE_UPLOAD_FAILED");
    }

    @Test
    void missingCredentialsAreReportedWithoutCallingOut() {
        properties.setApiSecret("");
        assertFails(HttpStatus.SERVICE_UNAVAILABLE, "IMAGE_STORAGE_NOT_CONFIGURED");
        server.verify();
    }

    private void assertFails(HttpStatus status, String code) {
        assertThatThrownBy(() -> storage.upload(new byte[] {1}, "r1/menu"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(status);
                    assertThat(ex.getCode()).isEqualTo(code);
                });
    }
}
