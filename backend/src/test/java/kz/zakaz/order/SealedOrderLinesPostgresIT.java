package kz.zakaz.order;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class SealedOrderLinesPostgresIT {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:17-alpine");
    @Test void initialLinesSucceedButLateAppendAndMismatchedTotalFail() throws Exception {
        Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword())
            .locations("classpath:db/migration").load().migrate();
        UUID order=UUID.randomUUID(),line=UUID.randomUUID(),buyer=UUID.randomUUID(),seller=UUID.randomUUID();
        try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword())) {
            c.setAutoCommit(false);
            try(var s=c.prepareStatement(
                "INSERT INTO customer_order(id,public_number,seller_id,buyer_id,status,total_amount,delivery_fee,currency_code,created_at,version,expected_line_count) VALUES (?,'ZK-7M4P-9K2D',?,?,'PLACED',100.00,0.00,'KZT',now(),0,1)")){
                s.setObject(1,order);s.setObject(2,seller);s.setObject(3,buyer);s.executeUpdate();
            }
            try(var s=c.prepareStatement(
                "INSERT INTO order_line(id,order_id,line_number,sku_id,quantity,unit,unit_price,line_total) VALUES (?,?,1,?,1.000,'PCS',100.00,100.00)")){
                s.setObject(1,line);s.setObject(2,order);s.setObject(3,UUID.randomUUID());
                assertThat(s.executeUpdate()).isEqualTo(1);
            }
            c.commit();
            c.setAutoCommit(true);
            try(var s=c.prepareStatement(
                "INSERT INTO order_line(id,order_id,line_number,sku_id,quantity,unit,unit_price,line_total) VALUES (?,?,2,?,1.000,'PCS',0.00,0.00)")){
                s.setObject(1,UUID.randomUUID());s.setObject(2,order);s.setObject(3,UUID.randomUUID());
                assertThatThrownBy(s::executeUpdate).isInstanceOf(SQLException.class);
            }
            try(var s=c.prepareStatement(
                "UPDATE customer_order SET status='CONFIRMED',version=version+1 WHERE id=?")) {
                s.setObject(1,order);assertThat(s.executeUpdate()).isEqualTo(1);
            }
        }
    }

    @Test void incompleteOrderCannotCommitEvenWithCorrectHeaderAmount() throws Exception {
        Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword())
            .locations("classpath:db/migration").load().migrate();
        try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword())) {
            c.setAutoCommit(false);
            try(var s=c.prepareStatement(
                "INSERT INTO customer_order(id,public_number,seller_id,buyer_id,status,total_amount,delivery_fee,currency_code,created_at,version,expected_line_count) VALUES (?,'ZK-7M4P-9K2E',?,?,'PLACED',100,0,'KZT',now(),0,2)")) {
                s.setObject(1,UUID.randomUUID());s.setObject(2,UUID.randomUUID());
                s.setObject(3,UUID.randomUUID());s.executeUpdate();
            }
            assertThatThrownBy(c::commit).isInstanceOf(SQLException.class);
            c.rollback();
        }
    }
}
