package com.possaas.modules.inventory.service;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.inventory.dto.*;
import com.possaas.modules.inventory.entity.*;
import com.possaas.modules.inventory.repository.*;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional
public class StockAuditCommandService {
    private final StockAuditAccessService access;
    private final InventoryAccessService inventory;
    private final StockAuditRepository audits;
    private final StockAuditItemRepository items;
    private final StockAuditQueryRepository queries;
    private final StockAuditPolicy policy;
    private final StockDocumentValidationPolicy validation;
    private final InventoryAvailabilityPolicy quantities;
    private final StockAuditSnapshotOperation snapshots;
    private final StockAuditConfirmationOperation confirmation;
    private final StockAuditAudit logs;
    private final Clock clock;

    public StockAuditDetailResponse create(String header,StockAuditRequests.Create request,String ip){
        var actor=access.require(true);
        String key=validation.idempotencyKey(header),note=validation.note(request.note());
        String hash=StockAuditAdjustmentOperation.hash(request.warehouseId()+"|"+(note==null?"":note));
        var replay=audits.findByRestaurantIdAndIdempotencyKey(actor.restaurantId(),key);
        if(replay.isPresent()){
            var existing=replay.orElseThrow();
            if(!existing.getCreationRequestHash().equals(hash))throw StockAuditPolicy.conflict("IDEMPOTENCY_KEY_REUSED","Idempotency-Key was used for another request");
            return response(actor,existing);
        }
        requireWarehouse(actor,request.warehouseId());
        String code="AUD-"+DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(clock.instant())+"-"+UUID.randomUUID().toString().substring(0,8);
        var audit=audits.saveAndFlush(new StockAudit(actor.restaurantId(),request.warehouseId(),code,key,hash,note,actor.userId(),clock.instant()));
        logs.record(actor,"STOCK_AUDIT_CREATED",audit,null,Map.of(),ip);
        return response(actor,audit);
    }

    public StockAuditDetailResponse update(UUID id,StockAuditRequests.Note request,String ip){
        var actor=access.require(true);var audit=locked(actor,id);
        policy.requireEditable(audit);policy.requireVersion(audit,request.expectedVersion());
        String note=validation.note(request.note());
        if(Objects.equals(note,audit.getNote()))return response(actor,audit);
        var before=logs.snapshot(audit);audit.updateNote(note,clock.instant());audits.saveAndFlush(audit);
        logs.record(actor,"STOCK_AUDIT_UPDATED",audit,before,Map.of(),ip);
        return response(actor,audit);
    }

    public StockAuditDetailResponse start(UUID id,StockAuditRequests.Transition request,String ip){
        var actor=access.require(true);var audit=locked(actor,id);
        if(audit.getStatus()==StockAuditStatus.COUNTING)return response(actor,audit);
        policy.requireStatus(audit,StockAuditStatus.DRAFT);policy.requireVersion(audit,request.expectedVersion());
        requireWarehouse(actor,audit.getWarehouseId());
        if(audits.existsByRestaurantIdAndWarehouseIdAndStatus(actor.restaurantId(),audit.getWarehouseId(),StockAuditStatus.COUNTING))
            throw StockAuditPolicy.conflict("WAREHOUSE_STOCK_AUDIT_IN_PROGRESS","Warehouse already has a COUNTING audit");
        var before=logs.snapshot(audit);audit.start(actor.userId(),clock.instant());
        // Flush establishes the DB-owned snapshot transaction identity before inserting scope rows.
        audits.saveAndFlush(audit);snapshots.capture(actor,audit);
        logs.record(actor,"STOCK_AUDIT_STARTED",audit,before,Map.of(),ip);
        return response(actor,audit);
    }

    public StockAuditDetailResponse count(UUID id,StockAuditRequests.Counts request,String ip){
        var actor=access.require(true);var audit=locked(actor,id);
        policy.requireStatus(audit,StockAuditStatus.COUNTING);policy.requireVersion(audit,request.expectedVersion());
        var ids=request.items().stream().map(StockAuditRequests.CountLine::auditItemId).toList();
        if(new HashSet<>(ids).size()!=ids.size())throw StockAuditPolicy.invalid("Duplicate auditItemId in batch");
        var rows=items.findAllByRestaurantIdAndStockAuditIdAndIdIn(actor.restaurantId(),id,ids).stream()
            .collect(Collectors.toMap(StockAuditItem::getId,Function.identity()));
        if(rows.size()!=ids.size())throw StockAuditPolicy.itemMissing();
        List<Map<String,Object>> changes=new ArrayList<>();
        for(var line:request.items()){
            var row=rows.get(line.auditItemId());var before=countSnapshot(row);
            if(row.count(quantities.quantity(line.countedQuantity()),line.note(),clock.instant())){
                var change=new LinkedHashMap<String,Object>();change.put("auditItemId",row.getId());
                change.put("before",before);change.put("after",countSnapshot(row));changes.add(change);
            }
        }
        if(changes.isEmpty())return response(actor,audit);
        items.flush();audit.counted(clock.instant());audits.saveAndFlush(audit);
        logs.record(actor,"STOCK_AUDIT_COUNTS_UPDATED",audit,null,Map.of("changes",changes),ip);
        return response(actor,audit);
    }

    public StockAuditDetailResponse confirm(UUID id,StockAuditRequests.Transition request,String ip){
        var actor=access.require(true);var audit=locked(actor,id);
        if(audit.getStatus()==StockAuditStatus.CONFIRMED)return response(actor,audit);
        policy.requireStatus(audit,StockAuditStatus.COUNTING);policy.requireVersion(audit,request.expectedVersion());
        var before=logs.snapshot(audit);var document=confirmation.confirm(actor,audit,ip);
        audit.confirm(actor.userId(),clock.instant());audits.saveAndFlush(audit);
        Map<String,Object> extra=new LinkedHashMap<>();extra.put("adjustmentDocumentId",document==null?null:document.getId());
        logs.record(actor,"STOCK_AUDIT_CONFIRMED",audit,before,extra,ip);
        return response(actor,audit);
    }

    public StockAuditDetailResponse cancel(UUID id,StockAuditRequests.Note request,String ip){
        var actor=access.require(true);var audit=locked(actor,id);
        if(audit.getStatus()==StockAuditStatus.CANCELLED)return response(actor,audit);
        policy.requireEditable(audit);policy.requireVersion(audit,request.expectedVersion());
        var before=logs.snapshot(audit);audit.cancel(actor.userId(),validation.note(request.note()),clock.instant());audits.saveAndFlush(audit);
        logs.record(actor,"STOCK_AUDIT_CANCELLED",audit,before,Map.of(),ip);
        return response(actor,audit);
    }

    private StockAudit locked(CurrentUser actor,UUID id){return audits.findForUpdate(actor.restaurantId(),id).orElseThrow(StockAuditPolicy::missing);}
    private StockAuditDetailResponse response(CurrentUser actor,StockAudit audit){return queries.detail(actor.restaurantId(),audit.getId());}
    private void requireWarehouse(CurrentUser actor,UUID id){
        if(!inventory.warehouse(actor.restaurantId(),id).isActive())throw StockAuditPolicy.conflict("WAREHOUSE_INACTIVE","Warehouse is inactive");
    }
    private static Map<String,Object> countSnapshot(StockAuditItem row){
        Map<String,Object> value=new LinkedHashMap<>();value.put("countedQuantity",row.getCountedQuantity());value.put("note",row.getNote());return value;
    }
}
