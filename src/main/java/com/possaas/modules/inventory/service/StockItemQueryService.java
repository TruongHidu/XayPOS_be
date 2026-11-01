package com.possaas.modules.inventory.service;
import com.possaas.modules.subscription.dto.PageResponse;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service @RequiredArgsConstructor @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class StockItemQueryService {
    private final InventoryAccessService access;
    private final NamedParameterJdbcTemplate jdbc;
    public PageResponse<StockItem> search(String q,String type,int page,int size) {
        var actor=access.require(false);
        if(type!=null && !Set.of("INGREDIENT","MENU_ITEM").contains(type))
            throw new com.possaas.common.exception.BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Unsupported itemType");
        var params=new HashMap<String,Object>();params.put("tenant",actor.restaurantId());
        String where=" FROM items WHERE restaurant_id=:tenant AND track_inventory AND NOT recipe_enabled AND deleted_at IS NULL";
        if(type!=null){where+=" AND item_type=:type";params.put("type",type);}
        if(q!=null && !q.isBlank()) {where+=" AND (strpos(lower(name),:q)>0 OR strpos(lower(coalesce(sku,'')),:q)>0)";params.put("q",q.trim().toLowerCase(Locale.ROOT));}
        Long total=jdbc.queryForObject("SELECT count(*)"+where,params,Long.class);
        params.put("size",size);params.put("offset",(long)page*size);
        var rows=jdbc.query("SELECT id,item_type,sku,name,base_unit,is_active"+where+" ORDER BY lower(name),id LIMIT :size OFFSET :offset",params,(rs,n)->new StockItem(rs.getObject("id",UUID.class),rs.getString("item_type"),rs.getString("sku"),rs.getString("name"),rs.getString("base_unit"),rs.getBoolean("is_active")));
        long count=total==null?0:total;
        return new PageResponse<>(rows,page,size,count,(int)((count+size-1)/size));
    }
    public record StockItem(UUID itemId,String itemType,String sku,String name,String baseUnit,boolean active) {}
}
