package com.possaas.modules.qrorder.service;

import com.possaas.common.exception.*;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.order.application.port.QrOrderCheckoutQuery;
import com.possaas.modules.order.entity.OrderStatus;
import com.possaas.modules.order.service.OrderSessionServingPolicy;
import com.possaas.modules.qrorder.dto.QrStaffRequestDetail;
import com.possaas.modules.qrorder.entity.*;
import com.possaas.modules.table.application.port.QrOrderSeatingQuery;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Read-only, reproducible decision context shared by GET review and approval under the tenant lock. */
@Component @RequiredArgsConstructor
public class QrReviewContextService {
    private final QrScopePolicy scopes;
    private final QrQuoteService quotes;
    private final QrOrderSeatingQuery seating;
    private final OrderSessionServingPolicy serving;
    private final QrOrderCheckoutQuery checkout;
    private final QrHashes hashes;

    /**
     * Observation translates read-only port refusals into UI state. Suspend its caller transaction:
     * a transactional entitlement/menu checker may mark a joined transaction rollback-only even
     * when its business exception is intentionally caught here. Approval calls require directly
     * and therefore keeps every authoritative check and write in its single locked transaction.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
    public Review inspect(QrOrderRequest request, List<QrOrderRequestItem> items, CurrentUser actor, Instant now) {
        try { return require(request, items, actor, now); }
        catch (BusinessException failure) { return new Review(null, null, false, failure.getCode(), null); }
        catch (AccessDeniedException failure) { return new Review(null, null, false, "FORBIDDEN", null); }
    }
    public Review require(QrOrderRequest request, List<QrOrderRequestItem> items, CurrentUser actor, Instant now) {
        if (request.getStatus() != QrRequestStatus.PENDING)
            throw new ConflictException("INVALID_QR_REQUEST_TRANSITION", "Only pending requests can be approved");
        if (!request.getExpiresAt().isAfter(now))
            throw new ConflictException("QR_ORDER_REQUEST_EXPIRED", "QR request expired");
        var scope = scopes.validate(request);
        if (!scope.restaurant().getCurrencyCode().equals(request.getCurrencyCode()))
            throw new ConflictException("ORDER_CURRENCY_MISMATCH", "Restaurant currency changed; submit a new request");
        var quote = quotes.quote(request.getRestaurantId(), scope.restaurant().getCurrencyCode(), items);
        QrStaffRequestDetail.ReviewContext context = null;
        if (request.getEntryType() == QrEntryType.TABLE) {
            var current = seating.current(request.getRestaurantId(), request.getTableId()).orElse(null);
            UUID sessionId = current == null ? null : current.sessionId();
            if (request.getObservedSessionId() != null && !request.getObservedSessionId().equals(sessionId))
                throw new ConflictException("QR_REQUEST_SESSION_CHANGED", "Original seating changed; submit a new request");
            QrStaffRequestDetail.ServingOrder orderState = null;
            if (sessionId != null) {
                checkout.requireAccepting(request.getRestaurantId(), sessionId);
                var root = serving.findServing(request.getRestaurantId(), sessionId).orElse(null);
                if (root != null) {
                    if (root.getStatus() == OrderStatus.OPEN)
                        throw new ConflictException("QR_ORDER_DRAFT_NOT_SUBMITTED", "Submit the staff draft before approving QR additions");
                    orderState = new QrStaffRequestDetail.ServingOrder(root.getId(), root.getVersion(), root.getStatus(),
                        root.getPaymentStatus(), root.getCurrencyCode(), root.getTotalAmount(), root.getPaidAmount());
                }
            }
            context = new QrStaffRequestDetail.ReviewContext(sessionId, scope.epoch(),
                current == null ? null : current.version(), current == null ? null : current.guestCount(), orderState);
            if (sessionId == null && !actor.hasPermission("TABLE_OPEN"))
                throw new AccessDeniedException("TABLE_OPEN is required to approve seating creation");
        }
        boolean allowed = actor.hasPermission("ORDER_CREATE") && actor.hasPermission("ORDER_UPDATE");
        var fingerprint = new CanonicalReview("qr-staff-review-v1", request.getId(), request.getVersion(), request.getPayloadHash(),
            request.getEntryType(), request.getTableId(), request.getObservedSessionId(), request.getTableAccessEpoch(),
            request.getCredentialHash(), true, request.getExpiresAt(), quote, context);
        return new Review(quote, hashes.hash(fingerprint), allowed, allowed ? null : "FORBIDDEN", context);
    }
    public record Review(QrQuoteService.QrQuote quote, String hash, boolean canApprove, String reasonCode,
            QrStaffRequestDetail.ReviewContext context) {}
    private record CanonicalReview(String operation, UUID requestId, long requestVersion, String payloadHash,
            QrEntryType entryType, UUID tableId, UUID observedSessionId, Long tableAccessEpoch,
            String credentialHash, boolean credentialValid, Instant expiresAt, QrQuoteService.QrQuote quote,
            QrStaffRequestDetail.ReviewContext seating) {}
}
