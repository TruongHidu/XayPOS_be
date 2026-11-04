package com.possaas.infrastructure.subscriptionpayment.sepay;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.subscriptionpurchase.service.PaymentInboxService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.TransactionException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Acknowledges SePay only after the inbox commit; matching and activation run in background workers. */
@Slf4j
@RestController
@RequiredArgsConstructor
public class SepayWebhookController {
    private static final Map<String, Boolean> ACK = Map.of("success", true);
    private final SepayWebhookPayloadMapper payloads;
    private final SepayProperties account;
    private final PaymentInboxService inbox;

    @PostMapping(SepayWebhookAuthenticationFilter.PATH)
    @PreAuthorize("hasAuthority('" + SepayWebhookAuthenticationFilter.AUTHORITY + "')")
    public Map<String, Boolean> receive(HttpServletRequest request) {
        byte[] body = (byte[]) request.getAttribute(SepayWebhookAuthenticationFilter.VERIFIED_BODY);
        if (body == null) throw new BusinessException(HttpStatus.UNAUTHORIZED, "WEBHOOK_AUTHENTICATION_FAILED", "SePay webhook authentication failed");
        if (!StringUtils.hasText(account.getProviderAccountKey())) throw unavailable();
        var event = parse(body);
        try {
            var receipt = inbox.receive(event);
            if (receipt.duplicate()) log.info("Duplicate SePay delivery for event {}", receipt.eventId());
            return ACK;
        } catch (DataAccessException | TransactionException failure) {
            log.error("SePay webhook could not be stored: {}", failure.getClass().getSimpleName());
            throw unavailable();
        }
    }

    private com.possaas.modules.subscriptionpurchase.application.port.VerifiedPaymentEvent parse(byte[] body) {
        try { return payloads.map(body); }
        catch (SepayWebhookPayloadMapper.InvalidPayloadException invalid) {
            log.warn("Signed SePay webhook has an invalid payload: {}", invalid.getMessage());
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY", "Invalid SePay webhook payload");
        }
    }

    private static BusinessException unavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "WEBHOOK_PERSISTENCE_UNAVAILABLE", "Webhook could not be stored; retry later");
    }
}
