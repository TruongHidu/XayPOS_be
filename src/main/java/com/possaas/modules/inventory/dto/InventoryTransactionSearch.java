package com.possaas.modules.inventory.dto;

import com.possaas.modules.inventory.entity.InventoryTransactionType;
import com.possaas.modules.inventory.entity.InventorySourceType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InventoryTransactionSearch {
    private UUID warehouseId;
    private UUID ingredientId;
    private UUID documentId;
    private UUID orderId;
    private UUID orderItemId;
    private InventorySourceType sourceType;
    private InventoryTransactionType transactionType;
    private Instant createdFrom;
    private Instant createdTo;
    @Min(0) private int page = 0;
    @Min(1) @Max(100) private int size = 20;
    private String sortBy = "createdAt";
    private String direction = "desc";

    public UUID warehouseId() { return warehouseId; }
    public UUID ingredientId() { return ingredientId; }
    public UUID documentId() { return documentId; }
    public UUID orderId() { return orderId; }
    public UUID orderItemId() { return orderItemId; }
    public InventorySourceType sourceType() { return sourceType; }
    public InventoryTransactionType transactionType() { return transactionType; }
    public Instant createdFrom() { return createdFrom; }
    public Instant createdTo() { return createdTo; }
    public int page() { return page; }
    public int size() { return size; }
    public String sortBy() { return sortBy; }
    public String direction() { return direction; }
}
