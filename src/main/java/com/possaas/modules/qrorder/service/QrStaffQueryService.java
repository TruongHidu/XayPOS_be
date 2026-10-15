package com.possaas.modules.qrorder.service;

import com.possaas.modules.qrorder.dto.*;
import com.possaas.modules.qrorder.entity.QrOrderRequestItem;
import com.possaas.modules.qrorder.repository.*;
import com.possaas.modules.subscription.dto.PageResponse;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class QrStaffQueryService {
    private final QrStaffAccessService access;
    private final QrOrderRequestRepository requests;
    private final QrOrderRequestItemRepository items;
    private final QrStaffResponseMapper mapper;
    private final QrReviewContextService review;
    private final Clock clock;

    public PageResponse<QrStaffRequestSummary> search(QrStaffSearch filter) {
        var actor = access.read(); var now = clock.instant();
        var page = requests.findAll(QrStaffRequestSpecifications.search(actor.restaurantId(), filter, now), filter.pageable());
        var ids = page.stream().map(r -> r.getId()).toList();
        var grouped = ids.isEmpty() ? java.util.Map.<UUID,List<QrOrderRequestItem>>of()
            : items.findAllByRestaurantIdAndRequestIdInOrderByLineNumberAsc(actor.restaurantId(), ids).stream()
                .collect(Collectors.groupingBy(QrOrderRequestItem::getRequestId));
        return PageResponse.from(page.map(r -> mapper.summary(r, grouped.getOrDefault(r.getId(), List.of()), now)));
    }
    public QrStaffRequestDetail detail(UUID id) {
        var actor = access.read(); var now = clock.instant(); var request = access.request(actor.restaurantId(), id);
        var lines = items.findAllByRestaurantIdAndRequestIdOrderByLineNumberAsc(actor.restaurantId(), id);
        var state = review.inspect(request, lines, actor, now);
        return new QrStaffRequestDetail(mapper.summary(request, lines, now), state.quote(), state.hash(),
            state.canApprove(), state.reasonCode(), state.context());
    }
}
