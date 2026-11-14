package kz.zakaz.intelligence.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import kz.zakaz.catalog.*;
import kz.zakaz.deal.*;
import kz.zakaz.domain.types.*;
import kz.zakaz.inventory.*;
import kz.zakaz.location.*;
import kz.zakaz.security.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
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
    @Autowired MlInvitationConsentService consent;
    @Autowired JdbcMlEventQueue deliveryQueue;
    @Autowired JdbcMlEventProjectionSink deliverySink;
    @BeforeEach void isolateDelivery() {
        jdbc.execute("TRUNCATE seller_bid_http_receipt,ml_event_projection_inbox,ml_seller_invitation_inbox,ml_event_delivery RESTART IDENTITY");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerSkuRepository skus;
    @Autowired CatalogPublishingService publishing;
    @Autowired DealPersistenceService deals;
    @Autowired SellerMembershipRepository memberships;
    @Autowired SellerDeliveryAreaRepository areas;
    @Autowired InventoryLocationRepository locations;
    @Autowired InventoryBalanceRepository balances;

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
        var sku=skus.saveAndFlush(SellerSku.create(listing,"PCS",StockTrackingMode.TRACKED,
                                          MeasurementUnit.PCS));
        if(publicListing) publishing.publishForSeller(seller,listing.getId());
        if(member) memberships.saveAndFlush(SellerMembership.grant(
            "https://idp.example.test","owner-"+n,seller,SellerMembership.Role.SELLER_ADMIN));
        if(area) areas.saveAndFlush(SellerDeliveryArea.allow(seller,"Almaty"));
        // The new eligibility policy requires an actionable, stocked origin;
        // the separate area=false negative uses a private warehouse.
        var origin=locations.saveAndFlush(InventoryLocation.create(
            seller,"STOCK-"+n,"ML warehouse","KZ-ALA","Almaty region",
            "Almaty","Test 1",LocationType.WAREHOUSE,null,false));
        balances.saveAndFlush(InventoryBalance.open(
            seller,origin.getId(),sku.getId(),new BigDecimal("5.000")));
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
    @Test void invitationsFailClosedWithoutBothExplicitOptIns() {
        var f=scenario(true,true,true);
        var candidate=invitation.evaluate(f.demandId(),f.sellerId(),1,Instant.now());
        assertThat(candidate.status()).isEqualTo("ELIGIBLE");
        var dispatcher=new MlEventDispatcher(deliveryQueue,deliverySink,"test-no-consent",
            10,3,java.time.Duration.ofSeconds(30));
        assertThat(dispatcher.pollOnce()).isEqualTo(1);
        assertThat(dispatcher.pollOnce()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ml_seller_invitation_inbox WHERE invitation_id=?
            """,Integer.class,candidate.id())).isZero();
        assertThat(deliveryQueue.health().get("delivered")).isEqualTo(2);
        // Already consumed events cannot be silently replayed as new invitations
        // when a user opts in after the fact.
        UUID buyer=jdbc.queryForObject(
            "SELECT buyer_id FROM buyer_demand_snapshot WHERE id=?",
            UUID.class,f.demandId());
        consent.setDemandSharing(buyer,f.demandId(),true);
        consent.setSellerInApp(f.sellerId(),true);
        assertThat(dispatcher.pollOnce()).isZero();
        assertThat(consent.listSellerInbox(f.sellerId(),24)).isEmpty();
    }

    @Test void optedInSellerGetsExactlyOnePrivateInboxItemAndRevocationHidesIt() {
        var f=scenario(true,true,true);
        UUID buyer=jdbc.queryForObject(
            "SELECT buyer_id FROM buyer_demand_snapshot WHERE id=?",
            UUID.class,f.demandId());
        consent.setDemandSharing(buyer,f.demandId(),true);
        consent.setSellerInApp(f.sellerId(),true);
        var candidate=invitation.evaluate(f.demandId(),f.sellerId(),1,Instant.now());
        var dispatcher=new MlEventDispatcher(deliveryQueue,deliverySink,"test-opted-in",
            10,3,java.time.Duration.ofSeconds(30));
        assertThat(dispatcher.pollOnce()).isEqualTo(1);
        assertThat(dispatcher.pollOnce()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM ml_seller_invitation_inbox WHERE invitation_id=?",
            Integer.class,candidate.id())).isEqualTo(1);
        assertThat(consent.listSellerInbox(f.sellerId(),24))
            .extracting(MlInvitationConsentService.InboxItem::invitationId)
            .containsExactly(candidate.id());

        consent.setDemandSharing(buyer,f.demandId(),false);
        assertThat(consent.listSellerInbox(f.sellerId(),24)).isEmpty();
        consent.setDemandSharing(buyer,f.demandId(),true);
        consent.setSellerInApp(f.sellerId(),false);
        assertThat(consent.listSellerInbox(f.sellerId(),24)).isEmpty();
        assertThatThrownBy(()->consent.setDemandSharing(
            UUID.randomUUID(),f.demandId(),true))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test void oneSidedOptInSuppressesInvitationUntilNewPolicyEvaluation() {
        var f=scenario(true,true,true);
        consent.setSellerInApp(f.sellerId(),true);
        UUID buyer=jdbc.queryForObject(
            "SELECT buyer_id FROM buyer_demand_snapshot WHERE id=?",
            UUID.class,f.demandId());
        var old=invitation.evaluate(f.demandId(),f.sellerId(),1,Instant.now());
        var worker=new MlEventDispatcher(deliveryQueue,deliverySink,
            "one-sided-test",10,3,java.time.Duration.ofSeconds(30));
        assertThat(worker.pollOnce()).isEqualTo(1);
        assertThat(worker.pollOnce()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ml_seller_invitation_inbox WHERE invitation_id=?
            """,Integer.class,old.id())).isZero();

        consent.setDemandSharing(buyer,f.demandId(),true);
        // Older events stay consumed; a new explicit policy decision is required.
        var updated=invitation.evaluate(f.demandId(),f.sellerId(),2,Instant.now());
        assertThat(updated.id()).isNotEqualTo(old.id());
        assertThat(worker.pollOnce()).isEqualTo(1);
        assertThat(consent.listSellerInbox(f.sellerId(),24))
            .extracting(MlInvitationConsentService.InboxItem::invitationId)
            .containsExactly(updated.id());
    }

}
