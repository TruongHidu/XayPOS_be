package com.possaas.modules.table.service;

import com.possaas.modules.subscription.dto.PageResponse;
import com.possaas.modules.table.dto.TableSessionTransferResponse;
import com.possaas.modules.table.repository.TableSessionTransferRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TableSessionTransferQueryService {
    private final TableAccessService access;
    private final TableSessionTransferRepository transfers;

    public PageResponse<TableSessionTransferResponse> history(UUID sessionId, int page, int size) {
        var actor = access.require(false);
        access.session(actor.restaurantId(), sessionId);
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw TableValidationPolicy.invalid("Invalid pagination");
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "sessionVersionAfter").and(Sort.by("id")));
        return PageResponse.from(transfers.findAllByRestaurantIdAndSessionId(actor.restaurantId(), sessionId, pageable)
                .map(TableSessionTransferResponse::from));
    }
}
