package com.possaas.modules.qrorder.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @Entity @Table(name="qr_order_request_items")
public class QrOrderRequestItem extends QrRecord {
    @Column(name="request_id",nullable=false,updatable=false) private UUID requestId;
    @Column(name="item_id",nullable=false,updatable=false) private UUID itemId;
    @Column(name="line_number",nullable=false,updatable=false) private int lineNumber;
    @Column(nullable=false,updatable=false,precision=12,scale=3) private BigDecimal quantity;
    @Column(updatable=false,length=500) private String note;
    @Column(name="quoted_item_name",nullable=false,updatable=false,length=150) private String quotedItemName;
    @Column(name="quoted_unit",nullable=false,updatable=false,length=30) private String quotedUnit;
    @Column(name="quoted_unit_price",nullable=false,updatable=false,precision=14,scale=2) private BigDecimal quotedUnitPrice;
    @Column(name="quoted_line_total",nullable=false,updatable=false,precision=14,scale=2) private BigDecimal quotedLineTotal;
    @Column(name="approved_order_item_id") private UUID approvedOrderItemId;
    @Override public String toString() { return "QrOrderRequestItem[id="+getId()+"]"; }
}
