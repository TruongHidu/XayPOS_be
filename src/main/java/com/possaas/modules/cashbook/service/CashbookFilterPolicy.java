package com.possaas.modules.cashbook.service;
import com.possaas.common.exception.BusinessException;
import com.possaas.modules.cashbook.dto.CashbookReadRequests;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
@Component
public class CashbookFilterPolicy {
    public record Filter(String type,String sourceType,String categoryCode,String paymentMethod,UUID createdBy,String q) {}
    public record Page(int page,int size,String sortBy,String direction) {}
    public Filter filter(CashbookReadRequests.Filter input) {
        return new Filter(value(input.type(),Set.of("INCOME","EXPENSE")),value(input.sourceType(),Set.of("ORDER_PAYMENT","EXPENSE_VOUCHER")),
            category(input.categoryCode()),input.paymentMethod()==null?null:input.paymentMethod().name(),input.createdBy(),search(input.q()));
    }
    public String value(String input,Set<String> allowed) {
        if(input==null) return null;
        String value=input.trim().toUpperCase(Locale.ROOT);
        if(!allowed.contains(value)) throw bad("INVALID_CASHBOOK_FILTER","Unsupported cashbook filter");
        return value;
    }
    public String category(String value) {return value==null?null:value.trim().toUpperCase(Locale.ROOT);}
    public String search(String value) {return value==null||value.isBlank()?null:value.trim();}
    public Page page(CashbookReadRequests.Page request,String defaultSort,Set<String> allowed) {
        int page=request.page()==null?0:request.page(),size=request.size()==null?20:request.size();
        if(page<0||size<1||size>100) throw bad("INVALID_CASHBOOK_FILTER","Invalid page or size");
        String sort=request.sortBy()==null?defaultSort:request.sortBy();
        String direction=request.direction()==null?"desc":request.direction().toLowerCase(Locale.ROOT);
        if(!allowed.contains(sort)||!Set.of("asc","desc").contains(direction)) throw bad("INVALID_CASHBOOK_SORT","Unsupported cashbook sort");
        return new Page(page,size,sort,direction);
    }
    private static BusinessException bad(String code,String message) {return new BusinessException(HttpStatus.BAD_REQUEST,code,message);}
}
