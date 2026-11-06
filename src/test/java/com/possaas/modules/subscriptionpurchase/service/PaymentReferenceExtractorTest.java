package com.possaas.modules.subscriptionpurchase.service;

import static org.assertj.core.api.Assertions.*;

import com.possaas.modules.subscriptionpurchase.service.PaymentReferenceExtractor.Outcome;
import org.junit.jupiter.api.Test;

class PaymentReferenceExtractorTest {
    private static final String REF = "PS0123456789ABCDEF0123";
    private final PaymentReferenceExtractor extractor = new PaymentReferenceExtractor();

    @Test void matchesExactTokenWithBankDecorationsAndLowercase() {
        assertThat(extractor.extract(null, REF).reference()).isEqualTo(REF);
        assertThat(extractor.extract(REF, "MBVCB.123456." + REF + ".CT tu 0123 NGUYEN VAN A").reference()).isEqualTo(REF);
        assertThat(extractor.extract(null, REF.toLowerCase() + " chuyen khoan").reference()).isEqualTo(REF);
    }

    @Test void trustsProviderCodeOnlyWhenContentContainsIt() {
        assertThat(extractor.extract(REF, REF + "FT26306123").outcome()).isEqualTo(Outcome.MATCHED);
        assertThat(extractor.extract(REF, "thanh toan goi PRO").outcome()).isEqualTo(Outcome.UNMATCHED);
    }

    @Test void rejectsGluedLegacyAndMissingReferences() {
        assertThat(extractor.extract(null, "X" + REF).outcome()).isEqualTo(Outcome.UNMATCHED);
        assertThat(extractor.extract(null, "PS0123456789ABCDEF01234567").outcome()).isEqualTo(Outcome.UNMATCHED);
        assertThat(extractor.extract(null, null).outcome()).isEqualTo(Outcome.UNMATCHED);
        assertThat(extractor.extract("PS123", "PS123").outcome()).isEqualTo(Outcome.UNMATCHED);
    }

    @Test void twoDifferentReferencesAreAmbiguous() {
        var other = "PSAAAAAAAAAAAAAAAAAAAA";
        assertThat(extractor.extract(null, REF + " " + other).outcome()).isEqualTo(Outcome.AMBIGUOUS);
        assertThat(extractor.extract(null, REF + " " + REF).outcome()).isEqualTo(Outcome.MATCHED);
    }
}
