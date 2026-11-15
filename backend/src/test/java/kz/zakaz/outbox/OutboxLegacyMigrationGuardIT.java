package kz.zakaz.outbox;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class OutboxLegacyMigrationGuardIT {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:17-alpine");
    @Test void existingOutboxHistoryRequiresReviewedSequenceAdoption() throws Exception {
        Flyway.configure()
            .dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword())
            .locations("classpath:db/migration").target(MigrationVersion.fromVersion("14"))
            .load().migrate();
        UUID order=UUID.randomUUID();
        try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword())){
            c.setAutoCommit(false);
            try(var s=c.prepareStatement(
                "INSERT INTO customer_order(id,public_number,seller_id,buyer_id,status,total_amount,delivery_fee,currency_code,created_at,version,expected_line_count) VALUES (?,'ZK-ABCD-9876',?,?,'PLACED',100,0,'KZT',now(),0,1)")){
                s.setObject(1,order);s.setObject(2,UUID.randomUUID());
                s.setObject(3,UUID.randomUUID());s.executeUpdate();
            }
            try(var s=c.prepareStatement(
                "INSERT INTO order_line(id,order_id,line_number,sku_id,quantity,unit,unit_price,line_total) VALUES (?, ?,1, ?,1,'PCS',100,100)")){
                s.setObject(1,UUID.randomUUID());s.setObject(2,order);
                s.setObject(3,UUID.randomUUID());s.executeUpdate();
            }
            try(var s=c.prepareStatement(
                "INSERT INTO order_outbox_event(id,aggregate_id,event_type,payload,occurred_at) VALUES (?,?,'ORDER_CONFIRMED','{}',now())")){
                s.setObject(1,UUID.randomUUID());s.setObject(2,order);s.executeUpdate();
            }
            c.commit();
        }
        assertThatThrownBy(()->Flyway.configure()
            .dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword())
            .locations("classpath:db/migration").load().migrate())
            .isInstanceOf(FlywayException.class)
            .hasMessageContaining("V15");
    }
}
