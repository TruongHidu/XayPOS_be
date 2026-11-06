package com.possaas.modules.inventory.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.inventory.entity.StockDocument;
import com.possaas.modules.inventory.repository.StockDocumentRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StockDocumentAccessService {
    private final InventoryAccessService inventoryAccess;
    private final StockDocumentRepository documents;

    public CurrentUser queryActor() { return inventoryAccess.require(false); }
    public CurrentUser mutationActor() { return inventoryAccess.require(true); }

    public StockDocument document(UUID restaurantId, UUID documentId) {
        return documents.findByIdAndRestaurantId(documentId, restaurantId).orElseThrow(this::notFound);
    }

    public StockDocument documentForUpdate(UUID restaurantId, UUID documentId) {
        return documents.findForUpdate(restaurantId, documentId).orElseThrow(this::notFound);
    }

    private ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("STOCK_DOCUMENT_NOT_FOUND", "Stock document not found");
    }
}
