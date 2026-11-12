package kz.zakaz.deal;

import java.math.*;
import java.time.Instant;
import java.util.*;
import kz.zakaz.catalog.*;
import kz.zakaz.domain.types.*;
import kz.zakaz.location.*;
import kz.zakaz.order.*;
import kz.zakaz.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class BidAcceptanceFlowTest {
    static final Instant NOW=Instant.parse("2026-11-12T10:00:00Z");
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerSkuRepository skus;
    @Autowired CatalogPublishingService publishing;
    @Autowired InventoryLocationRepository locations;
    @Autowired BuyerAccountLinkRepository buyerLinks;
    @Autowired SellerDeliveryAreaRepository areas;
    @Autowired DealPersistenceService deals;
    @Autowired SellerBidSubmissionService bidding;
    @Autowired BidAcceptanceService accepting;
    @Autowired CustomerOrderRepository orders;
    @Autowired BidAcceptanceRepository acceptances;

    private record Seed(BuyerDemand demand,SellerBid bid,UUID buyer,UUID seller,UUID location,String subject) {}
    private Seed setup() {
        var category=categories.saveAndFlush(Category.create(
            "01"+String.format("%02d",1+Math.floorMod(UUID.randomUUID().hashCode(),99))
                +String.format("%03d",1+Math.floorMod(UUID.randomUUID().hashCode(),999))
                +"0000000000000",ProductType.PHYSICAL,"Phones",null));
        var product=products.saveAndFlush(CanonicalProduct.create(category,"Phone"));
        var seller=UUID.randomUUID();var buyer=UUID.randomUUID();
        var listing=listings.saveAndFlush(SellerListing.draft(seller,product));
        var sku=skus.saveAndFlush(SellerSku.create(listing,"T-1",StockTrackingMode.TRACKED,MeasurementUnit.PCS));
        publishing.publishForSeller(seller,listing.getId());
        var location=locations.saveAndFlush(InventoryLocation.create(seller,"WH-1","Warehouse",
            "KZ-ALA","Almaty region","Almaty","1 Test street",LocationType.SHOP,null,true));
        areas.saveAndFlush(SellerDeliveryArea.allow(seller,"Almaty"));
        var subject="buyer-"+UUID.randomUUID();
        buyerLinks.saveAndFlush(BuyerAccountLink.provision("https://idp.example.test",subject,buyer));
        var d=new BuyerDemand(UUID.randomUUID(),buyer,"Almaty",NOW.plusSeconds(3600),
            List.of(new BuyerDemand.DemandLine(product.getId(),new BigDecimal("2"),null,MeasurementUnit.PCS)));
        deals.createDemand(d,NOW);
        var bid=new SellerBid(UUID.randomUUID(),d.id(),seller,1,NOW.plusSeconds(1800),
            List.of(new SellerBid.BidLine(product.getId(),sku.getId(),
                new BigDecimal("2"),new BigDecimal("1000.00"))));
        bidding.submit(seller,bid,new SellerBidSubmissionService.QuoteRequest(
            SellerBidQuoteSnapshot.Method.DELIVERY,location.getId(),new BigDecimal("300.00")),NOW);
        return new Seed(d,bid,buyer,seller,location.getId(),subject);
    }
    private JwtAuthenticationToken buyerToken(String subject) {
        return new JwtAuthenticationToken(Jwt.withTokenValue("verified-by-spring-security")
            .header("alg","RS256").issuer("https://idp.example.test").subject(subject)
            .claim("aud",List.of("zakaz-api")).build(),List.of());
    }

    @Test void quotedDeliveryIncludedInOrderAndExactRetryReturnsSameOrder() {
        var s=setup(); var actor=buyerToken(s.subject());
        var accepted=accepting.accept(actor,s.demand.id(),s.bid.id(),"key-1",NOW);
        assertThat(accepted).isNotNull();
        var o=orders.findById(accepted).orElseThrow();
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PLACED);
        assertThat(o.getTotalAmount()).isEqualByComparingTo("2300.00");
        assertThat(o.getDeliveryFee()).isEqualByComparingTo("300.00");
        assertThat(o.getAcceptedBidId()).isEqualTo(s.bid.id());
        assertThat(accepting.accept(actor,s.demand.id(),s.bid.id(),"key-1",NOW)).isEqualTo(accepted);
        assertThat(acceptances.findAll()).hasSize(1);
    }

    @Test void mismatchedBuyerAndReusedKeyForDifferentBidAreRejected() {
        var s=setup();
        assertThatThrownBy(()->accepting.accept(buyerToken("intruder"),s.demand.id(),s.bid.id(),"k1",NOW))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        var actor=buyerToken(s.subject());
        accepting.accept(actor,s.demand.id(),s.bid.id(),"k1",NOW);
        assertThatThrownBy(()->accepting.accept(actor,s.demand.id(),UUID.randomUUID(),"k1",NOW))
            .isInstanceOf(IdempotencyConflictException.class);
    }
    @Test void staleOrSupersededBidCannotBeAccepted() {
        var s=setup();var actor=buyerToken(s.subject());
        assertThatThrownBy(()->accepting.accept(actor,s.demand.id(),s.bid.id(),"stale",NOW.plusSeconds(1900)))
            .isInstanceOf(IllegalStateException.class);
    }
}
