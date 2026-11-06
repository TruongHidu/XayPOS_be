package com.possaas.modules.qrorder;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.modules.qrorder.dto.*;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QrRequestDtoContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String ITEM = "11111111-1111-4111-8111-111111111111";
    private static final String HASH = "a".repeat(64);

    @Test
    void approvalDistinguishesMissingTargetFromExplicitNullWithoutGlobalMapperChanges() throws Exception {
        var prefix = "{\"expectedRequestVersion\":0,\"expectedReviewContextHash\":\"" + HASH + "\"";
        var missing = mapper.readValue(prefix + "}", QrStaffRequests.Approve.class);
        var explicitNull = mapper.readValue(prefix + ",\"targetSessionId\":null}", QrStaffRequests.Approve.class);
        assertThat(missing.isTargetSessionPresent()).isFalse();
        assertThat(explicitNull.isTargetSessionPresent()).isTrue();
        assertThat(explicitNull.getTargetSessionId()).isNull();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(missing)).hasSize(1);
            assertThat(factory.getValidator().validate(explicitNull)).isEmpty();
        }
        assertThat(mapper.isEnabled(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
    }

    @Test
    void privateApprovalUnknownFieldsAreRejectedAndDtoStringsOmitHashAndReasons() throws Exception {
        var secret = "private-customer-note";
        var approve = mapper.readValue("{\"expectedRequestVersion\":0,\"expectedReviewContextHash\":\"" + HASH + "\",\"targetSessionId\":null}", QrStaffRequests.Approve.class);
        assertThat(approve.toString()).doesNotContain(HASH);
        assertThatThrownBy(() -> mapper.readValue("{\"expectedRequestVersion\":0,\"expectedReviewContextHash\":\"" + HASH + "\",\"targetSessionId\":null,\"sourceChannel\":\"QR_TABLE\"}", QrStaffRequests.Approve.class))
            .isInstanceOf(JsonProcessingException.class);
        assertThat(new QrStaffRequests.Reject(0L, secret).toString()).doesNotContain(secret);
        assertThatThrownBy(() -> mapper.readValue("{\"expectedRequestVersion\":0,\"reason\":\"No\",\"actor\":\"" + ITEM + "\"}", QrStaffRequests.Reject.class))
            .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void guestPayloadIsStrictAndNormalizedWithSeparateLimits() throws Exception {
        var request = mapper.readValue("{\"customerName\":\"  An  \",\"note\":\"   \",\"items\":[{\"itemId\":\"" + ITEM + "\",\"quantity\":2,\"note\":\"  Không hành  \"}]}", QrGuestRequests.Submit.class);
        assertThat(request.customerName()).isEqualTo("An");
        assertThat(request.note()).isNull();
        assertThat(request.items().get(0).quantity()).isEqualTo(new BigDecimal("2.000"));
        assertThat(request.items().get(0).note()).isEqualTo("Không hành");
        assertThat(request.toString()).doesNotContain("An", "Không hành");
        for (var field : new String[]{"restaurantId", "tableId", "sessionId", "orderId", "sourceChannel", "serviceType", "status", "unitPrice"}) {
            assertThatThrownBy(() -> mapper.readValue("{\"" + field + "\":\"not-allowed\",\"items\":[{\"itemId\":\"" + ITEM + "\",\"quantity\":1}]}", QrGuestRequests.Submit.class))
                .as(field).isInstanceOf(JsonProcessingException.class);
        }
        assertThatThrownBy(() -> mapper.readValue("{\"items\":[{\"itemId\":\"" + ITEM + "\",\"quantity\":1,\"costPrice\":0}]}", QrGuestRequests.Submit.class))
            .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void invalidGuestNumericAndSizeInputsCannotBypassDeserializer() {
        for (var quantity : new String[]{"0", "-1", "20.001", "0.0001", "\"1\"", "null", "1e100"})
            assertThatThrownBy(() -> mapper.readValue("{\"items\":[{\"itemId\":\"" + ITEM + "\",\"quantity\":" + quantity + "}]}", QrGuestRequests.Submit.class))
                .as(quantity).isInstanceOf(JsonProcessingException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"items\":[]}", QrGuestRequests.Submit.class))
            .isInstanceOf(JsonProcessingException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"note\":\"" + "n".repeat(501) + "\",\"items\":[{\"itemId\":\"" + ITEM + "\",\"quantity\":1}]}", QrGuestRequests.Submit.class))
            .isInstanceOf(JsonProcessingException.class);
        var line = "{\"itemId\":\"" + ITEM + "\",\"quantity\":1}";
        assertThatThrownBy(() -> mapper.readValue("{\"items\":[" + String.join(",", java.util.Collections.nCopies(21, line)) + "]}", QrGuestRequests.Submit.class))
            .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void accessBodyAndResponseToStringNeverExposeCredentials() throws Exception {
        assertThat(mapper.readValue("{}", QrGuestRequests.Empty.class)).isNotNull();
        assertThatThrownBy(() -> mapper.readValue("{\"guestToken\":\"secret\"}", QrGuestRequests.Empty.class))
            .isInstanceOf(JsonProcessingException.class);
        var response = new QrGuestAccessResponse("never-log-this-token", null, null, null, null,
            new QrGuestAccessResponse.RestaurantMetadata(UUID.randomUUID(), "Private Restaurant", "VND"), null);
        assertThat(response.toString()).doesNotContain("never-log-this-token", "Private Restaurant");
        assertThat(new QrGuestRequests.Line(UUID.randomUUID(), BigDecimal.ONE, "sensitive note").toString()).doesNotContain("sensitive note");
    }
}
