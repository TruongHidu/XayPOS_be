package com.possaas.modules.qrorder.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.*;
import java.util.UUID;

public final class QrStaffRequests {
    private QrStaffRequests() {}

    /** A setter tracks explicit null separately from a missing target property. */
    public static final class Approve {
        @NotNull @Min(0) private Long expectedRequestVersion;
        @NotBlank @Pattern(regexp="[a-f0-9]{64}") private String expectedReviewContextHash;
        private UUID targetSessionId;
        private boolean targetSessionPresent;
        public Long getExpectedRequestVersion() { return expectedRequestVersion; }
        public void setExpectedRequestVersion(Long value) { expectedRequestVersion = value; }
        public String getExpectedReviewContextHash() { return expectedReviewContextHash; }
        public void setExpectedReviewContextHash(String value) { expectedReviewContextHash = value; }
        public UUID getTargetSessionId() { return targetSessionId; }
        @JsonSetter("targetSessionId") public void setTargetSessionId(UUID value) { targetSessionId = value; targetSessionPresent = true; }
        @JsonIgnore @AssertTrue(message="targetSessionId must be supplied explicitly")
        public boolean isTargetSessionPresent() { return targetSessionPresent; }
        @JsonAnySetter public void unsupported(String name, Object ignored) { throw new IllegalArgumentException("Unsupported approval field"); }
        @Override public String toString() { return "QrApproveRequest[private fields omitted]"; }
    }

    public record Reject(@NotNull @Min(0) Long expectedRequestVersion, @NotBlank @Size(max=1000) String reason) {
        @JsonAnySetter public void unsupported(String name, Object ignored) { throw new IllegalArgumentException("Unsupported rejection field"); }
        @Override public String toString() { return "QrRejectRequest[private fields omitted]"; }
    }
}
