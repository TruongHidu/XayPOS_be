package com.possaas.modules.inventory.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.ConflictException;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.inventory.application.port.TrackedIngredientCatalog;
import com.possaas.modules.inventory.dto.StockDocumentDetailResponse;
import com.possaas.modules.inventory.dto.StockDocumentRequests;
import com.possaas.modules.inventory.entity.StockDocument;
import com.possaas.modules.inventory.entity.StockDocumentItem;
import com.possaas.modules.inventory.entity.StockDocumentStatus;
import com.possaas.modules.inventory.repository.StockDocumentItemRepository;
import com.possaas.modules.inventory.repository.StockDocumentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class StockDocumentCommandService {
    private final StockDocumentAccessService access;
    private final InventoryAccessService inventoryAccess;
    private final StockDocumentRepository documents;
    private final StockDocumentItemRepository items;
    private final TrackedIngredientCatalog ingredients;
    private final StockDocumentLifecyclePolicy lifecycle;
    private final StockDocumentPermissionPolicy permissions;
    private final StockDocumentValidationPolicy validation;
    private final StockDocumentCodeGenerator codes;
    private final StockDocumentRequestHasher hasher;
    private final StockDocumentItemFactory itemFactory;
    private final StockPostingService posting;
    private final StockDocumentResponseMapper mapper;
    private final InventoryAudit audit;
    private final Clock clock;

    public StockDocumentDetailResponse create(String idempotencyHeader, StockDocumentRequests.Create request,
            String ipAddress) {
        CurrentUser actor = access.mutationActor();
        permissions.require(actor, request.documentType());
        String key = validation.publicDocumentKey(idempotencyHeader);
        String hash = hasher.hash(request);
        var replay = documents.findByRestaurantIdAndIdempotencyKey(actor.restaurantId(), key);
        if (replay.isPresent()) {
            StockDocument existing = replay.orElseThrow();
            if (!existing.getCreationRequestHash().equals(hash))
                throw new ConflictException("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was used for another request");
            return response(existing);
        }
        var warehouse = inventoryAccess.warehouse(actor.restaurantId(), request.warehouseId());
        requireActiveWarehouse(warehouse.isActive());
        ensureDistinct(request.safeItems().stream().map(StockDocumentRequests.Line::ingredientId).toList());
        StockDocument document = new StockDocument();
        document.initialize(actor.restaurantId(), warehouse.getId(), codes.generate(request.documentType()),
                request.documentType(), key, hash, validation.note(request.note()), actor.userId(), clock.instant());
        documents.saveAndFlush(document);

        if (!request.safeItems().isEmpty()) {
            var snapshots = ingredients.requireTrackedBatch(actor.restaurantId(),
                    request.safeItems().stream().map(StockDocumentRequests.Line::ingredientId).toList());
            List<StockDocumentItem> created = request.safeItems().stream().map(line -> itemFactory.create(document,
                    snapshots.get(line.ingredientId()), line.quantity(), line.unitCost(),
                    line.adjustmentDirection(), line.note())).toList();
            items.saveAllAndFlush(created);
            recalculate(document, created);
        }
        documents.saveAndFlush(document);
        audit.document(actor, "STOCK_DOCUMENT_CREATED", document, null, audit.document(document), ipAddress);
        return response(document);
    }

    public StockDocumentDetailResponse update(UUID documentId, StockDocumentRequests.Update request,
            String ipAddress) {
        CurrentUser actor = access.mutationActor();
        StockDocument document = access.documentForUpdate(actor.restaurantId(), documentId);
        permissions.require(actor, document.getDocumentType());
        lifecycle.requireManual(document);
        lifecycle.requireDraft(document);
        lifecycle.requireVersion(document, request.expectedVersion());
        String note = validation.note(request.note());
        if (Objects.equals(document.getNote(), note)) return response(document);
        Map<String, Object> before = audit.document(document);
        document.updateNote(note, clock.instant());
        documents.saveAndFlush(document);
        audit.document(actor, "STOCK_DOCUMENT_UPDATED", document, before, audit.document(document), ipAddress);
        return response(document);
    }

    public StockDocumentDetailResponse addItem(UUID documentId, StockDocumentRequests.AddItem request,
            String ipAddress) {
        CurrentUser actor = access.mutationActor();
        StockDocument document = mutable(actor, documentId, request.expectedVersion());
        if (items.existsByRestaurantIdAndStockDocumentIdAndIngredientId(
                actor.restaurantId(), documentId, request.ingredientId()))
            throw new ConflictException("DUPLICATE_STOCK_DOCUMENT_ITEM", "Ingredient already exists in document");
        var ingredient = ingredients.requireTracked(actor.restaurantId(), request.ingredientId());
        StockDocumentItem item = itemFactory.create(document, ingredient, request.quantity(), request.unitCost(),
                request.adjustmentDirection(), request.note());
        items.saveAndFlush(item);
        recalculate(document, loadItems(document));
        documents.saveAndFlush(document);
        audit.document(actor, "STOCK_DOCUMENT_ITEM_ADDED", document, null, itemAudit(item), ipAddress);
        return response(document);
    }

    public StockDocumentDetailResponse updateItem(UUID documentId, UUID itemId,
            StockDocumentRequests.UpdateItem request, String ipAddress) {
        CurrentUser actor = access.mutationActor();
        StockDocument document = mutable(actor, documentId, request.expectedVersion());
        StockDocumentItem item = requireItem(actor.restaurantId(), documentId, itemId);
        Map<String, Object> before = itemAudit(item);
        boolean changed = itemFactory.update(document, item, request.quantity(), request.unitCost(),
                request.adjustmentDirection(), request.note());
        if (!changed) return response(document);
        items.saveAndFlush(item);
        recalculate(document, loadItems(document));
        documents.saveAndFlush(document);
        audit.document(actor, "STOCK_DOCUMENT_ITEM_UPDATED", document, before, itemAudit(item), ipAddress);
        return response(document);
    }

    public void removeItem(UUID documentId, UUID itemId, long expectedVersion, String ipAddress) {
        CurrentUser actor = access.mutationActor();
        StockDocument document = mutable(actor, documentId, expectedVersion);
        StockDocumentItem item = requireItem(actor.restaurantId(), documentId, itemId);
        Map<String, Object> before = itemAudit(item);
        items.delete(item);
        items.flush();
        recalculate(document, loadItems(document));
        documents.saveAndFlush(document);
        audit.document(actor, "STOCK_DOCUMENT_ITEM_REMOVED", document, before, null, ipAddress);
    }

    public StockDocumentDetailResponse confirm(UUID documentId, StockDocumentRequests.Transition request,
            String ipAddress) {
        CurrentUser actor = access.mutationActor();
        StockDocument document = access.documentForUpdate(actor.restaurantId(), documentId);
        permissions.require(actor, document.getDocumentType());
        lifecycle.requireManual(document);
        if (document.getStatus() == StockDocumentStatus.CONFIRMED) return response(document);
        lifecycle.requireDraft(document);
        lifecycle.requireVersion(document, request.expectedVersion());
        lifecycle.requireNotEmpty(document);
        var warehouse = inventoryAccess.warehouse(actor.restaurantId(), document.getWarehouseId());
        requireActiveWarehouse(warehouse.isActive());
        List<StockDocumentItem> documentItems = loadItems(document);
        posting.post(actor, document, documentItems, ipAddress);
        Map<String, Object> before = audit.document(document);
        document.confirm(actor.userId(), clock.instant());
        documents.saveAndFlush(document);
        audit.document(actor, "STOCK_DOCUMENT_CONFIRMED", document, before, audit.document(document), ipAddress);
        return mapper.detail(document, warehouse, documentItems);
    }

    public StockDocumentDetailResponse cancel(UUID documentId, StockDocumentRequests.Transition request,
            String ipAddress) {
        CurrentUser actor = access.mutationActor();
        StockDocument document = access.documentForUpdate(actor.restaurantId(), documentId);
        permissions.require(actor, document.getDocumentType());
        lifecycle.requireManual(document);
        if (document.getStatus() == StockDocumentStatus.CANCELLED) return response(document);
        lifecycle.requireDraft(document);
        lifecycle.requireVersion(document, request.expectedVersion());
        Map<String, Object> before = audit.document(document);
        document.cancel(actor.userId(), clock.instant());
        documents.saveAndFlush(document);
        audit.document(actor, "STOCK_DOCUMENT_CANCELLED", document, before, audit.document(document), ipAddress);
        return response(document);
    }

    private StockDocument mutable(CurrentUser actor, UUID documentId, long expectedVersion) {
        StockDocument document = access.documentForUpdate(actor.restaurantId(), documentId);
        permissions.require(actor, document.getDocumentType());
        lifecycle.requireManual(document);
        lifecycle.requireDraft(document);
        lifecycle.requireVersion(document, expectedVersion);
        return document;
    }

    private void recalculate(StockDocument document, List<StockDocumentItem> values) {
        BigDecimal quantity = values.stream().map(StockDocumentItem::getQuantity)
                .reduce(new BigDecimal("0.000"), BigDecimal::add).setScale(3);
        BigDecimal cost = values.stream().map(StockDocumentItem::getTotalCost)
                .reduce(new BigDecimal("0.00"), BigDecimal::add).setScale(2);
        document.updateTotals(values.size(), quantity, cost, clock.instant());
    }

    private StockDocumentDetailResponse response(StockDocument document) {
        var warehouse = inventoryAccess.warehouseIncludingDeleted(document.getRestaurantId(), document.getWarehouseId());
        return mapper.detail(document, warehouse, loadItems(document));
    }

    private List<StockDocumentItem> loadItems(StockDocument document) {
        return items.findAllByRestaurantIdAndStockDocumentIdOrderByCreatedAtAscIdAsc(
                document.getRestaurantId(), document.getId());
    }

    private StockDocumentItem requireItem(UUID restaurantId, UUID documentId, UUID itemId) {
        return items.findByIdAndRestaurantIdAndStockDocumentId(itemId, restaurantId, documentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "STOCK_DOCUMENT_ITEM_NOT_FOUND", "Stock document item not found"));
    }

    private static void ensureDistinct(List<UUID> ids) {
        if (new HashSet<>(ids).size() != ids.size())
            throw new ConflictException("DUPLICATE_STOCK_DOCUMENT_ITEM", "Ingredient is duplicated in document");
    }

    private static void requireActiveWarehouse(boolean active) {
        if (!active) throw new ConflictException("WAREHOUSE_INACTIVE", "Warehouse is inactive");
    }

    private static Map<String, Object> itemAudit(StockDocumentItem item) {
        return Map.of("ingredientId", item.getIngredientId(), "quantity", item.getQuantity(),
                "unitCost", item.getUnitCost(), "totalCost", item.getTotalCost(),
                "direction", item.getAdjustmentDirection() == null ? "" : item.getAdjustmentDirection().name());
    }
}
