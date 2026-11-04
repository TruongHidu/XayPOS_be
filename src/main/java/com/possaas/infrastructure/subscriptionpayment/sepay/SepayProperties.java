package com.possaas.infrastructure.subscriptionpayment.sepay;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Platform receiving account linked to SePay. Values come from environment; no secret is needed for QR. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.sepay")
public class SepayProperties {
    private boolean enabled;
    private String providerAccountKey;
    /** Short bank name from qr.sepay.vn/banks.json, e.g. MBBank. */
    private String bankCode;
    /** 6-digit NAPAS BIN used by the VietQR payload. */
    private String bankBin;
    private String accountNumber;
    private String accountName;
    private String qrImageBaseUrl = "https://qr.sepay.vn/img";
    private String qrTemplate = "compact";

    public boolean isConfigured() {
        return StringUtils.hasText(providerAccountKey) && providerAccountKey.length() <= 100
            && StringUtils.hasText(bankCode) && bankCode.length() <= 30
            && bankBin != null && bankBin.matches("\\d{6}")
            && accountNumber != null && accountNumber.matches("[0-9A-Za-z]{1,19}")
            && StringUtils.hasText(accountName) && accountName.length() <= 150
            && qrImageBaseUrl != null && qrImageBaseUrl.startsWith("https://");
    }
}
