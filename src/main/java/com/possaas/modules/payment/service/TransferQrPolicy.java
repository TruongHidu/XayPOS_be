package com.possaas.modules.payment.service;

import com.possaas.common.exception.BusinessException;
import java.math.BigDecimal;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** What a customer-facing transfer QR may carry: whole Vietnamese đồng and a short, matchable content. */
@Component
public class TransferQrPolicy {
    static final String CONTENT_PREFIX = "TT ";
    static final int CODE_CHARS = 10;

    /** VietQR transfers are in VND only, and banks take whole đồng. */
    public long amountInDong(String currencyCode, BigDecimal total) {
        if (!"VND".equalsIgnoreCase(currencyCode))
            throw unsupported("Transfer QR supports VND orders only");
        try {
            return total.stripTrailingZeros().longValueExact();
        } catch (ArithmeticException fractional) {
            throw unsupported("Transfer QR needs a whole-đồng total");
        }
    }

    /**
     * "TT " and the last characters of the order code, e.g. OD-…1A2B3C4D5E → "TT 1A2B3C4D5E": short enough
     * for staff to spot in a bank notification and within the VietQR purpose limit.
     */
    public String transferContent(String orderCode) {
        String plain = orderCode == null ? "" : orderCode.replaceAll("[^0-9A-Za-z]", "").toUpperCase(Locale.ROOT);
        if (plain.isEmpty()) throw new IllegalArgumentException("Order code has no letters or digits");
        // At most 3 + 10 characters, well within VietQrPayloadBuilder.MAX_PURPOSE_LENGTH.
        return CONTENT_PREFIX + plain.substring(Math.max(0, plain.length() - CODE_CHARS));
    }

    private static BusinessException unsupported(String message) {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "TRANSFER_QR_CURRENCY_UNSUPPORTED", message);
    }
}
