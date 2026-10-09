package com.possaas.modules.order.service;

import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class OrderCodeGenerator {
    public String generate() { return "OD-"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT); }
}
