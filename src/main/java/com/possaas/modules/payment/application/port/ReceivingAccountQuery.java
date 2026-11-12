package com.possaas.modules.payment.application.port;

import java.util.Optional;
import java.util.UUID;

/** Where a restaurant receives customer bank transfers; implemented by the module that owns the setting. */
public interface ReceivingAccountQuery {
    Optional<ReceivingAccount> find(UUID restaurantId);

    record ReceivingAccount(String bankBin, String accountNumber, String accountName) {}
}
