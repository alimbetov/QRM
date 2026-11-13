package kz.zakaz.intelligence.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import kz.zakaz.catalog.*;
import kz.zakaz.deal.*;
import kz.zakaz.domain.types.*;
import kz.zakaz.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test-pg")
@TestPropertySource(properties="zakaz.ml.r2.invitation-evaluation.enabled=true")
class MlSellerInvitationPostgresIT {
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",pg::getJdbcUrl);
        r.add("spring.datasource.username",pg::getUsername);
        r.add("spring.datasource.password",pg::getPassword);
    }

    @Autowired MlSellerInvitationService invitation;
    @Autowired JdbcTemplate jdbc;
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerSkuRepository skus;
    @Autowired CatalogPublishingService publishing;
    @Autowired DealPersistenceService deals;
    @Autowired SellerMembershipRepository memberships;
    @Autowired SellerDeliveryAreaRepository areas;

    private record Scenario(UUID demandId,UUID sellerId,Instant expiration) {}
    private Scenario scenario(boolean publicListing,boolean member,boolean area) {
        String n=UUID.randomUUID().toString().replace("-","");
        String code=String.format("%02d%02d%03d",
            1+Integer.parseInt(n.substring(0,2),16)%99,
            1+Integer.parseInt(n.substring(2,4),16)%99,
            1+Integer.parseInt(n.substring(4,7),16)%999)+"0000000000000";
        var cat=categories.saveAndFlush(Category.create(code,ProductType.PHYSICAL,"Invites",null));
        var product=products.saveAndFlush(CanonicalProduct.create(cat,"Invites "+n));
        UUID seller=UUID.randomUUID();
        var listing=listings.saveAndFlush(SellerListing.draft(seller,product));
        skus.saveAndFlush(SellerSku.create(listing,"PCS",StockTrackingMode.TRACKED,
                                          MeasurementUnit.PCS));
        if(publicListing) publishing.publishForSeller(seller,listing.getId());
        if(member) memberships.saveAndFlush(SellerMembership.grant(
            "https://idp.example.test","owner-"+n,seller,SellerMembership.Role.SELLER_ADMIN));
        if(area) areas.saveAndFlush(SellerDeliveryArea.allow(seller,"Almaty"));
        Instant expires=Instant.now().plusSeconds(3600);
        var demand=new BuyerDemand(UUID.randomUUID(),UUID.randomUUID(),
            "Almaty",expires,List.of(new BuyerDemand.DemandLine(
                product.getId(),BigDecimal.ONE,null,MeasurementUnit.PCS)));
        deals.createDemand(demand,Instant.now());
        return new Scenario(demand.id(),seller,expires);
    }

    @Test void eligibleCandidateIsOnlyHistoricalEvidenceNotOutboundNotification() {
        var f=scenario(true,true,true);
        var first=invitation.evaluate(f.demandId(),f.sellerId(),1,Instant.now());
        assertThat(first.status()).isEqualTo("ELIGIBLE");
        assertThat(first.reasonCode()).isEqualTo("MATCHED");
        var retry=invitation.evaluate(f.demandId(),f.sellerId(),1,Instant.now());
        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ml_trade_event
            WHERE event_type='SELLER_INVITATION_EVALUATED' AND aggregate_id=?
            """,Integer.class,first.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ml_seller_invitation WHERE demand_id=? AND seller_id=?
            """,Integer.class,f.demandId(),f.sellerId())).isEqualTo(1);
    }

    @Test void failClosedOnMissingMemberNoListingOrTerritoryAndExpiredDemand() {
        var noMember=scenario(true,false,true);
        var a=invitation.evaluate(noMember.demandId(),noMember.sellerId(),1,Instant.now());
        assertThat(a.status()).isEqualTo("SUPPRESSED");
        assertThat(a.reasonCode()).isEqualTo("NO_ACTIVE_MEMBERSHIP");
        var privateListing=scenario(false,true,true);
        assertThat(invitation.evaluate(privateListing.demandId(),privateListing.sellerId(),
            1,Instant.now()).reasonCode()).isEqualTo("NO_PUBLIC_COMPATIBLE_SKU");
        var noArea=scenario(true,true,false);
        assertThat(invitation.evaluate(noArea.demandId(),noArea.sellerId(),
            1,Instant.now()).reasonCode()).isEqualTo("NO_FULFILLMENT_AREA");
        var expired=scenario(true,true,true);
        assertThat(invitation.evaluate(expired.demandId(),expired.sellerId(),
            1,expired.expiration().plusSeconds(1)).reasonCode()).isEqualTo("EXPIRED");
    }

    @Test void newPolicyVersionCreatesNewDecisionButOldHistoryCannotChange() {
        var f=scenario(true,true,true);
        var original=invitation.evaluate(f.demandId(),f.sellerId(),1,Instant.now());
        var updated=invitation.evaluate(f.demandId(),f.sellerId(),2,Instant.now());
        assertThat(original.id()).isNotEqualTo(updated.id());
        assertThatThrownBy(()->jdbc.update("""
            UPDATE ml_seller_invitation SET status='SUPPRESSED' WHERE id=?
            """,original.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ml_trade_event
            WHERE demand_id=? AND event_type='SELLER_INVITATION_EVALUATED'
            """,Integer.class,f.demandId())).isEqualTo(2);
    }

    @Test void competingWorkersRecordExactlyOneDecisionAndEvent() throws Exception {
        var f=scenario(true,true,true);
        var threads=Executors.newFixedThreadPool(6);
        try {
            var start=new CountDownLatch(1);
            List<Future<UUID>> calls=new ArrayList<>();
            for(int i=0;i<6;i++) {
                calls.add(threads.submit(()->{
                    start.await(5,TimeUnit.SECONDS);
                    return invitation.evaluate(f.demandId(),f.sellerId(),9,Instant.now()).id();
                }));
            }
            start.countDown();
            Set<UUID> ids=new HashSet<>();
            for(var call:calls) ids.add(call.get(20,TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
            UUID id=ids.iterator().next();
            assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ml_seller_invitation
                WHERE demand_id=? AND seller_id=? AND policy_version=9
                """,Integer.class,f.demandId(),f.sellerId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ml_trade_event
                WHERE event_type='SELLER_INVITATION_EVALUATED' AND aggregate_id=?
                """,Integer.class,id)).isEqualTo(1);
        } finally {
            threads.shutdownNow();
        }
    }
}
