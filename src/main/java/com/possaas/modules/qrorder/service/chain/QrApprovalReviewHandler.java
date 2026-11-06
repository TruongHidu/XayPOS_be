package com.possaas.modules.qrorder.service.chain;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.order.service.chain.*;
import com.possaas.modules.qrorder.dto.QrApprovalResponse;
import com.possaas.modules.qrorder.service.QrReviewContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class QrApprovalReviewHandler implements OrderHandler<QrApprovalContext,QrApprovalResponse> {
    private final QrReviewContextService review;
    @Override public QrApprovalResponse handle(QrApprovalContext c, OrderNext<QrApprovalContext,QrApprovalResponse> next) {
        var current = review.require(c.getRequest(), c.getItems(), c.getLocked().actor(), c.getNow());
        if (!current.hash().equals(c.getCommand().getExpectedReviewContextHash()))
            throw new ConflictException("QR_REVIEW_CONTEXT_CHANGED", "Quote or seating changed; review again before approving");
        c.setReview(current);
        return next.proceed(c);
    }
}
