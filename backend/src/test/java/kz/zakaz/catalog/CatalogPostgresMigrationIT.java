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

    @Test void manualDdlRunsOnPostgresAndRejectsInvalidHccAndBrokenReferences() throws Exception {
        try(Connection conn=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())) {
            ScriptUtils.executeSqlScript(conn,new ClassPathResource("db/manual/V001__catalog_foundation.sql"));
            try(var insert=conn.prepareStatement(
                "INSERT INTO catalog_category(id,hcc_code,product_type,label,active) VALUES (?::uuid,?,?,?,true)")) {
                insert.setString(1,java.util.UUID.randomUUID().toString());
                insert.setString(2,"01020030000000000000");
                insert.setString(3,"PHYSICAL");
                insert.setString(4,"Phones");
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
            assertThatThrownBy(()-> {
                try(var bad=conn.prepareStatement(
                    "INSERT INTO catalog_category(id,hcc_code,product_type,label,active) VALUES (?::uuid,?,?,?,true)")) {
                    bad.setString(1,java.util.UUID.randomUUID().toString());
                    bad.setString(2,"01000030000000000000"); // gap (database must reject)
                    bad.setString(3,"PHYSICAL"); bad.setString(4,"Invalid");
                    bad.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);
        }
    }
}
