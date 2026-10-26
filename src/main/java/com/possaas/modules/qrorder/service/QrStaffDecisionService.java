package com.possaas.modules.qrorder.service;

import com.possaas.modules.qrorder.event.QrOrderRequestChanged.Change;
import com.possaas.common.exception.ConflictException;
import com.possaas.modules.order.service.chain.OrderChain;
import com.possaas.modules.qrorder.dto.*;
import com.possaas.modules.qrorder.entity.QrRequestStatus;
import com.possaas.modules.qrorder.repository.*;
import com.possaas.modules.qrorder.service.chain.*;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class QrStaffDecisionService {
    private final QrStaffAccessService access;
    private final QrRequestEventPublisher events;
    private final QrOrderRequestRepository requests;
    private final QrOrderRequestItemRepository items;
    private final QrApprovalReplayHandler replay;
    private final QrApprovalReviewHandler review;
    private final QrApprovalPrepareHandler prepare;
    private final QrApprovalPersistHandler persist;
    private final QrRequestAudit audit;
    private final QrStaffResponseMapper mapper;
    private final Clock clock;

    @Transactional public QrApprovalResponse approve(UUID id, QrStaffRequests.Approve body, String ip) {
        var locked = access.lockApprove(); var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var request = access.request(locked.actor().restaurantId(), id);
        var lines = items.findAllByRestaurantIdAndRequestIdOrderByLineNumberAsc(locked.actor().restaurantId(), id);
        var chain = new OrderChain<QrApprovalContext,QrApprovalResponse>(List.of(replay, review, prepare, persist),
            c -> { throw new IllegalStateException("QR approval chain did not persist or replay"); });
        return chain.execute(new QrApprovalContext(locked, now, ip, body, request, lines));
    }
    @Transactional public QrStaffRequestSummary reject(UUID id, QrStaffRequests.Reject body, String ip) {
        var locked = access.lockReject(); var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var request = access.request(locked.actor().restaurantId(), id);
        var lines = items.findAllByRestaurantIdAndRequestIdOrderByLineNumberAsc(locked.actor().restaurantId(), id);
        if (request.getStatus() == QrRequestStatus.REJECTED) return mapper.summary(request, lines, now);
        if (request.getStatus() != QrRequestStatus.PENDING)
            throw new ConflictException("INVALID_QR_REQUEST_TRANSITION", "Only pending requests can be rejected");
        if (request.getVersion() != body.expectedRequestVersion())
            throw new ConflictException("CONCURRENT_QR_REQUEST_UPDATE", "QR request changed; reload before retrying");
        var before = audit.snapshot(request);
        request.setStatus(QrRequestStatus.REJECTED); request.setRejectedBy(locked.actor().userId()); request.setRejectedAt(now);
        request.setRejectionReason(body.reason().trim()); request.setUpdatedAt(now); requests.saveAndFlush(request);
        audit.record(request, locked.actor().userId(), "QR_ORDER_REQUEST_REJECTED", before, ip); requests.flush();
        events.publish(request,Change.REJECTED);
        return mapper.summary(request, lines, now);
    }
}
