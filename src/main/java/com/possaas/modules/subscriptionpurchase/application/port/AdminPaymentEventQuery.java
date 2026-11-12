package com.possaas.modules.subscriptionpurchase.application.port;

import com.possaas.modules.subscription.dto.PageResponse;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.PaymentEventSummary;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.ReviewCounts;
import com.possaas.modules.subscriptionpurchase.entity.PaymentEventState;
import com.possaas.modules.subscriptionpurchase.entity.ReviewReason;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Cross-tenant read model of received bank transfers; never exposes raw payloads, hashes or idempotency keys. */
public interface AdminPaymentEventQuery {
    PageResponse<PaymentEventSummary> search(PaymentEventState state, ReviewReason reason, UUID restaurantId, String q,
        int page, int size);

    Optional<PaymentEventSummary> find(UUID eventId);

    List<PaymentEventSummary> findByPurchase(UUID purchaseId);

    ReviewCounts reviewCounts();
}
