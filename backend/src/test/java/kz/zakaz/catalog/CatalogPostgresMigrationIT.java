package kz.zakaz.catalog;

import java.sql.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class CatalogPostgresMigrationIT {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17-alpine");

    @Test void catalogV001AndV002RunOnPostgresWithConstraints() throws Exception {
        try(Connection conn=DriverManager.getConnection(postgres.getJdbcUrl(),
                                                        postgres.getUsername(),postgres.getPassword())) {
            ScriptUtils.executeSqlScript(conn,new ClassPathResource("db/manual/V001__catalog_foundation.sql"));
            ScriptUtils.executeSqlScript(conn,new ClassPathResource("db/manual/V002__catalog_enum_governance.sql"));
            var categoryId=java.util.UUID.randomUUID();
            try(var insert=conn.prepareStatement(
                "INSERT INTO catalog_category(id,hcc_code,product_type,label,active) VALUES (?,?,?, ?,true)")) {
                insert.setObject(1,categoryId); insert.setString(2,"01020030000000000000");
                insert.setString(3,"PHYSICAL");insert.setString(4,"Phones");
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
            assertThatThrownBy(()-> {
                try(var bad=conn.prepareStatement(
                    "INSERT INTO catalog_category(id,hcc_code,product_type,label,active) VALUES (?,?,?,?,true)")) {
                    bad.setObject(1,java.util.UUID.randomUUID());
                    bad.setString(2,"01000030000000000000");
                    bad.setString(3,"PHYSICAL");bad.setString(4,"Gap");
                    bad.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);

            var attrId=java.util.UUID.randomUUID();
            try(var stmt=conn.prepareStatement(
                "INSERT INTO catalog_attribute_definition(id,code,kind,active) VALUES (?,?,?,true)")) {
                stmt.setObject(1,attrId);stmt.setString(2,"COLOR");stmt.setString(3,"ENUM");
                assertThat(stmt.executeUpdate()).isEqualTo(1);
            }
            try(var stmt=conn.prepareStatement(
                "INSERT INTO catalog_attribute_enum_option(id,attribute_id,code,active) VALUES (?,?,?,true)")) {
                stmt.setObject(1,java.util.UUID.randomUUID());
                stmt.setObject(2,attrId);stmt.setString(3,"BLACK");
                assertThat(stmt.executeUpdate()).isEqualTo(1);
            }
            assertThatThrownBy(()->{
                try(var stmt=conn.prepareStatement(
                    "INSERT INTO catalog_attribute_enum_option(id,attribute_id,code,active) VALUES (?,?,?,true)")){
                    stmt.setObject(1,java.util.UUID.randomUUID());
                    stmt.setObject(2,attrId);stmt.setString(3,"BLACK");
                    stmt.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->{
                try(var stmt=conn.prepareStatement(
                    "INSERT INTO catalog_attribute_enum_option(id,attribute_id,code,active) VALUES (?,?,?,true)")){
                    stmt.setObject(1,java.util.UUID.randomUUID());
                    stmt.setObject(2,attrId);stmt.setString(3,"not_normalized");
                    stmt.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);
        }
    }
}
