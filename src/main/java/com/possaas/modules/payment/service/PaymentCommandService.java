package com.possaas.modules.payment.service;

import com.possaas.modules.payment.dto.PaymentResponse;
import com.possaas.modules.payment.dto.RecordManualPaymentRequest;
import com.possaas.modules.payment.repository.PaymentRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional
public class PaymentCommandService {
    private final PaymentAccessService access;
    private final ManualPaymentPolicy policy;
    private final PaymentIdempotencyService idempotency;
    private final PaymentRepository payments;
    private final PaymentSettlementService settlement;
    private final PaymentResponseMapper mapper;
    private final Clock clock;

    public Result recordManual(UUID orderId, RecordManualPaymentRequest request, String rawKey, String ip) {
        var actor = access.lock();
        var normalized = policy.normalize(request);
        String key = idempotency.key(rawKey);
        String fingerprint = idempotency.fingerprint(actor.userId(), orderId,
            request.expectedOrderVersion(), normalized);
        var previous = payments.findByRestaurantIdAndIdempotencyKey(actor.restaurantId(), key);
        if (previous.isPresent()) {
            idempotency.requireReplayMatch(previous.get(), actor.userId(), fingerprint);
            return new Result(mapper.toResponse(previous.get()), true);
        }
        var payment = settlement.recordManual(actor, orderId, request.expectedOrderVersion(),
            normalized, key, fingerprint, clock.instant().truncatedTo(ChronoUnit.MICROS), ip);
        return new Result(mapper.toResponse(payment), false);
    }

    public record Result(PaymentResponse response, boolean replay) {}
}
