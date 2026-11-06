package com.possaas.infrastructure.subscriptionpayment.sepay;

import com.possaas.modules.subscriptionpurchase.application.port.PlatformAccountRegistry;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Single configured receiving account; an attempt frozen under another key no longer has a trusted account. */
@Component
@RequiredArgsConstructor
public class SepayPlatformAccountRegistry implements PlatformAccountRegistry {
    private final SepayProperties properties;

    @Override
    public Optional<String> accountNumber(String providerAccountKey) {
        if (providerAccountKey == null || !properties.isConfigured() || !providerAccountKey.equals(properties.getProviderAccountKey()))
            return Optional.empty();
        return Optional.of(properties.getAccountNumber());
    }
}
