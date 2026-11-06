package com.possaas.modules.payment.service;

import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PaymentCodeGenerator {
    public String next() {
        return "PAY-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    }
}
