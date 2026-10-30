package com.possaas.modules.cashbook.service;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.cashbook.entity.CashbookExpense;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class CashbookExpenseAudit {
    private final AuditService audit;
    public Map<String,Object> snapshot(CashbookExpense e) {
        Map<String,Object> m=new LinkedHashMap<>();m.put("expenseCode",e.getExpenseCode());m.put("status",e.getStatus());m.put("amount",e.getAmount());
        m.put("currencyCode",e.getCurrencyCode());m.put("paymentMethod",e.getPaymentMethod().name());m.put("expenseAt",e.getExpenseAt().toString());
        m.put("categoryId",e.getCategoryId());m.put("stockDocumentId",e.getStockDocumentId());m.put("voidedBy",e.getVoidedBy());m.put("voidedAt",e.getVoidedAt()==null?null:e.getVoidedAt().toString());m.put("voidReason",e.getVoidReason());return m;
    }
    public void record(CurrentUser actor,String action,CashbookExpense e,Map<String,Object> before,String ip) {
        audit.record(actor.restaurantId(),actor.userId(),action,"cashbook_expenses",e.getId(),before,snapshot(e),ip);
    }
}
