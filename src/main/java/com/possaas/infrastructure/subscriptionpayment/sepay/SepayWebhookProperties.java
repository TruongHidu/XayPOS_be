package com.possaas.infrastructure.subscriptionpayment.sepay;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** HMAC secret is created in the SePay webhook wizard; an empty secret rejects every delivery. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.sepay.webhook")
public class SepayWebhookProperties {
    private String secret;
    private Duration timestampTolerance = Duration.ofMinutes(5);
    private int maxBodyBytes = 65536;
}
