package com.possaas.modules.inventory.service;

import com.possaas.common.exception.*;
import com.possaas.modules.inventory.entity.*;
import java.math.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class StockAuditPolicy {
    public void requireVersion(StockAudit audit,long expected){if(audit.getVersion()!=expected)throw conflict("CONCURRENT_STOCK_AUDIT_UPDATE","Stock audit changed; reload before retrying");}
    public void requireStatus(StockAudit audit,StockAuditStatus status){if(audit.getStatus()!=status)throw transition();}
    public void requireEditable(StockAudit audit){if(audit.getStatus()!=StockAuditStatus.DRAFT && audit.getStatus()!=StockAuditStatus.COUNTING)throw transition();}
    public BigDecimal price(BigDecimal value){
        try {if(value==null || value.signum()<0 || value.compareTo(new BigDecimal("999999999999.99"))>0)throw new ArithmeticException();return value.setScale(2,RoundingMode.UNNECESSARY);}
        catch(ArithmeticException invalid){throw conflict("INVALID_INVENTORY_COST_DATA","Invalid ingredient reference cost");}
    }
    public BigDecimal lineCost(BigDecimal quantity,BigDecimal cost){
        BigDecimal total=quantity.multiply(price(cost)).setScale(2,RoundingMode.HALF_UP);
        if(total.compareTo(new BigDecimal("99999999999999.99"))>0)throw overflow();
        return total;
    }
    public void documentTotals(BigDecimal quantity,BigDecimal cost){
        if(quantity.compareTo(new BigDecimal("9999999999999.999"))>0 || cost.compareTo(new BigDecimal("9999999999999999.99"))>0)throw overflow();
    }
    public static ConflictException stale(){return conflict("STOCK_AUDIT_SNAPSHOT_STALE","Physical movements, quantity or ingredient unit changed; cancel and recount in a new audit");}
    public static ConflictException transition(){return conflict("INVALID_STOCK_AUDIT_TRANSITION","Operation is not allowed in this stock audit state");}
    public static ResourceNotFoundException missing(){return new ResourceNotFoundException("STOCK_AUDIT_NOT_FOUND","Stock audit not found");}
    public static ResourceNotFoundException itemMissing(){return new ResourceNotFoundException("STOCK_AUDIT_ITEM_NOT_FOUND","Stock audit item not found");}
    public static ConflictException overflow(){return conflict("STOCK_AUDIT_ADJUSTMENT_OUT_OF_RANGE","Generated adjustment exceeds supported precision");}
    public static ConflictException conflict(String code,String message){return new ConflictException(code,message);}
    public static BusinessException invalid(String message){return new BusinessException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message);}
}
