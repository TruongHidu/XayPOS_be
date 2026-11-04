package com.possaas.modules.subscriptionpurchase.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component @ConfigurationProperties("subscription-purchase") @Getter @Setter
public class PurchaseProperties {
    private boolean acceptNew = false;
    /** Bank transaction times have second precision and drift; tolerated on both ends of the payable window. */
    private Duration paymentClockSkew = Duration.ofMinutes(2);
}
