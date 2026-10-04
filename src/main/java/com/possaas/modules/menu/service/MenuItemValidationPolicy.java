package com.possaas.modules.menu.service;

import com.possaas.common.exception.BusinessException;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class MenuItemValidationPolicy {
    public String required(String value, int max) {
        String text = optional(value);
        if (text == null || text.length() > max)
            throw invalid("Invalid required text");
        return text;
    }

    public String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public String sku(String value) {
        String sku = optional(value);
        if (sku == null)
            return null;
        sku = sku.toUpperCase(Locale.ROOT);
        if (sku.length() > 80)
            throw invalid("SKU exceeds 80 characters");
        return sku;
    }

    public BigDecimal price(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.scale() > 2
                || value.compareTo(new BigDecimal("999999999999.99")) > 0)
            throw invalid("Price must fit NUMERIC(14,2) without rounding");
        return value.setScale(2);
    }

    public String image(String value) {
        String url = optional(value);
        if (url == null)
            return null;
        try {
            URI uri = URI.create(url);
            if (url.length() > 500 || uri.getHost() == null || uri.getUserInfo() != null
                    || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw invalid("imageUrl must be an absolute HTTP(S) URL without credentials");
        }
        return url;
    }

    public static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }
}
