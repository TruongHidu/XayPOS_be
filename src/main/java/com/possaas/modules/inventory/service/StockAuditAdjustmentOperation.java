package com.possaas.modules.inventory.service;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.inventory.entity.*;
import com.possaas.modules.inventory.repository.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Server-owned document assembly. Posting remains the single writer of balances/ledger/alerts. */
@Service @RequiredArgsConstructor @Transactional(propagation=Propagation.MANDATORY)
public class StockAuditAdjustmentOperation {
    public static final String KEY_PREFIX="SYS_STOCK_AUDIT:";
    private final StockDocumentRepository documents;
    private final StockDocumentItemRepository items;
    private final StockDocumentCodeGenerator codes;
    private final StockPostingService posting;
    private final StockAuditPolicy policy;
    private final InventoryAudit audit;
    private final Clock clock;

    public StockDocument post(CurrentUser actor,StockAudit source,List<StockAuditItem> snapshot,String ip){
        var changed=snapshot.stream().filter(row->row.difference().signum()!=0).toList();
        if(changed.isEmpty())return null;
        BigDecimal quantity=new BigDecimal("0.000"),cost=new BigDecimal("0.00");
        for(var row:changed){quantity=quantity.add(row.difference().abs());cost=cost.add(policy.lineCost(row.difference().abs(),row.getReferenceUnitCostSnapshot()));}
        policy.documentTotals(quantity,cost);
        String key=KEY_PREFIX+source.getId();var now=clock.instant();
        var document=new StockDocument();document.initialize(actor.restaurantId(),source.getWarehouseId(),
            codes.generate(StockDocumentType.ADJUSTMENT),StockDocumentType.ADJUSTMENT,key,hash(key),
            "Stock audit "+source.getAuditCode(),actor.userId(),now);document.linkStockAudit(source.getId());
        documents.saveAndFlush(document);
        var lines=changed.stream().map(row->{
            var item=new StockDocumentItem();item.initialize(actor.restaurantId(),document.getId(),row.getIngredientId(),
                row.getSku(),row.getName(),row.getUnit(),row.difference().abs(),row.difference().signum()>0?
                    AdjustmentDirection.INCREASE:AdjustmentDirection.DECREASE,row.getReferenceUnitCostSnapshot(),row.getNote(),now);
            return item;
        }).toList();
        items.saveAllAndFlush(lines);document.updateTotals(lines.size(),quantity,cost,now);documents.saveAndFlush(document);
        var created=audit.document(document);created.put("stockAuditId",source.getId());
        audit.document(actor,"STOCK_DOCUMENT_CREATED",document,null,created,ip);
        posting.post(actor,document,lines,ip);
        var before=audit.document(document);document.confirm(actor.userId(),now);documents.saveAndFlush(document);
        var confirmed=audit.document(document);confirmed.put("stockAuditId",source.getId());
        audit.document(actor,"STOCK_DOCUMENT_CONFIRMED",document,before,confirmed,ip);
        return document;
    }
    public static String hash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException unavailable){throw new IllegalStateException(unavailable);}
    }
}
