package com.possaas.modules.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.entity.TransferAccount;
import org.junit.jupiter.api.Test;

class TransferAccountTest {
    @Test void normalisesWhatTheOwnerTyped() {
        var account = TransferAccount.of(" 970436 ", "0123 4567 89", "  Nguyễn   Văn Đức ");
        assertThat(account.bankBin()).isEqualTo("970436");
        assertThat(account.accountNumber()).isEqualTo("0123456789");
        assertThat(account.accountName()).isEqualTo("NGUYEN VAN DUC");
    }

    @Test void rejectsValuesAVietQrCodeCannotCarry() {
        for (String[] bad : new String[][] {
            {"97043", "0123456789", "QUAN AN"},          // BIN too short
            {"97043A", "0123456789", "QUAN AN"},         // BIN not digits
            {"970436", "0123-456", "QUAN AN"},           // symbol in number
            {"970436", "12345678901234567890", "QUAN AN"}, // 20 characters
            {"970436", "0123456789", "QUÁN @N"},         // symbol in name
            {"970436", "0123456789", "A".repeat(51)},    // name too long
            {"970436", "0123456789", "   "},             // blank name
            {null, "0123456789", "QUAN AN"},
        }) {
            assertThatThrownBy(() -> TransferAccount.of(bad[0], bad[1], bad[2]))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVALID_TRANSFER_ACCOUNT");
        }
    }

    @Test void masksTheNumberForAudit() {
        assertThat(TransferAccount.of("970436", "0123456789", "A").maskedNumber()).isEqualTo("••••6789");
        assertThat(TransferAccount.of("970436", "12", "A").maskedNumber()).isEqualTo("••••12");
    }

    @Test void restaurantKeepsTheAccountWholeOrNotAtAll() {
        var restaurant = new Restaurant();
        assertThat(restaurant.transferAccount()).isEmpty();
        var account = TransferAccount.of("970436", "0123456789", "QUAN AN");
        restaurant.changeTransferAccount(account);
        assertThat(restaurant.transferAccount()).contains(account);
        restaurant.changeTransferAccount(null);
        assertThat(restaurant.transferAccount()).isEmpty();
    }
}
