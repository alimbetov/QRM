package kz.zakaz.catalog;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class CatalogPostgresPublishedGuardIT {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17-alpine");

    @Test void databaseRejectsDirectPublishedSkuEditsWithoutCallingJavaService() throws Exception {
        try(Connection c=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),
                                                      postgres.getPassword())){
            ScriptUtils.executeSqlScript(c,new ClassPathResource("db/manual/V001__catalog_foundation.sql"));
            ScriptUtils.executeSqlScript(c,new ClassPathResource("db/manual/V002__catalog_enum_governance.sql"));
            var guard=new ClassPathResource("db/manual/V003__catalog_published_sku_guard.sql");
            String ddl;
            try(var input=guard.getInputStream()){
                ddl=new String(input.readAllBytes(),StandardCharsets.UTF_8);
            }
            for(String statement:ddl.split("-- STATEMENT BREAK")) {
                try(var s=c.createStatement()){s.execute(statement);}
            }

            UUID category=UUID.randomUUID(),product=UUID.randomUUID(),
                 listing=UUID.randomUUID(),sku=UUID.randomUUID(),attr=UUID.randomUUID(),
                 attrValue=UUID.randomUUID();
            exec(c,"INSERT INTO catalog_category(id,hcc_code,product_type,label,active) VALUES (?,'01020030000000000000','PHYSICAL','Phones',true)",category);
            exec(c,"INSERT INTO catalog_product(id,category_id,canonical_name) VALUES (?,?,'Phone')",product,category);
            exec(c,"INSERT INTO catalog_listing(id,seller_id,product_id,status) VALUES (?,?,?,'DRAFT')",
                 listing,UUID.randomUUID(),product);
            exec(c,"INSERT INTO catalog_sku(id,listing_id,seller_sku_code,tracking_mode) VALUES (?,?,'A56','TRACKED')",
                 sku,listing);
            exec(c,"INSERT INTO catalog_attribute_definition(id,code,kind,active) VALUES (?,'STORAGE_GB','DECIMAL',true)",attr);
            exec(c,"INSERT INTO catalog_sku_attribute_value(id,sku_id,attribute_id,canonical_value) VALUES (?,?,?,'256')",
                 attrValue,sku,attr);
            exec(c,"UPDATE catalog_listing SET status='PUBLIC' WHERE id=?",listing);

            assertThatThrownBy(()->exec(c,"INSERT INTO catalog_sku(id,listing_id,seller_sku_code,tracking_mode) VALUES (?,?,'ANOTHER','TRACKED')",
                UUID.randomUUID(),listing)).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->exec(c,"UPDATE catalog_sku_attribute_value SET canonical_value='512' WHERE id=?",
                attrValue)).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->exec(c,"DELETE FROM catalog_sku_attribute_value WHERE id=?",
                attrValue)).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->exec(c,"DELETE FROM catalog_sku WHERE id=?",
                sku)).isInstanceOf(SQLException.class);
        }
    }
    private static void exec(Connection c,String sql,Object... args) throws SQLException {
        try(var ps=c.prepareStatement(sql)){
            for(int i=0;i<args.length;i++)ps.setObject(i+1,args[i]);
            ps.executeUpdate();
        }
    }
}
