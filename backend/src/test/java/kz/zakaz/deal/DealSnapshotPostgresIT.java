package kz.zakaz.deal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import kz.zakaz.catalog.*;
import kz.zakaz.domain.types.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test-pg") @Testcontainers
class DealSnapshotPostgresIT {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);
        r.add("spring.datasource.username",postgres::getUsername);
        r.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired DealPersistenceService deals;
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerSkuRepository skus;
    @Autowired CatalogPublishingService publisher;
    @Autowired SellerBidSnapshotRepository bids;

    @Test void flywayCreatesDealTablesAndBidSnapshotSurvivesRehydration() {
        var category=categories.saveAndFlush(Category.create(
            "02010010000000000000",ProductType.PHYSICAL,"Phones",null));
        var product=products.saveAndFlush(CanonicalProduct.create(category,"Phone"));
        UUID seller=UUID.randomUUID(),buyer=UUID.randomUUID();
        var listing=listings.saveAndFlush(SellerListing.draft(seller,product));
        var sku=skus.saveAndFlush(SellerSku.create(listing,"SKU-1",StockTrackingMode.TRACKED));
        publisher.publishForSeller(seller,listing.getId());
        var now=Instant.parse("2026-11-12T10:00:00Z");
        var d=new BuyerDemand(UUID.randomUUID(),buyer,"Алматы",now.plusSeconds(7200),
            List.of(new BuyerDemand.DemandLine(product.getId(),BigDecimal.ONE,null)));
        deals.createDemand(d,now);
        var offer=new SellerBid(UUID.randomUUID(),d.id(),seller,1,now.plusSeconds(600),
            List.of(new SellerBid.BidLine(product.getId(),sku.getId(),BigDecimal.ONE,
                new BigDecimal("1200.00"))));
        deals.submitBid(seller,offer,now);
        assertThat(deals.getBid(offer.id())).isEqualTo(offer);
        assertThat(deals.getDemand(d.id())).isEqualTo(d);
    }
}
