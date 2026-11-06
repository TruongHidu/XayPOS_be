package com.possaas.modules.inventory.dto;

import com.possaas.modules.inventory.entity.StockAuditStatus;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class StockAuditSearch {
    @Size(max=100) private String q;
    private UUID warehouseId;
    private StockAuditStatus status;
    private Instant createdFrom;
    private Instant createdTo;
    @Min(0) private int page=0;
    @Min(1) @Max(100) private int size=20;
    @NotNull @Pattern(regexp="createdAt|startedAt|updatedAt|auditCode") private String sortBy="createdAt";
    @NotNull @Pattern(regexp="(?i)asc|desc") private String direction="desc";
    @AssertTrue(message="createdFrom must be before createdTo")
    public boolean isValidPeriod(){return createdFrom==null || createdTo==null || createdFrom.isBefore(createdTo);}
}
