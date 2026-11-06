package com.possaas.modules.subscriptionpurchase.application.port;

/**
 * Prepares how the owner pays the platform for one attempt. Implementations never persist, never take locks
 * and must return {@code transferContent == referenceCode}; callers invoke them outside any transaction.
 * Webhook verification and reconciliation are separate ports.
 */
public interface PaymentInstructionProvider {
    boolean isReady();

    /** @throws PaymentProviderUnavailableException when instructions cannot be prepared */
    PreparedPaymentInstructions prepare(PaymentInstructionRequest request);
}
