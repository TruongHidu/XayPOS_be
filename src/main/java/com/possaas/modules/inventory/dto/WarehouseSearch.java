package com.possaas.modules.inventory.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WarehouseSearch {
    @Size(max = 100)
    private String q;
    private Boolean active;
    @Min(0)
    private int page = 0;
    @Min(1)
    @Max(100)
    private int size = 20;
    private String sortBy = "createdAt";
    private String direction = "desc";

    public String q() { return q; }
    public Boolean active() { return active; }
    public int page() { return page; }
    public int size() { return size; }
    public String sortBy() { return sortBy; }
    public String direction() { return direction; }
}
