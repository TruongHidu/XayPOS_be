package com.possaas.modules.subscriptionpurchase.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component @ConfigurationProperties("subscription-purchase") @Getter @Setter
public class PurchaseProperties {
    private boolean acceptNew = false;
}
