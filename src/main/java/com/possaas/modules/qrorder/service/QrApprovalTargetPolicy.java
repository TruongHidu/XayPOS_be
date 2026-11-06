package com.possaas.modules.qrorder.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.qrorder.entity.*;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** A seating choice is staff intent, not a latest-session lookup or a guest body override. */
@Component
public class QrApprovalTargetPolicy {
    public void require(QrOrderRequest request, UUID target, QrReviewContextService.Review review) {
        if (request.getEntryType() == QrEntryType.RESTAURANT) {
            if (target != null) throw new ConflictException("TAKEAWAY_SESSION_NOT_ALLOWED", "Takeaway cannot use seating");
            return;
        }
        UUID current = review.context().currentSessionId();
        if (!Objects.equals(target, current)) throw changed();
        if (request.getObservedSessionId() != null && !Objects.equals(request.getObservedSessionId(), target)) throw changed();
    }
    private ConflictException changed() {
        return new ConflictException("QR_REQUEST_SESSION_CHANGED", "Explicit target must be the reviewed current session");
    }
}
