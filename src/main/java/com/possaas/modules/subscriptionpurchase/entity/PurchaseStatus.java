package com.possaas.modules.subscriptionpurchase.entity;

public enum PurchaseStatus {
    PENDING_PAYMENT, PAID, ACTIVATED, EXPIRED, CANCELLED, REVIEW_REQUIRED, CLOSED;

    public boolean isOpen() {
        return this == PENDING_PAYMENT || this == PAID || this == REVIEW_REQUIRED;
    }
}
