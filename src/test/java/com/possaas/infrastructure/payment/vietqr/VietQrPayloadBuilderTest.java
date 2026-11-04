package com.possaas.infrastructure.payment.vietqr;

import static org.assertj.core.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VietQrPayloadBuilderTest {
    private final VietQrPayloadBuilder builder = new VietQrPayloadBuilder();

    @Test void crcMatchesCcittFalseCheckValue() {
        assertThat(VietQrPayloadBuilder.crc16("123456789")).isEqualTo("29B1");
    }

    @Test void encodesNapasAccountTransferWithAmountAndPurpose() {
        String payload = builder.accountTransfer("970422", "0123456789", 399000, "PS0123456789ABCDEF0123");
        Map<String, String> root = parse(payload);
        assertThat(root).containsEntry("00", "01").containsEntry("01", "12").containsEntry("53", "704")
            .containsEntry("54", "399000").containsEntry("58", "VN");
        Map<String, String> merchant = parse(root.get("38"));
        assertThat(merchant).containsEntry("00", "A000000727").containsEntry("02", "QRIBFTTA");
        assertThat(parse(merchant.get("01"))).containsEntry("00", "970422").containsEntry("01", "0123456789");
        assertThat(parse(root.get("62"))).containsEntry("08", "PS0123456789ABCDEF0123");
        assertThat(payload).endsWith("6304" + root.get("63"));
        assertThat(root.get("63")).isEqualTo(VietQrPayloadBuilder.crc16(payload.substring(0, payload.length() - 4)));
    }

    @ParameterizedTest @ValueSource(strings = {"PS0123456789ABCDEF01234567", "PS-0123", ""})
    void rejectsPurposeThatBanksWouldTruncateOrMangle(String purpose) {
        assertThatThrownBy(() -> builder.accountTransfer("970422", "0123456789", 1, purpose)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsInvalidBankAccountAndAmount() {
        assertThatThrownBy(() -> builder.accountTransfer("97042", "0123", 1, "PS1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.accountTransfer("970422", "01-23", 1, "PS1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.accountTransfer("970422", "0123", 0, "PS1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.accountTransfer("970422", "0123", 1_000_000_000_000L, "PS1")).isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, String> parse(String tlv) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i < tlv.length(); ) {
            String id = tlv.substring(i, i + 2);
            int length = Integer.parseInt(tlv.substring(i + 2, i + 4));
            fields.put(id, tlv.substring(i + 4, i + 4 + length));
            i += 4 + length;
        }
        return fields;
    }
}
