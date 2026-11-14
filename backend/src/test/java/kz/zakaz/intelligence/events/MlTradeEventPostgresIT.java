package kz.zakaz.intelligence.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import kz.zakaz.catalog.*;
import kz.zakaz.deal.*;
import kz.zakaz.domain.types.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

/**
 * ML-R2 PostgreSQL RED: trade event capture and invitation uniqueness must
 * survive real transactions, restart/replay and concurrent workers.
 * No synthetic/out-of-process event broker and no notification delivery here.
 */
@SpringBootTest @Testcontainers @ActiveProfiles("test-pg")
class MlTradeEventPostgresIT {
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",pg::getJdbcUrl);
        r.add("spring.datasource.username",pg::getUsername);
        r.add("spring.datasource.password",pg::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerSkuRepository skus;
    @Autowired CatalogPublishingService publishing;
    @Autowired DealPersistenceService deals;
    @Autowired TransactionTemplate transactions;

    private record Scenario(BuyerDemand demand,UUID productId,UUID skuId,UUID sellerId){}
    private Scenario scenario() {
        String n=UUID.randomUUID().toString().replace("-","");
        String code=String.format("%02d%02d%03d",
            1+Integer.parseInt(n.substring(0,2),16)%99,
            1+Integer.parseInt(n.substring(2,4),16)%99,
            1+Integer.parseInt(n.substring(4,7),16)%999)+"0000000000000";
        var category=categories.saveAndFlush(Category.create(
            code,ProductType.PHYSICAL,"ML-R2 trade",null));
        var product=products.saveAndFlush(CanonicalProduct.create(category,"R2 item "+n));
        UUID seller=UUID.randomUUID();
        var listing=listings.saveAndFlush(SellerListing.draft(seller,product));
        var sku=skus.saveAndFlush(SellerSku.create(
            listing,"SKU-1",StockTrackingMode.TRACKED,MeasurementUnit.PCS));
        publishing.publishForSeller(seller,listing.getId());
        var demand=new BuyerDemand(UUID.randomUUID(),UUID.randomUUID(),"Almaty",
            Instant.now().plusSeconds(3600),
            List.of(new BuyerDemand.DemandLine(product.getId(),BigDecimal.ONE,null,
                                               MeasurementUnit.PCS)));
        deals.createDemand(demand,Instant.now());
        return new Scenario(demand,product.getId(),sku.getId(),seller);
    }

    @Test void demandCreationAndBidRevisionProduceSingleImmutableVersionedEvents() {
        var s=scenario();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ml_trade_event
            WHERE event_type='DEMAND_CREATED' AND aggregate_id=? AND source_revision=1
            """,Integer.class,s.demand().id())).isEqualTo(1);

        var first=new SellerBid(UUID.randomUUID(),s.demand().id(),s.sellerId(),1,
            Instant.now().plusSeconds(3000),
            List.of(new SellerBid.BidLine(s.productId(),s.skuId(),BigDecimal.ONE,
                                         new BigDecimal("100.00"))));
        deals.submitBid(s.sellerId(),first,Instant.now());
        var second=new SellerBid(UUID.randomUUID(),s.demand().id(),s.sellerId(),2,
            Instant.now().plusSeconds(3000),
            List.of(new SellerBid.BidLine(s.productId(),s.skuId(),BigDecimal.ONE,
                                         new BigDecimal("99.00"))));
        deals.submitBid(s.sellerId(),second,Instant.now());
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ml_trade_event
            WHERE demand_id=? AND event_type IN ('BID_SUBMITTED','BID_REVISED')
            """,Integer.class,s.demand().id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ml_trade_event
            WHERE demand_id=? AND event_type='BID_REVISED' AND source_revision=2
            """,Integer.class,s.demand().id())).isEqualTo(1);
    }

    @Test void eventsAreAppendOnlyAndDuplicateBusinessRevisionCannotBeInserted() {
        var s=scenario();
        UUID id=jdbc.queryForObject("""
            SELECT event_id FROM ml_trade_event
            WHERE event_type='DEMAND_CREATED' AND demand_id=?
            """,UUID.class,s.demand().id());
        assertThatThrownBy(()->jdbc.update("DELETE FROM ml_trade_event WHERE event_id=?",id))
            .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("""
            INSERT INTO ml_trade_event(event_id,aggregate_type,aggregate_id,event_type,
                source_revision,demand_id,schema_version,occurred_at)
            VALUES (?,'DEMAND',?,'DEMAND_CREATED',1,?,1,clock_timestamp())
            """,UUID.randomUUID(),s.demand().id(),s.demand().id()))
            .isInstanceOf(DataAccessException.class);
    }

    @Test void databaseDeduplicatesSellerInvitationPerDemandAndPolicy() {
        var s=scenario();
        UUID seller=s.sellerId(),demand=s.demand().id();
        UUID first=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO ml_seller_invitation(
                id,demand_id,seller_id,policy_version,status,reason_code,evaluated_at)
            VALUES (?,?,?,1,'ELIGIBLE','MATCHED',clock_timestamp())
            """,first,demand,seller);
        assertThatThrownBy(()->jdbc.update("""
            INSERT INTO ml_seller_invitation(
                id,demand_id,seller_id,policy_version,status,reason_code,evaluated_at)
            VALUES (?,?,?,1,'ELIGIBLE','MATCHED',clock_timestamp())
            """,UUID.randomUUID(),demand,seller))
            .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ml_trade_event
            WHERE event_type='SELLER_INVITATION_EVALUATED' AND demand_id=? AND seller_id=?
            """,Integer.class,demand,seller)).isEqualTo(1);
    }
    @Test void rolledBackDemandDoesNotLeaveGhostTrainingEvent() {
        var sample=scenario();
        var another=new BuyerDemand(UUID.randomUUID(),UUID.randomUUID(),
            "Almaty",Instant.now().plusSeconds(3600),
            List.of(new BuyerDemand.DemandLine(
                sample.productId(),BigDecimal.ONE,null,MeasurementUnit.PCS)));
        assertThatThrownBy(()->transactions.execute(status->{
            deals.createDemand(another,Instant.now());
            assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ml_trade_event
                WHERE demand_id=? AND event_type='DEMAND_CREATED'
                """,Integer.class,another.id())).isEqualTo(1);
            throw new IllegalStateException("Force business rollback");
        })).isInstanceOf(IllegalStateException.class)
           .hasMessageContaining("Force business rollback");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ml_trade_event WHERE demand_id=?
            """,Integer.class,another.id())).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM buyer_demand_snapshot WHERE id=?
            """,Integer.class,another.id())).isZero();
    }

}
