package com.possaas.modules.subscriptionpurchase.application.port;

/** Whole VND amount; reference is the server-generated transfer content. */
public record PaymentInstructionRequest(String referenceCode, long amount, String currencyCode) {}
