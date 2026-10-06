package com.possaas.modules.table.service;

import com.possaas.common.exception.BusinessException;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class TableValidationPolicy {
    public String required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw invalid("Invalid text value");
        return value.trim();
    }

    public String optional(String value, int max) {
        if (value == null) return null;
        if (value.length() > max) throw invalid("Text is too long");
        return value.isBlank() ? null : value.trim();
    }

    public String code(String value) {
        return required(required(value, 50).toUpperCase(Locale.ROOT), 50);
    }

    public short positiveShort(Integer value, int defaultValue) {
        int number = value == null ? defaultValue : value;
        if (number < 1 || number > Short.MAX_VALUE) throw invalid("Value must be between 1 and 32767");
        return (short) number;
    }

    public int displayOrder(Integer value) {
        if (value != null && value < 0) throw invalid("displayOrder must not be negative");
        return value == null ? 0 : value;
    }

    public static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    public void requireChanges(boolean hasChanges) {
        if (!hasChanges) throw new BusinessException(HttpStatus.BAD_REQUEST, "EMPTY_UPDATE_REQUEST", "No fields provided");
    }
}
