package com.possaas.modules.subscriptionpurchase.service;

import com.possaas.modules.subscriptionpurchase.application.port.PaymentInstructionProvider;
import com.possaas.modules.subscriptionpurchase.config.PurchaseProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Single answer to "may owners start a new payment now?" shared by commands and UI hints. */
@Component @RequiredArgsConstructor
public class PaymentAttemptAvailability {
    private final PurchaseProperties settings;
    private final PaymentInstructionProvider provider;

    public boolean acceptingNew() { return settings.isAcceptNew(); }
    public boolean providerReady() { return provider.isReady(); }
    public boolean available() { return acceptingNew() && providerReady(); }
}
