package com.possaas.infrastructure.subscriptionpayment.sepay;

import static org.assertj.core.api.Assertions.*;

import com.possaas.infrastructure.payment.vietqr.VietQrPayloadBuilder;
import com.possaas.modules.subscriptionpurchase.application.port.PaymentInstructionProvider;
import com.possaas.modules.subscriptionpurchase.application.port.PaymentInstructionRequest;
import com.possaas.modules.subscriptionpurchase.application.port.PaymentProviderUnavailableException;
import org.junit.jupiter.api.Test;

class SepayPaymentInstructionProviderTest {
    private static final String REFERENCE = "PS0123456789ABCDEF0123";

    @Test void preparesOfflineInstructionsForThePlatformAccount() {
        PaymentInstructionProvider provider = provider(configured());
        assertThat(provider.isReady()).isTrue();
        var prepared = provider.prepare(new PaymentInstructionRequest(REFERENCE, 399000, "VND"));
        assertThat(prepared.provider()).isEqualTo("SEPAY");
        assertThat(prepared.providerAccountKey()).isEqualTo("platform-demo");
        var instructions = prepared.instructions();
        assertThat(instructions.transferContent()).isEqualTo(REFERENCE);
        assertThat(instructions.bankCode()).isEqualTo("MB Bank");
        assertThat(instructions.accountNumber()).isEqualTo("0123456789");
        assertThat(instructions.accountName()).isEqualTo("POS SAAS DEMO");
        assertThat(instructions.qrPayload()).contains("5406399000").contains("62260822" + REFERENCE);
        assertThat(instructions.qrImageUrl()).isEqualTo("https://qr.sepay.vn/img?acc=0123456789&bank=MB+Bank&amount=399000&des="
            + REFERENCE + "&template=compact");
    }

    @Test void missingOrDisabledConfigurationIsUnavailable() {
        SepayProperties disabled = configured();
        disabled.setEnabled(false);
        SepayProperties incomplete = configured();
        incomplete.setBankBin("MB");
        for (SepayProperties properties : new SepayProperties[] {disabled, incomplete, new SepayProperties()}) {
            var provider = provider(properties);
            assertThat(provider.isReady()).isFalse();
            assertThatThrownBy(() -> provider.prepare(new PaymentInstructionRequest(REFERENCE, 1, "VND")))
                .isInstanceOf(PaymentProviderUnavailableException.class);
        }
    }

    @Test void foreignCurrencyAndUnencodableReferenceAreUnavailable() {
        var provider = provider(configured());
        assertThatThrownBy(() -> provider.prepare(new PaymentInstructionRequest(REFERENCE, 1, "USD")))
            .isInstanceOf(PaymentProviderUnavailableException.class);
        assertThatThrownBy(() -> provider.prepare(new PaymentInstructionRequest(REFERENCE + "0000", 1, "VND")))
            .isInstanceOf(PaymentProviderUnavailableException.class);
    }

    private static PaymentInstructionProvider provider(SepayProperties properties) {
        return new SepayPaymentInstructionProvider(properties, new VietQrPayloadBuilder());
    }

    private static SepayProperties configured() {
        SepayProperties properties = new SepayProperties();
        properties.setEnabled(true);
        properties.setProviderAccountKey("platform-demo");
        properties.setBankCode("MB Bank");
        properties.setBankBin("970422");
        properties.setAccountNumber("0123456789");
        properties.setAccountName("POS SAAS DEMO");
        return properties;
    }
}
