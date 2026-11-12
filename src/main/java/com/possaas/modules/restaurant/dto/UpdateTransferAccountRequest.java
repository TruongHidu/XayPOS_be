package com.possaas.modules.restaurant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Whole receiving account; format rules live in {@code TransferAccount}. */
public record UpdateTransferAccountRequest(
    @NotBlank @Size(max = 6) String bankBin,
    @NotBlank @Size(max = 40) String accountNumber,
    @NotBlank @Size(max = 100) String accountName) {
}
