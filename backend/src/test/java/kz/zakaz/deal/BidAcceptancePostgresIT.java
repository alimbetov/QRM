package kz.zakaz.deal;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class BidAcceptancePostgresIT {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17-alpine");
    @Test void allManagedMigrationsAndConstraintsExist() throws Exception {
        var f=Flyway.configure()
            .dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
            .locations("classpath:db/migration").load();
        f.migrate();
        try(var c=DriverManager.getConnection(postgres.getJdbcUrl(),
            postgres.getUsername(),postgres.getPassword())) {
            for(var table:List.of("bid_quote_snapshot","bid_acceptance","buyer_account_link",
                                  "seller_delivery_area")) {
                try(var s=c.prepareStatement("select count(*) from information_schema.tables "+
                    "where table_schema='public' and table_name=?")){
                    s.setString(1,table);
                    try(var rs=s.executeQuery()){
                        assertThat(rs.next()).isTrue();assertThat(rs.getInt(1)).isEqualTo(1);
                    }
                }
            }
            try(var stmt=c.createStatement();var rs=stmt.executeQuery(
                "select count(*) from flyway_schema_history where success=true")) {
                assertThat(rs.next()).isTrue();assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(8);
            }
        }
    }
}
