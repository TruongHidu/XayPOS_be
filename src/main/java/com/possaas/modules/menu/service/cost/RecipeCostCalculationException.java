package com.possaas.modules.menu.service.cost;

/** Calculation failures are translated to the application's error contract by the query service. */
public class RecipeCostCalculationException extends RuntimeException {
    private final String code;

    public RecipeCostCalculationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
