package com.possaas.modules.cashbook.service;
import com.possaas.common.exception.BusinessException;
import com.possaas.modules.restaurant.entity.Restaurant;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class CashbookRangePolicy {
    private final Clock clock;
    public CashbookQueryContext range(Restaurant restaurant, LocalDate from, LocalDate to, String requestedCurrency) {
        if(from==null||to==null||from.isAfter(to)) throw bad("INVALID_CASHBOOK_DATE_RANGE","A valid fromDate/toDate range is required");
        if(ChronoUnit.DAYS.between(from,to)>=366) throw bad("CASHBOOK_RANGE_TOO_LARGE","Range must not exceed 366 inclusive days");
        ZoneId zone;
        try {zone=ZoneId.of(restaurant.getTimezone());}
        catch(DateTimeException|NullPointerException ex) {throw new BusinessException(HttpStatus.CONFLICT,"INVALID_CASHBOOK_TIMEZONE","Restaurant timezone is invalid");}
        String currency=requestedCurrency==null?restaurant.getCurrencyCode():requestedCurrency;
        currency=currency==null?"":currency.trim().toUpperCase(Locale.ROOT);
        try {if(!currency.matches("[A-Z]{3}")) throw new IllegalArgumentException(); Currency.getInstance(currency);}
        catch(IllegalArgumentException ex) {throw bad("INVALID_CASHBOOK_CURRENCY","Currency must be a valid ISO code");}
        try {return new CashbookQueryContext(restaurant.getId(),from,to,zone,from.atStartOfDay(zone).toInstant(),
            to.plusDays(1).atStartOfDay(zone).toInstant(),currency,clock.instant());}
        catch(DateTimeException ex) {throw bad("INVALID_CASHBOOK_DATE_RANGE","Date range is unsupported");}
    }
    private static BusinessException bad(String code,String message) {return new BusinessException(HttpStatus.BAD_REQUEST,code,message);}
}
