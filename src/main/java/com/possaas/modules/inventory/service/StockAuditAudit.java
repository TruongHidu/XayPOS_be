package com.possaas.modules.inventory.service;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.inventory.entity.StockAudit;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class StockAuditAudit {
    private final AuditService audits;
    public Map<String,Object> snapshot(StockAudit audit){
        Map<String,Object> data=new LinkedHashMap<>();data.put("auditCode",audit.getAuditCode());
        data.put("warehouseId",audit.getWarehouseId());data.put("status",audit.getStatus());data.put("note",audit.getNote());return data;
    }
    public void record(CurrentUser actor,String action,StockAudit audit,Map<String,Object> before,
            Map<String,Object> extra,String ip){
        var after=snapshot(audit);after.putAll(extra);audits.record(actor.restaurantId(),actor.userId(),action,
            "stock_audits",audit.getId(),before,after,ip);
    }
}
