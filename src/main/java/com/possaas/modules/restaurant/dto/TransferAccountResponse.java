package com.possaas.modules.restaurant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.possaas.modules.restaurant.entity.TransferAccount;
import java.util.Optional;

/** {@code configured=false} with null fields when the restaurant has no receiving account yet. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TransferAccountResponse(boolean configured, String bankBin, String accountNumber, String accountName) {
    public static TransferAccountResponse from(Optional<TransferAccount> account) {
        return account
            .map(a -> new TransferAccountResponse(true, a.bankBin(), a.accountNumber(), a.accountName()))
            .orElse(new TransferAccountResponse(false, null, null, null));
    }
}
