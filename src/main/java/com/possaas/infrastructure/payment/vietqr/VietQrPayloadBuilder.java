package com.possaas.infrastructure.payment.vietqr;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Offline NAPAS VietQR (EMVCo merchant-presented) payload for a bank-account transfer with fixed amount and content.
 * Provider-neutral: it only encodes TLV fields and the CRC16-CCITT checksum.
 */
@Component
public class VietQrPayloadBuilder {
    public static final int MAX_PURPOSE_LENGTH = 25;
    private static final String NAPAS_GUID = "A000000727";
    private static final String TRANSFER_TO_ACCOUNT = "QRIBFTTA";

    public String accountTransfer(String bankBin, String accountNumber, long amount, String purpose) {
        if (bankBin == null || !bankBin.matches("\\d{6}")) throw new IllegalArgumentException("Bank BIN must be 6 digits");
        if (accountNumber == null || !accountNumber.matches("[0-9A-Za-z]{1,19}")) throw new IllegalArgumentException("Invalid account number");
        if (amount <= 0 || amount > 999_999_999_999L) throw new IllegalArgumentException("Invalid amount");
        if (purpose == null || !purpose.matches("[0-9A-Za-z ]{1," + MAX_PURPOSE_LENGTH + "}"))
            throw new IllegalArgumentException("Purpose must be 1-" + MAX_PURPOSE_LENGTH + " ASCII letters or digits");
        String beneficiary = tlv("00", bankBin) + tlv("01", accountNumber);
        String merchant = tlv("00", NAPAS_GUID) + tlv("01", beneficiary) + tlv("02", TRANSFER_TO_ACCOUNT);
        String body = tlv("00", "01") + tlv("01", "12") + tlv("38", merchant) + tlv("53", "704")
            + tlv("54", Long.toString(amount)) + tlv("58", "VN") + tlv("62", tlv("08", purpose)) + "6304";
        return body + crc16(body);
    }

    /** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF) as required by EMVCo tag 63. */
    static String crc16(String data) {
        int crc = 0xFFFF;
        for (byte b : data.getBytes(StandardCharsets.US_ASCII)) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
            crc &= 0xFFFF;
        }
        return String.format(Locale.ROOT, "%04X", crc);
    }

    private static String tlv(String id, String value) {
        if (value.length() > 99) throw new IllegalArgumentException("EMVCo field " + id + " exceeds 99 characters");
        return id + String.format(Locale.ROOT, "%02d", value.length()) + value;
    }
}
