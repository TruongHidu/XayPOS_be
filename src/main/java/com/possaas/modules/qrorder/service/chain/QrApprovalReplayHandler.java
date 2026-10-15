package com.possaas.modules.qrorder.service.chain;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.ConflictException;
import com.possaas.modules.order.repository.OrderItemRepository;
import com.possaas.modules.order.service.OrderAccessService;
import com.possaas.modules.order.service.OrderResponseMapper;
import com.possaas.modules.order.service.chain.*;
import com.possaas.modules.qrorder.dto.QrApprovalResponse;
import com.possaas.modules.qrorder.entity.*;
import com.possaas.modules.qrorder.service.QrStaffResponseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class QrApprovalReplayHandler implements OrderHandler<QrApprovalContext,QrApprovalResponse> {
    private final OrderAccessService orders;
    private final OrderItemRepository items;
    private final OrderResponseMapper orderMapper;
    private final QrStaffResponseMapper mapper;
    @Override public QrApprovalResponse handle(QrApprovalContext c, OrderNext<QrApprovalContext,QrApprovalResponse> next) {
        var r = c.getRequest(); var body = c.getCommand();
        if (!body.isTargetSessionPresent()) throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "targetSessionId must be supplied explicitly");
        if (r.getEntryType() == QrEntryType.RESTAURANT && body.getTargetSessionId() != null)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "TAKEAWAY_SESSION_NOT_ALLOWED", "Takeaway targetSessionId must be null");
        if (r.getStatus() == QrRequestStatus.APPROVED) {
            if (body.getTargetSessionId() != null && !body.getTargetSessionId().equals(r.getApprovedSessionId()))
                throw new ConflictException("QR_REQUEST_SESSION_CHANGED", "Request was approved for another session");
            var root = orders.order(r.getRestaurantId(), r.getApprovedOrderId());
            var response = orderMapper.detail(root, items.findAllByRestaurantIdAndOrderIdOrderByLineNumberAsc(r.getRestaurantId(), root.getId()));
            return next.complete(mapper.approved(r, c.getItems(), response, true));
        }
        if (r.getStatus() != QrRequestStatus.PENDING)
            throw new ConflictException("INVALID_QR_REQUEST_TRANSITION", "Only pending requests can be approved");
        if (r.getVersion() != body.getExpectedRequestVersion())
            throw new ConflictException("CONCURRENT_QR_REQUEST_UPDATE", "QR request changed; reload before retrying");
        return next.proceed(c);
    }
}
