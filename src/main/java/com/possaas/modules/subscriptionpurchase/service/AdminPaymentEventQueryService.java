package com.possaas.modules.subscriptionpurchase.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.subscription.dto.PageResponse;
import com.possaas.modules.subscriptionpurchase.application.port.AdminPaymentEventQuery;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.PaymentEventSummary;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.ReviewCounts;
import com.possaas.modules.subscriptionpurchase.entity.PaymentEventState;
import com.possaas.modules.subscriptionpurchase.entity.ReviewReason;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminPaymentEventQueryService {
    private final AdminPaymentEventQuery events;

    public PageResponse<PaymentEventSummary> search(PaymentEventState state, ReviewReason reason, UUID restaurantId, String q,
            int page, int size) {
        return events.search(state, reason, restaurantId, q, page, size);
    }

    public PaymentEventSummary detail(UUID eventId) {
        return events.find(eventId)
            .orElseThrow(() -> new ResourceNotFoundException("PAYMENT_EVENT_NOT_FOUND", "Payment event not found"));
    }

    public ReviewCounts reviewCounts() { return events.reviewCounts(); }
}
