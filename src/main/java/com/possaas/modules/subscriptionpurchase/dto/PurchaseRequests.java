package com.possaas.modules.subscriptionpurchase.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class PurchaseRequests {
    private PurchaseRequests() {}

    public record Create(
        @NotBlank @Size(max = 50) @Pattern(regexp = "\\s*[A-Za-z0-9_-]+\\s*") String packageCode
    ) {
        @JsonAnySetter public void unsupported(String name, Object value) {
            throw new IllegalArgumentException("Unsupported purchase field");
        }
    }

    /** Body is always {}; the purchase comes from the path and price/account never come from the client. */
    public record CreateAttempt() {
        @JsonAnySetter public void unsupported(String name, Object value) {
            throw new IllegalArgumentException("Unsupported payment attempt field");
        }
    }

    public record Cancel(@NotNull @Min(0) Long expectedVersion, @Size(min = 1, max = 500) String reason) {
        @JsonAnySetter public void unsupported(String name, Object value) {
            throw new IllegalArgumentException("Unsupported cancellation field");
        }
    }
}
