package com.possaas.modules.inventory.dto;

import com.possaas.modules.inventory.entity.LowStockAlertStatus;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class LowStockAlertSearch {
    private UUID warehouseId;
    private UUID ingredientId;
    private LowStockAlertStatus status;
    @Size(max=100) private String q;
    private Instant triggeredFrom;
    private Instant triggeredTo;
    @Min(0) private int page=0;
    @Min(1) @Max(100) private int size=20;
    @NotNull @Pattern(regexp="triggeredAt|updatedAt") private String sortBy="triggeredAt";
    @NotNull @Pattern(regexp="(?i)asc|desc") private String direction="desc";

    @AssertTrue(message="triggeredFrom must be before triggeredTo")
    public boolean isValidPeriod() {
        return triggeredFrom==null || triggeredTo==null || triggeredFrom.isBefore(triggeredTo);
    }
}
