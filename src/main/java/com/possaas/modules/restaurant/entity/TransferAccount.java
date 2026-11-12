package com.possaas.modules.restaurant.entity;

import com.possaas.common.exception.BusinessException;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * The bank account a restaurant receives customer transfers on. Values are normalised so they fit a NAPAS
 * VietQR code as-is: banking apps show the holder name upper-case and without Vietnamese marks.
 */
public record TransferAccount(String bankBin, String accountNumber, String accountName) {
    public static final int MAX_NAME_LENGTH = 50;
    private static final Pattern BIN = Pattern.compile("\\d{6}");
    private static final Pattern NUMBER = Pattern.compile("[0-9A-Za-z]{1,19}");
    private static final Pattern NAME = Pattern.compile("[A-Z0-9 ]{1," + MAX_NAME_LENGTH + "}");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    public TransferAccount {
        if (bankBin == null || !BIN.matcher(bankBin).matches()) throw invalid("bankBin");
        if (accountNumber == null || !NUMBER.matcher(accountNumber).matches()) throw invalid("accountNumber");
        if (accountName == null || !NAME.matcher(accountName).matches()) throw invalid("accountName");
    }

    /** Builds an account from what the owner typed, normalising spacing, marks and case. */
    public static TransferAccount of(String bankBin, String accountNumber, String accountName) {
        return new TransferAccount(
            bankBin == null ? null : bankBin.trim(),
            accountNumber == null ? null : SPACES.matcher(accountNumber).replaceAll(""),
            normalizeName(accountName));
    }

    static String normalizeName(String name) {
        if (name == null) return null;
        String plain = Normalizer.normalize(name.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD);
        plain = MARKS.matcher(plain).replaceAll("");
        return SPACES.matcher(plain.trim()).replaceAll(" ").toUpperCase(Locale.ROOT);
    }

    /** For audit and logs: never the full number. */
    public String maskedNumber() {
        int keep = Math.min(4, accountNumber.length());
        return "••••" + accountNumber.substring(accountNumber.length() - keep);
    }

    private static BusinessException invalid(String field) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TRANSFER_ACCOUNT",
            "Invalid transfer account field: " + field);
    }
}
