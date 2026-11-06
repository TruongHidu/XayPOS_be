package com.possaas.modules.inventory.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class StockAuditItemSearch {
    @Size(max=100) private String q;
    private Boolean counted;
    private boolean differenceOnly;
    @Min(0) private int page=0;
    @Min(1) @Max(100) private int size=20;
    @NotNull @Pattern(regexp="name|sku|systemQuantity|countedQuantity|differenceQuantity") private String sortBy="name";
    @NotNull @Pattern(regexp="(?i)asc|desc") private String direction="asc";
}
