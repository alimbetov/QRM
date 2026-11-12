package kz.zakaz.inventory;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class OrderLineIntegrityPostgresIT {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:17-alpine");

    @Test void databaseRejectsDirectEconomicOrderMutation() throws Exception {
        Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword())
            .locations("classpath:db/migration").load().migrate();
        try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword())){
            UUID order=UUID.randomUUID(),seller=UUID.randomUUID(),buyer=UUID.randomUUID(),
                 line=UUID.randomUUID(),sku=UUID.randomUUID();
            var cols="id,public_number,seller_id,buyer_id,status,total_amount,delivery_fee,currency_code,created_at,version";
            try(var stmt=c.prepareStatement("INSERT INTO customer_order("+cols+
                ") VALUES (?,'ZK-7M4P-9K2D',?,?,'PLACED',100.00,0.00,'KZT',now(),0)")){
                stmt.setObject(1,order);stmt.setObject(2,seller);stmt.setObject(3,buyer);
                stmt.executeUpdate();
            }
            try(var stmt=c.prepareStatement("INSERT INTO order_line(id,order_id,line_number,sku_id,"+
                "quantity,unit,unit_price,line_total) VALUES (?,?,1,?,1.000,'PCS',100.00,100.00)")){
                stmt.setObject(1,line);stmt.setObject(2,order);stmt.setObject(3,sku);
                stmt.executeUpdate();
            }
            for(var sql:List.of(
                "UPDATE customer_order SET total_amount=10.00 WHERE id=?",
                "UPDATE order_line SET unit_price=10.00 WHERE id=?",
                "DELETE FROM order_line WHERE id=?")) {
                var target=sql.contains("customer_order")?order:line;
                assertThatThrownBy(()->{
                    try(var stmt=c.prepareStatement(sql)){
                        stmt.setObject(1,target);stmt.executeUpdate();
                    }
                }).isInstanceOf(SQLException.class);
            }
            try(var stmt=c.prepareStatement(
                "UPDATE customer_order SET status='CONFIRMED',version=version+1 WHERE id=?")){
                stmt.setObject(1,order);
                assertThat(stmt.executeUpdate()).isEqualTo(1);
            }
        }
    }
}
