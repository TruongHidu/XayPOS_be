package com.possaas.infrastructure.subscriptionpayment.sepay;

import com.possaas.infrastructure.payment.vietqr.VietQrPayloadBuilder;
import com.possaas.modules.subscriptionpurchase.application.port.PaymentInstructionProvider;
import com.possaas.modules.subscriptionpurchase.application.port.PaymentInstructionRequest;
import com.possaas.modules.subscriptionpurchase.application.port.PaymentProviderUnavailableException;
import com.possaas.modules.subscriptionpurchase.application.port.PreparedPaymentInstructions;
import com.possaas.modules.subscriptionpurchase.dto.PaymentInstructions;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Bank transfer to the platform account tracked by SePay; QR data is built offline, no HTTP call. */
@Component
@RequiredArgsConstructor
public class SepayPaymentInstructionProvider implements PaymentInstructionProvider {
    public static final String PROVIDER = "SEPAY";
    private final SepayProperties properties;
    private final VietQrPayloadBuilder vietQr;

    @Override
    public boolean isReady() { return properties.isEnabled() && properties.isConfigured(); }

    @Override
    public PreparedPaymentInstructions prepare(PaymentInstructionRequest request) {
        if (!isReady()) throw new PaymentProviderUnavailableException("SePay receiving account is not configured");
        if (!"VND".equals(request.currencyCode())) throw new PaymentProviderUnavailableException("SePay accepts VND only");
        String reference = request.referenceCode();
        try {
            String payload = vietQr.accountTransfer(properties.getBankBin(), properties.getAccountNumber(), request.amount(), reference);
            String imageUrl = properties.getQrImageBaseUrl() + "?acc=" + encode(properties.getAccountNumber())
                + "&bank=" + encode(properties.getBankCode()) + "&amount=" + request.amount()
                + "&des=" + encode(reference) + "&template=" + encode(properties.getQrTemplate());
            var instructions = new PaymentInstructions(properties.getBankCode(), properties.getAccountNumber(),
                properties.getAccountName(), reference, payload, imageUrl);
            return new PreparedPaymentInstructions(PROVIDER, properties.getProviderAccountKey(), instructions);
        } catch (IllegalArgumentException invalid) {
            throw new PaymentProviderUnavailableException("SePay payment instructions cannot be prepared", invalid);
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
