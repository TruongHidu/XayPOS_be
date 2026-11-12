package com.possaas.modules.subscriptionpurchase.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.subscription.dto.PageResponse;
import com.possaas.modules.subscriptionpurchase.application.port.AdminPurchaseQuery;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.PurchaseDetail;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.PurchaseSummary;
import com.possaas.modules.subscriptionpurchase.entity.PurchaseStatus;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminPurchaseQueryService {
    private final AdminPurchaseQuery purchases;

    public PageResponse<PurchaseSummary> search(PurchaseStatus status, UUID restaurantId, String q, int page, int size) {
        return purchases.search(status, restaurantId, q, page, size);
    }

    public PurchaseDetail detail(UUID purchaseId) {
        return purchases.findDetail(purchaseId)
            .orElseThrow(() -> new ResourceNotFoundException("PURCHASE_NOT_FOUND", "Purchase not found"));
    }
}
