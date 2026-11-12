package com.possaas.modules.subscriptionpurchase.application.port;

import com.possaas.modules.subscription.dto.PageResponse;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.PurchaseDetail;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.PurchaseSummary;
import com.possaas.modules.subscriptionpurchase.entity.PurchaseStatus;
import java.util.Optional;
import java.util.UUID;

/** Cross-tenant read model of package purchases for super admins. */
public interface AdminPurchaseQuery {
    PageResponse<PurchaseSummary> search(PurchaseStatus status, UUID restaurantId, String q, int page, int size);

    Optional<PurchaseDetail> findDetail(UUID purchaseId);
}
