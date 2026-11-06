package com.possaas.modules.subscriptionpurchase.service;

import static org.assertj.core.api.Assertions.*;

import com.possaas.infrastructure.payment.vietqr.VietQrPayloadBuilder;
import java.util.HashSet;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PaymentReferenceGeneratorTest {
    @Test void referenceFitsDatabaseFormatAndQrPurposeField() {
        var generator = new PaymentReferenceGenerator();
        var seen = new HashSet<String>();
        for (int i = 0; i < 1000; i++) {
            String reference = generator.next();
            assertThat(reference).matches(PaymentReferenceGenerator.FORMAT).hasSizeLessThanOrEqualTo(VietQrPayloadBuilder.MAX_PURPOSE_LENGTH);
            seen.add(reference);
        }
        assertThat(seen).hasSize(1000);
    }

    @Test void usesInjectedRandomSource() {
        assertThat(new PaymentReferenceGenerator(new Random(7)).next()).isEqualTo(new PaymentReferenceGenerator(new Random(7)).next());
    }
}
