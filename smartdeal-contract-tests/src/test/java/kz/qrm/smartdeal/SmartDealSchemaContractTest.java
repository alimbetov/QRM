package kz.qrm.smartdeal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Isolated SCHEMA contract tests. They do not prove the absent R2DBC application works.
 * Uses real PostgreSQL, not H2. Requires a reachable Docker daemon.
 */
final class SmartDealSchemaContractTest {
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
        try (Connection c = connection(); Statement st = c.createStatement()) {
            String sql;
            try (var in = SmartDealSchemaContractTest.class.getResourceAsStream("/db/fixture.sql")) {
                if (in == null) throw new IllegalStateException("Missing db/fixture.sql");
                sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            for (String statement : sql.split(";")) {
                if (!statement.isBlank()) st.execute(statement);
            }
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(POSTGRES::stop));
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @BeforeEach
    void reset() throws SQLException {
        try (Connection c = connection(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE smartdeal_conversion, match_invitation, seller_bid, buyer_demand CASCADE");
        }
    }

    private static void createDemand(Connection c, UUID demand) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO buyer_demand(id,buyer_user_id,category_id,quantity,unit_code,city_code,state,expires_at) " +
                        "VALUES(?,?, 'MIX',100,'KG','ALMATY','OPEN',now()+interval '7 days')")) {
            ps.setObject(1, demand);
            ps.setObject(2, UUID.randomUUID());
            assertEquals(1, ps.executeUpdate());
        }
    }

    private static void bid(Connection c, UUID bid, UUID demand, UUID seller, int revision,
                            String state, String quantity, String unit, String price, String shipping)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO seller_bid(id,demand_id,seller_id,listing_id,bid_version,status,quantity,unit_code,unit_price_kzt,shipping_kzt,valid_until) " +
                        "VALUES(?,?,?,?,?,?,?,?,CAST(? AS numeric),CAST(? AS numeric),now()+interval '2 days')")) {
            ps.setObject(1, bid);
            ps.setObject(2, demand);
            ps.setObject(3, seller);
            ps.setObject(4, UUID.randomUUID());
            ps.setInt(5, revision);
            ps.setString(6, state);
            ps.setBigDecimal(7, new java.math.BigDecimal(quantity));
            ps.setString(8, unit);
            ps.setString(9, price);
            ps.setString(10, shipping);
            ps.executeUpdate();
        }
    }

    @Test
    void oneCurrentRevisionPerSellerAndDemand() throws SQLException {
        try (Connection c = connection()) {
            UUID demand = UUID.randomUUID(), seller = UUID.randomUUID();
            createDemand(c, demand);
            bid(c, UUID.randomUUID(), demand, seller, 1, "ACTIVE", "100", "KG", "460", "2000");
            assertThrows(SQLException.class, () ->
                    bid(c, UUID.randomUUID(), demand, seller, 2, "ACTIVE", "100", "KG", "450", "2100"));
        }
    }

    @Test
    void differentSellersMayBidOnSameDemand() throws SQLException {
        try (Connection c = connection()) {
            UUID demand = UUID.randomUUID();
            createDemand(c, demand);
            bid(c, UUID.randomUUID(), demand, UUID.randomUUID(), 1, "ACTIVE", "100", "KG", "460", "2000");
            assertDoesNotThrow(() ->
                    bid(c, UUID.randomUUID(), demand, UUID.randomUUID(), 1, "ACTIVE", "100", "KG", "470", null));
        }
    }

    @Test
    void supersededRevisionDoesNotBlockNewCurrentRevision() throws SQLException {
        try (Connection c = connection()) {
            UUID demand = UUID.randomUUID(), seller = UUID.randomUUID(), old = UUID.randomUUID();
            createDemand(c, demand);
            bid(c, old, demand, seller, 1, "ACTIVE", "100", "KG", "460", "2000");
            try (PreparedStatement ps = c.prepareStatement("UPDATE seller_bid SET status='SUPERSEDED' WHERE id=?")) {
                ps.setObject(1, old);
                assertEquals(1, ps.executeUpdate());
            }
            assertDoesNotThrow(() ->
                    bid(c, UUID.randomUUID(), demand, seller, 2, "ACTIVE", "100", "KG", "445", "2300"));
        }
    }

    @Test
    void cannotSelectTwoWinningBidsFromDifferentSellers() throws SQLException {
        try (Connection c = connection()) {
            UUID demand = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
            createDemand(c, demand);
            bid(c, a, demand, UUID.randomUUID(), 1, "SELECTED", "100", "KG", "460", "2000");
            assertThrows(SQLException.class, () ->
                    bid(c, b, demand, UUID.randomUUID(), 1, "SELECTED", "100", "KG", "450", "1800"));
        }
    }

    @Test
    void demandCannotPointToBidFromAnotherDemand() throws SQLException {
        try (Connection c = connection()) {
            UUID demandA = UUID.randomUUID(), demandB = UUID.randomUUID(), bidB = UUID.randomUUID();
            createDemand(c, demandA);
            createDemand(c, demandB);
            bid(c, bidB, demandB, UUID.randomUUID(), 1, "ACTIVE", "100", "KG", "460", null);
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE buyer_demand SET selected_bid_id=? WHERE id=?")) {
                ps.setObject(1, bidB);
                ps.setObject(2, demandA);
                ps.executeUpdate();
            }
            assertThrows(SQLException.class, c::commit);
            c.rollback();
        }
    }

    @Test
    void conversionAndInvitationsAreUnique() throws SQLException {
        try (Connection c = connection()) {
            UUID demand = UUID.randomUUID(), bid = UUID.randomUUID(), seller = UUID.randomUUID();
            createDemand(c, demand);
            bid(c, bid, demand, seller, 1, "SELECTED", "100", "KG", "460", "2000");
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO smartdeal_conversion(demand_id,accepted_bid_id,negotiation_id,order_id) VALUES(?,?,?,?)")) {
                ps.setObject(1, demand);
                ps.setObject(2, bid);
                ps.setObject(3, UUID.randomUUID());
                ps.setObject(4, UUID.randomUUID());
                ps.executeUpdate();
            }
            assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO smartdeal_conversion(demand_id,accepted_bid_id,negotiation_id,order_id) VALUES(?,?,?,?)")) {
                    ps.setObject(1, demand);
                    ps.setObject(2, bid);
                    ps.setObject(3, UUID.randomUUID());
                    ps.setObject(4, UUID.randomUUID());
                    ps.executeUpdate();
                }
            });
            UUID inviter = UUID.randomUUID();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO match_invitation(id,demand_id,seller_id,listing_id) VALUES(?,?,?,?)")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, demand);
                ps.setObject(3, inviter);
                ps.setObject(4, UUID.randomUUID());
                ps.executeUpdate();
            }
            assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO match_invitation(id,demand_id,seller_id,listing_id) VALUES(?,?,?,?)")) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, demand);
                    ps.setObject(3, inviter);
                    ps.setObject(4, UUID.randomUUID());
                    ps.executeUpdate();
                }
            });
        }
    }

    @Test
    void invalidBidPriceOrQuantityFails() throws SQLException {
        try (Connection c = connection()) {
            UUID demand = UUID.randomUUID();
            createDemand(c, demand);
            assertThrows(SQLException.class, () ->
                    bid(c, UUID.randomUUID(), demand, UUID.randomUUID(), 1, "ACTIVE", "-1", "KG", "10", null));
            assertThrows(SQLException.class, () ->
                    bid(c, UUID.randomUUID(), demand, UUID.randomUUID(), 1, "ACTIVE", "100", "KG", "-1", null));
            assertThrows(SQLException.class, () ->
                    bid(c, UUID.randomUUID(), demand, UUID.randomUUID(), 1, "ACTIVE", "100", "KG", "10", "-1"));
        }
    }
}
