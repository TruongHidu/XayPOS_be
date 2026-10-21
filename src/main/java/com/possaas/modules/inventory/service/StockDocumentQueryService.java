package com.possaas.modules.inventory.service;

import com.possaas.modules.inventory.dto.InventoryTransactionResponse;
import com.possaas.modules.inventory.dto.InventoryTransactionSearch;
import com.possaas.modules.inventory.dto.StockDocumentDetailResponse;
import com.possaas.modules.inventory.dto.StockDocumentSearch;
import com.possaas.modules.inventory.dto.StockDocumentSummaryResponse;
import com.possaas.modules.inventory.repository.InventoryTransactionQueryRepository;
import com.possaas.modules.inventory.repository.StockDocumentItemRepository;
import com.possaas.modules.inventory.repository.StockDocumentQueryRepository;
import com.possaas.modules.subscription.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockDocumentQueryService {
    private final StockDocumentAccessService access;
    private final InventoryAccessService inventoryAccess;
    private final StockDocumentQueryRepository documentQueries;
    private final InventoryTransactionQueryRepository transactionQueries;
    private final StockDocumentItemRepository items;
    private final StockDocumentResponseMapper mapper;

    public PageResponse<StockDocumentSummaryResponse> search(StockDocumentSearch search) {
        var actor = access.queryActor();
        validateRange(search.createdFrom(), search.createdTo());
        var result = documentQueries.search(actor.restaurantId(), search);
        return new PageResponse<>(result.content(), search.page(), search.size(), result.totalElements(),
                pages(result.totalElements(), search.size()));
    }

    public StockDocumentDetailResponse detail(java.util.UUID documentId) {
        var actor = access.queryActor();
        var document = access.document(actor.restaurantId(), documentId);
        var warehouse = inventoryAccess.warehouseIncludingDeleted(actor.restaurantId(), document.getWarehouseId());
        return mapper.detail(document, warehouse,
                items.findAllByRestaurantIdAndStockDocumentIdOrderByCreatedAtAscIdAsc(actor.restaurantId(), documentId));
    }

    public PageResponse<InventoryTransactionResponse> transactions(InventoryTransactionSearch search) {
        var actor = access.queryActor();
        validateRange(search.createdFrom(), search.createdTo());
        var result = transactionQueries.search(actor.restaurantId(), search);
        return new PageResponse<>(result.content(), search.page(), search.size(), result.totalElements(),
                pages(result.totalElements(), search.size()));
    }

    private static int pages(long total, int size) { return (int) ((total + size - 1) / size); }

    private static void validateRange(java.time.Instant from, java.time.Instant to) {
        if (from != null && to != null && from.isAfter(to))
            throw new com.possaas.common.exception.BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR", "createdFrom must not be after createdTo");
    }
}
