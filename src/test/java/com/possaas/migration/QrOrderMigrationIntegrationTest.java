package com.possaas.migration;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class QrOrderMigrationIntegrationTest {
    @Autowired DataSource dataSource;
    @Test void upgradeFromV24SeedsAccessEpochWithoutChangingSubscriptionsPackagesRolesOrExistingTables() throws Exception {
        String schema="it_qr_migration_"+UUID.randomUUID().toString().replace("-","");
        String prefix="\""+schema+"\".";
        try(Connection c=dataSource.getConnection(); Statement s=c.createStatement()) { s.execute("CREATE SCHEMA \""+schema+"\""); }
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("24").load().migrate();
            var before=new LinkedHashMap<String,String>();
            try(Connection c=dataSource.getConnection();Statement s=c.createStatement()) {
                s.execute("INSERT INTO "+prefix+"restaurants(id,code,name,status) VALUES('00000000-0000-0000-0000-000000000001','QR_V25','Existing','ACTIVE')");
                s.execute("INSERT INTO "+prefix+"restaurant_tables(id,restaurant_id,code,name,status,qr_token,created_at,updated_at) VALUES('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','B1','Existing','AVAILABLE','preserved-table-qr',now(),now())");
                for(String name:List.of("restaurants","restaurant_tables","table_sessions","orders","order_items","payments","packages","package_features","restaurant_subscriptions","roles","role_permissions","permissions")) before.put(name,snapshot(s,prefix,name));
            }
            var flyway=Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("25").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1); flyway.validate();
            try(Connection c=dataSource.getConnection();Statement s=c.createStatement()) {
                for(var entry:before.entrySet()) assertThat(snapshot(s,prefix,entry.getKey())).as(entry.getKey()).isEqualTo(entry.getValue());
                try(ResultSet rows=s.executeQuery("select table_id,epoch from "+prefix+"qr_table_access")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getObject(1,UUID.class)).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000002")); assertThat(rows.getLong(2)).isZero(); assertThat(rows.next()).isFalse();
                }
                try(ResultSet rows=s.executeQuery("select indexdef from pg_indexes where schemaname='"+schema+"' and indexname='ux_order_serving_session'")) { assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).contains("UNIQUE"); }
            }
        } finally {
            if(!schema.matches("it_qr_migration_[0-9a-f]{32}")) throw new IllegalStateException("Unsafe schema");
            try(Connection c=dataSource.getConnection();Statement s=c.createStatement()) { s.execute("DROP SCHEMA \""+schema+"\" CASCADE"); }
        }
    }
    private String snapshot(Statement s,String prefix,String name) throws Exception {
        try(ResultSet r=s.executeQuery("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text from "+prefix+name+" t")) { r.next(); return r.getString(1); }
    }
}
