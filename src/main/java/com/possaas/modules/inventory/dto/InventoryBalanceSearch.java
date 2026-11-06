package com.possaas.modules.inventory.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InventoryBalanceSearch {
    private UUID warehouseId;
    @Size(max = 100)
    private String q;
    private Boolean ingredientActive;
    @Min(0)
    private int page = 0;
    @Min(1)
    @Max(100)
    private int size = 20;
    private String sortBy = "ingredientName";
    private String direction = "asc";

    public UUID warehouseId() { return warehouseId; }
    public String q() { return q; }
    public Boolean ingredientActive() { return ingredientActive; }
    public int page() { return page; }
    public int size() { return size; }
    public String sortBy() { return sortBy; }
    public String direction() { return direction; }
}
