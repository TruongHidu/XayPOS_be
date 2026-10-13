package com.possaas.modules.payment.service;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.order.application.port.OrderPaymentCommand;
import com.possaas.modules.payment.entity.Payment;
import com.possaas.modules.payment.entity.PaymentConfirmationSource;
import com.possaas.modules.payment.entity.PaymentMethod;
import com.possaas.modules.payment.entity.PaymentStatus;
import com.possaas.modules.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Common settlement boundary. Only trusted, already-confirmed payment flows may call settleFull. */
@Service @RequiredArgsConstructor
public class PaymentSettlementService {
    private final OrderPaymentCommand orderPayments;
    private final PaymentRepository payments;
    private final PaymentCodeGenerator codes;
    private final PaymentAudit audit;

    @Transactional(propagation = Propagation.MANDATORY)
    public Payment recordManual(CurrentUser actor, UUID orderId, long expectedOrderVersion,
                                ManualPaymentPolicy.ManualPayment request, String key,
                                String fingerprint, Instant now, String ip) {
        return settleFull(new VerifiedSettlement(actor.restaurantId(), orderId, expectedOrderVersion,
            request.amount(), request.method(), PaymentConfirmationSource.STAFF_MANUAL,
            null, null, request.referenceNo(), key, fingerprint, actor.userId(), now, ip));
    }

    /** Future verified provider adapter uses this after authentication, intent and amount reconciliation. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Payment settleFull(VerifiedSettlement instruction) {
        var order = orderPayments.settleFull(instruction.restaurantId(), instruction.orderId(),
            instruction.expectedOrderVersion(), instruction.amount(), instruction.now());
        Payment payment = new Payment();
        payment.setRestaurantId(instruction.restaurantId());
        payment.setOrderId(instruction.orderId());
        payment.setPaymentCode(codes.next());
        payment.setAmount(order.amount());
        payment.setCurrencyCode(order.currencyCode());
        payment.setPaymentMethod(instruction.method());
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setConfirmationSource(instruction.source());
        payment.setProvider(instruction.provider());
        payment.setProviderTransactionId(instruction.providerTransactionId());
        payment.setReferenceNo(instruction.referenceNo());
        payment.setIdempotencyKey(instruction.idempotencyKey());
        payment.setRequestFingerprint(instruction.requestFingerprint());
        payment.setRequestedAt(instruction.now());
        payment.setPaidAt(instruction.now());
        payment.setCreatedBy(instruction.actorId());
        payment.setCreatedAt(instruction.now());
        payment.setUpdatedAt(instruction.now());
        payments.saveAndFlush(payment);
        audit.recorded(payment, instruction.ip());
        payments.flush();
        return payment;
    }

    public record VerifiedSettlement(UUID restaurantId, UUID orderId, long expectedOrderVersion,
                                     BigDecimal amount, PaymentMethod method,
                                     PaymentConfirmationSource source, String provider,
                                     String providerTransactionId, String referenceNo,
                                     String idempotencyKey, String requestFingerprint,
                                     UUID actorId, Instant now, String ip) {}
}
