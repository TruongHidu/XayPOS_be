package com.possaas.modules.subscriptionpurchase.application.port;

import com.possaas.modules.subscriptionpurchase.dto.PaymentInstructions;

/** providerAccountKey is the trusted config id of the receiving account, never a secret. */
public record PreparedPaymentInstructions(String provider, String providerAccountKey, PaymentInstructions instructions) {}
