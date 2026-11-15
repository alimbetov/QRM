package kz.zakaz.intelligence.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import kz.zakaz.catalog.*;
import kz.zakaz.deal.*;
import kz.zakaz.domain.types.*;
import kz.zakaz.inventory.*;
import kz.zakaz.location.*;
import kz.zakaz.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

/**
 * ML-R2 hardening: a seller invitation must be actionable under the same
 * FULL basket / single fulfillment origin / free tracked inventory rules
 * that the real invitation -> SellerBid workspace enforces.
 *
 * These tests deliberately use PostgreSQL + authoritative JPA facts.
 */
@SpringBootTest @Testcontainers @ActiveProfiles("test-pg")
@TestPropertySource(properties="zakaz.ml.r2.invitation-evaluation.enabled=true")
class MlInvitationEligibilityParityPostgresIT {
    @Container static PostgreSQLContainer<?> pg =
        new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",pg::getJdbcUrl);
        r.add("spring.datasource.username",pg::getUsername);
        r.add("spring.datasource.password",pg::getPassword);
    }
    @Autowired MlSellerInvitationService evaluator;
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerSkuRepository skus;
    @Autowired CatalogPublishingService publisher;
    @Autowired SellerMembershipRepository memberships;
    @Autowired SellerDeliveryAreaRepository areas;
    @Autowired InventoryLocationRepository locations;
    @Autowired InventoryBalanceRepository balances;

    private record Fixture(UUID demandId,UUID sellerId) {}

    private Fixture fixture(boolean secondSku,boolean splitOrigins,
                            BigDecimal firstFree,BigDecimal secondFree) {
        String suffix=UUID.randomUUID().toString().replace("-","");
        String hcc=String.format("%02d%02d%03d",
            1+Integer.parseInt(suffix.substring(0,2),16)%99,
            1+Integer.parseInt(suffix.substring(2,4),16)%99,
            1+Integer.parseInt(suffix.substring(4,7),16)%999)+"0000000000000";
        var category=categories.saveAndFlush(Category.create(
            hcc,ProductType.PHYSICAL,"Parity "+suffix,null));
        var phone=products.saveAndFlush(CanonicalProduct.create(category,"Phone "+suffix));
        var charger=products.saveAndFlush(CanonicalProduct.create(category,"Charger "+suffix));
        UUID seller=UUID.randomUUID();
        memberships.saveAndFlush(SellerMembership.grant("https://idp.example.test",
            "parity-"+suffix,seller,SellerMembership.Role.SELLER_ADMIN));
        areas.saveAndFlush(SellerDeliveryArea.allow(seller,"Almaty"));
        var origin=locations.saveAndFlush(InventoryLocation.create(seller,
            "ORIGIN-"+suffix,"Primary location","KZ-ALA","Almaty",
            "Almaty","Test 1",LocationType.SHOP,null,true));
        var listing=listings.saveAndFlush(SellerListing.draft(seller,phone));
        var primarySku=skus.saveAndFlush(SellerSku.create(
            listing,"PHONE",StockTrackingMode.TRACKED,MeasurementUnit.PCS));
        publisher.publishForSeller(seller,listing.getId());
        balances.saveAndFlush(InventoryBalance.open(seller,origin.getId(),
            primarySku.getId(),firstFree));

        if(secondSku) {
            var another=listings.saveAndFlush(SellerListing.draft(seller,charger));
            var secondarySku=skus.saveAndFlush(SellerSku.create(
                another,"CHARGER",StockTrackingMode.TRACKED,MeasurementUnit.PCS));
            publisher.publishForSeller(seller,another.getId());
            var secondOrigin=splitOrigins?
                locations.saveAndFlush(InventoryLocation.create(seller,
                    "OTHER-"+suffix,"Second location","KZ-ALA","Almaty",
                    "Almaty","Test 2",LocationType.SHOP,null,true)):origin;
            balances.saveAndFlush(InventoryBalance.open(seller,secondOrigin.getId(),
                secondarySku.getId(),secondFree));
        }
        UUID demandId=UUID.randomUUID();
        // Both lines persisted as one immutable real demand, not injected afterwards.
        var demand=new BuyerDemand(demandId,UUID.randomUUID(),"Almaty",
            Instant.now().plusSeconds(3600),List.of(
                new BuyerDemand.DemandLine(phone.getId(),BigDecimal.ONE,null,MeasurementUnit.PCS),
                new BuyerDemand.DemandLine(charger.getId(),BigDecimal.ONE,null,MeasurementUnit.PCS)));
        // Invoke normal demand persistence through the real Spring service.
        deals.createDemand(demand,Instant.now());
        return new Fixture(demandId,seller);
    }

    @Autowired DealPersistenceService deals;

    @Test void aSingleMatchingSkuCannotQualifyAnIncompleteBasket() {
        var f=fixture(false,false,new BigDecimal("5.000"),BigDecimal.ZERO);
        var decision=evaluator.evaluate(f.demandId(),f.sellerId(),1,Instant.now());
        assertThat(decision.status()).isEqualTo("SUPPRESSED");
        assertThat(decision.reasonCode()).isEqualTo("NO_PUBLIC_COMPATIBLE_SKU");
    }

    @Test void fullCatalogWithoutFreeInventoryMustNotQualify() {
        var f=fixture(true,false,BigDecimal.ZERO,new BigDecimal("5.000"));
        var decision=evaluator.evaluate(f.demandId(),f.sellerId(),1,Instant.now());
        assertThat(decision.status()).isEqualTo("SUPPRESSED");
        assertThat(decision.reasonCode()).isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test void splitInventoryAcrossOriginsCannotProduceAnUnfulfillableBid() {
        var f=fixture(true,true,new BigDecimal("5.000"),new BigDecimal("5.000"));
        var decision=evaluator.evaluate(f.demandId(),f.sellerId(),1,Instant.now());
        assertThat(decision.status()).isEqualTo("SUPPRESSED");
        assertThat(decision.reasonCode()).isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test void allLinesWithStockAtOneUsableOriginAreEligible() {
        var f=fixture(true,false,new BigDecimal("5.000"),new BigDecimal("2.000"));
        var decision=evaluator.evaluate(f.demandId(),f.sellerId(),1,Instant.now());
        assertThat(decision.status()).isEqualTo("ELIGIBLE");
        assertThat(decision.reasonCode()).isEqualTo("MATCHED");
    }
}
