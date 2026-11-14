package kz.zakaz.deal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import kz.zakaz.catalog.*;
import kz.zakaz.domain.types.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest @ActiveProfiles("test")
@Import({DealPersistenceService.class,CatalogSkuFactsResolver.class,CatalogPublishingService.class})
class DealPersistenceH2Test {
    static final Instant NOW=Instant.parse("2026-11-12T10:00:00Z");
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerSkuRepository skus;
    @Autowired CatalogPublishingService publishing;
    @Autowired DealPersistenceService deals;
    @Autowired BuyerDemandSnapshotRepository demands;
    @Autowired SellerBidSnapshotRepository bids;

    private record Fixture(UUID productId,UUID sellerId,UUID skuId) {}
    private Fixture catalog() {
        var c=categories.saveAndFlush(Category.create(
            "01020030000000000000",ProductType.PHYSICAL,"Phones",null));
        var product=products.saveAndFlush(CanonicalProduct.create(c,"Galaxy"));
        UUID seller=UUID.randomUUID();
        var listing=listings.saveAndFlush(SellerListing.draft(seller,product));
        var sku=skus.saveAndFlush(SellerSku.create(listing,"GALAXY",StockTrackingMode.TRACKED));
        publishing.publishForSeller(seller,listing.getId());
        return new Fixture(product.getId(),seller,sku.getId());
    }
    private BuyerDemand demand(UUID product,UUID buyer) {
        return new BuyerDemand(UUID.randomUUID(),buyer,"Алматы",NOW.plusSeconds(3600),
            List.of(new BuyerDemand.DemandLine(product,new BigDecimal("2"),new BigDecimal("200000.00"))));
    }
    private SellerBid bid(UUID demandId,Fixture f,int revision) {
        return new SellerBid(UUID.randomUUID(),demandId,f.sellerId(),revision,NOW.plusSeconds(1800),
            List.of(new SellerBid.BidLine(f.productId(),f.skuId(),new BigDecimal("2"),new BigDecimal("145000.00"))));
    }
    @Test void persistsBuyerDemandAndValidatedImmutableSellerBid() {
        var f=catalog();
        var d=demand(f.productId(),UUID.randomUUID());
        deals.createDemand(d,NOW);
        var first=bid(d.id(),f,1);
        deals.submitBid(f.sellerId(),first,NOW);
        assertThat(demands.findById(d.id()).orElseThrow().toDomain().lines())
            .containsExactlyElementsOf(d.lines());
        assertThat(bids.findById(first.id()).orElseThrow().toDomain()).isEqualTo(first);
        var newer=bid(d.id(),f,2);
        deals.submitBid(f.sellerId(),newer,NOW);
        assertThat(bids.findById(first.id()).orElseThrow().toDomain()).isEqualTo(first);
        assertThat(bids.findById(newer.id()).orElseThrow().toDomain()).isEqualTo(newer);
    }
    @Test void rejectsForgedSellerAndNonContiguousRevision() {
        var f=catalog(); var d=demand(f.productId(),UUID.randomUUID());
        deals.createDemand(d,NOW);
        var offer=bid(d.id(),f,1);
        assertThatThrownBy(()->deals.submitBid(UUID.randomUUID(),offer,NOW))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->deals.submitBid(f.sellerId(),bid(d.id(),f,2),NOW))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("revision");
        assertThat(bids.findAll()).isEmpty();
    }
    @Test void refusesSkuThatBelongsToAnotherCanonicalProduct() {
        var f=catalog();var other=products.saveAndFlush(CanonicalProduct.create(
            categories.findAll().getFirst(),"Tablet"));
        var d=demand(other.getId(),UUID.randomUUID());
        deals.createDemand(d,NOW);
        var wrong=new SellerBid(UUID.randomUUID(),d.id(),f.sellerId(),1,NOW.plusSeconds(120),
            List.of(new SellerBid.BidLine(other.getId(),f.skuId(),new BigDecimal("2"),
                new BigDecimal("1000.00"))));
        assertThatThrownBy(()->deals.submitBid(f.sellerId(),wrong,NOW))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("canonical");
        assertThat(bids.findAll()).isEmpty();
    }
    @Test void refusesExpiredDemandAndStaleBid() {
        var f=catalog();var d=demand(f.productId(),UUID.randomUUID());
        deals.createDemand(d,NOW);
        assertThatThrownBy(()->deals.submitBid(f.sellerId(),bid(d.id(),f,1),NOW.plusSeconds(3601)))
            .isInstanceOf(IllegalStateException.class);
    }
}
