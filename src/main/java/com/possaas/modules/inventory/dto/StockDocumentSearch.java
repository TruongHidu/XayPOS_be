package com.possaas.modules.inventory.dto;

import com.possaas.modules.inventory.entity.StockDocumentStatus;
import com.possaas.modules.inventory.entity.StockDocumentType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StockDocumentSearch {
    @Size(max = 100) private String q;
    private StockDocumentType type;
    private StockDocumentStatus status;
    private UUID warehouseId;
    private Instant createdFrom;
    private Instant createdTo;
    @Min(0) private int page = 0;
    @Min(1) @Max(100) private int size = 20;
    private String sortBy = "createdAt";
    private String direction = "desc";

    public String q() { return q; }
    public StockDocumentType type() { return type; }
    public StockDocumentStatus status() { return status; }
    public UUID warehouseId() { return warehouseId; }
    public Instant createdFrom() { return createdFrom; }
    public Instant createdTo() { return createdTo; }
    public int page() { return page; }
    public int size() { return size; }
    public String sortBy() { return sortBy; }
    public String direction() { return direction; }
}
