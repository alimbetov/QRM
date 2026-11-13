package kz.zakaz.inventory;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class InventoryRelationalIntegrityPostgresIT {
    @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:17-alpine");

    static void exec(Connection c,String sql,Object... args) throws SQLException {
        try(var p=c.prepareStatement(sql)) {
            for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);
            p.executeUpdate();
        }
    }

    @Test void databaseRejectsCrossTenantInventoryAndMismatchedReservationLinks() throws Exception {
        Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword())
            .locations("classpath:db/migration").load().migrate();
        UUID sellerA=UUID.randomUUID(),sellerB=UUID.randomUUID(),
            locationA=UUID.randomUUID(), locationB=UUID.randomUUID(),
            category=UUID.randomUUID(), product=UUID.randomUUID(),
            listingA=UUID.randomUUID(),listingB=UUID.randomUUID(),
            skuA=UUID.randomUUID(),skuB=UUID.randomUUID(),
            orderA=UUID.randomUUID(),orderB=UUID.randomUUID(),
            lineA=UUID.randomUUID(),lineB=UUID.randomUUID();
        try(Connection c=DriverManager.getConnection(
                db.getJdbcUrl(),db.getUsername(),db.getPassword())) {
            exec(c,"INSERT INTO catalog_category(id,hcc_code,product_type,label,active) VALUES (?,'01020300000000000000','PHYSICAL','Devices',true)",category);
            exec(c,"INSERT INTO catalog_product(id,category_id,canonical_name) VALUES (?,?,'Device')",product,category);
            exec(c,"INSERT INTO catalog_listing(id,seller_id,product_id,status) VALUES (?,?,?,'DRAFT')",listingA,sellerA,product);
            exec(c,"INSERT INTO catalog_listing(id,seller_id,product_id,status) VALUES (?,?,?,'DRAFT')",listingB,sellerB,product);
            exec(c,"INSERT INTO catalog_sku(id,listing_id,seller_sku_code,tracking_mode,sales_unit) VALUES (?,?,'A','TRACKED','PCS')",skuA,listingA);
            exec(c,"INSERT INTO catalog_sku(id,listing_id,seller_sku_code,tracking_mode,sales_unit) VALUES (?,?,'B','TRACKED','PCS')",skuB,listingB);
            exec(c,"INSERT INTO inventory_location(id,seller_id,code,name,region_code,region_name,city,address_line,location_type,public_pickup_enabled,active) VALUES (?,?,'L1','Wh','KZ-ALA','Almaty','Almaty','1','SHOP',true,true)",locationA,sellerA);
            exec(c,"INSERT INTO inventory_location(id,seller_id,code,name,region_code,region_name,city,address_line,location_type,public_pickup_enabled,active) VALUES (?,?,'L2','Wh','KZ-ALA','Almaty','Almaty','2','SHOP',true,true)",locationB,sellerB);

            assertThatThrownBy(()->exec(c,"INSERT INTO inventory_balance(id,seller_id,location_id,sku_id,on_hand,reserved) VALUES (?,?,?,?,1,0)",
                UUID.randomUUID(),sellerA,locationB,skuA)).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->exec(c,"INSERT INTO inventory_balance(id,seller_id,location_id,sku_id,on_hand,reserved) VALUES (?,?,?,?,1,0)",
                UUID.randomUUID(),sellerA,locationA,skuB)).isInstanceOf(SQLException.class);

            exec(c,"INSERT INTO inventory_balance(id,seller_id,location_id,sku_id,on_hand,reserved) VALUES (?,?,?,?,2,0)",
                UUID.randomUUID(),sellerA,locationA,skuA);

            exec(c,"INSERT INTO customer_order(id,public_number,seller_id,buyer_id,status,total_amount,delivery_fee,currency_code,created_at,version,expected_line_count) VALUES (?,'ZK-1234-ABCD',?,?,'PLACED',100,0,'KZT',now(),0,1)",
                orderA,sellerA,UUID.randomUUID());
            exec(c,"INSERT INTO customer_order(id,public_number,seller_id,buyer_id,status,total_amount,delivery_fee,currency_code,created_at,version,expected_line_count) VALUES (?,'ZK-1234-ABCE',?,?,'PLACED',100,0,'KZT',now(),0,1)",
                orderB,sellerB,UUID.randomUUID());
            exec(c,"INSERT INTO order_line(id,order_id,line_number,sku_id,quantity,unit,unit_price,line_total) VALUES (?,?,1,?,1,'PCS',100,100)",lineA,orderA,skuA);
            exec(c,"INSERT INTO order_line(id,order_id,line_number,sku_id,quantity,unit,unit_price,line_total) VALUES (?,?,1,?,1,'PCS',100,100)",lineB,orderB,skuB);
            String insert="INSERT INTO inventory_reservation(id,order_id,order_line_id,seller_id,location_id,sku_id,quantity,status) VALUES (?,?,?,?,?,?,1,'RESERVED')";
            assertThatThrownBy(()->exec(c,insert,UUID.randomUUID(),orderB,lineA,sellerA,locationA,skuA))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(()->exec(c,insert,UUID.randomUUID(),orderA,lineA,sellerB,locationA,skuA))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(()->exec(c,insert,UUID.randomUUID(),orderA,lineA,sellerA,locationA,skuB))
                .isInstanceOf(SQLException.class);
        }
    }
}
