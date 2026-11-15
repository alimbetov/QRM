package kz.zakaz.deal;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class DealSnapshotAppendOnlyPostgresIT {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:17-alpine");
    @Test void signedOffHistoryCannotBeChangedByDirectSql() throws Exception {
        var flyway=Flyway.configure()
            .dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword())
            .locations("classpath:db/migration").load();
        flyway.migrate();
        try(var connection=DriverManager.getConnection(
                db.getJdbcUrl(),db.getUsername(),db.getPassword())) {
            UUID id=UUID.randomUUID(),buyer=UUID.randomUUID(),seller=UUID.randomUUID(),bidId=UUID.randomUUID();
            try(var ins=connection.prepareStatement(
                    "INSERT INTO buyer_demand_snapshot(id,buyer_id,city,expires_at,created_at,version) "+
                    "VALUES (?,?,'Almaty',now()+interval '1 hour',now(),0)")){
                ins.setObject(1,id);ins.setObject(2,buyer);
                assertThat(ins.executeUpdate()).isEqualTo(1);
            }
            try(var ins=connection.prepareStatement(
                    "INSERT INTO seller_bid_snapshot(id,demand_id,seller_id,revision,expires_at,submitted_at,version) "+
                    "VALUES (?,?,?,1,now()+interval '30 minutes',now(),0)")){
                ins.setObject(1,bidId);ins.setObject(2,id);ins.setObject(3,seller);
                assertThat(ins.executeUpdate()).isEqualTo(1);
            }
            for(var sql:List.of(
                    "UPDATE buyer_demand_snapshot SET city='Other' WHERE id=?",
                    "DELETE FROM buyer_demand_snapshot WHERE id=?",
                    "UPDATE seller_bid_snapshot SET revision=2 WHERE id=?",
                    "DELETE FROM seller_bid_snapshot WHERE id=?")) {
                UUID target=sql.contains("buyer_demand")?id:bidId;
                assertThatThrownBy(()->{
                    try(var statement=connection.prepareStatement(sql)){
                        statement.setObject(1,target);statement.executeUpdate();
                    }
                }).isInstanceOf(SQLException.class);
            }
            try(var stmt=connection.createStatement();
                var rs=stmt.executeQuery(
                    "SELECT count(*) FROM pg_trigger WHERE tgname IN "+
                    "('trg_demand_snapshot_immutable','trg_demand_line_snapshot_immutable',"+
                    "'trg_bid_snapshot_immutable','trg_bid_line_snapshot_immutable')")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(4);
            }
        }
    }
}
