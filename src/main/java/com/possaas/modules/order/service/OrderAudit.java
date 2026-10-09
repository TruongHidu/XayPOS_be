package com.possaas.modules.order.service;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.order.entity.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class OrderAudit {
    private final AuditService audit;
    public Map<String,Object> snapshot(Order order,List<OrderItem> lines) {
        var state=new LinkedHashMap<String,Object>();
        state.put("orderCode",order.getOrderCode()); state.put("serviceType",order.getServiceType()); state.put("tableSessionId",order.getTableSessionId());
        state.put("status",order.getStatus()); state.put("paymentStatus",order.getPaymentStatus()); state.put("totalAmount",order.getTotalAmount());
        state.put("currencyCode",order.getCurrencyCode()); state.put("version",order.getVersion()); state.put("mutationSequence",order.getMutationSequence());
        state.put("lines",lines.stream().map(l->Map.of("id",l.getId(),"status",l.getStatus(),"quantity",l.getQuantity(),"lineTotal",l.getLineTotal())).toList());
        // No raw body, customer PII, notes, idempotency key, request hash or token.
        return state;
    }
    public void record(CurrentUser actor,String action,Order order,Map<String,Object> before,List<OrderItem> lines,String ip) {
        audit.record(actor.restaurantId(),actor.userId(),action,"orders",order.getId(),before,snapshot(order,lines),ip);
    }
}
