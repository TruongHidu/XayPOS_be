package com.possaas.modules.inventory.dto;

public class LowStockSearch extends InventoryBalanceSearch {
    public LowStockSearch() {
        setSortBy("quantity");
        setDirection("asc");
    }
}
