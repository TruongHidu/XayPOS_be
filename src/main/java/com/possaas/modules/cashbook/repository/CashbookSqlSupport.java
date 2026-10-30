package com.possaas.modules.cashbook.repository;
import com.possaas.modules.cashbook.service.CashbookQueryContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.Instant;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
final class CashbookSqlSupport {
    private CashbookSqlSupport() {}
    static MapSqlParameterSource parameters(CashbookQueryContext c) {
        return new MapSqlParameterSource().addValue("restaurantId",c.restaurantId()).addValue("currencyCode",c.currencyCode())
            .addValue("fromInclusive",Timestamp.from(c.fromInclusive())).addValue("toExclusive",Timestamp.from(c.toExclusive()))
            .addValue("timezone",c.zone().getId());
    }
    static String pattern(String q) {return q==null?null:"%"+q.toLowerCase(java.util.Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
    static BigDecimal money(BigDecimal n) {return n.setScale(2,RoundingMode.UNNECESSARY);}
    static Instant instant(ResultSet rs,String name) throws SQLException {var t=rs.getTimestamp(name);return t==null?null:t.toInstant();}
}
