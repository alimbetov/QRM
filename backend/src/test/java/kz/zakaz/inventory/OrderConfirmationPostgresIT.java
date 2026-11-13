package kz.zakaz.inventory;

import java.math.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import kz.zakaz.catalog.*;
import kz.zakaz.deal.*;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test-pg") @Testcontainers
class OrderConfirmationPostgresIT {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void settings(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",postgres::getJdbcUrl);
        r.add("spring.datasource.username",postgres::getUsername);
        r.add("spring.datasource.password",postgres::getPassword);
    }
    static final Instant NOW=Instant.parse("2026-11-12T10:00:00Z");
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerSkuRepository skus;
    @Autowired CatalogPublishingService publishing;
    @Autowired InventoryLocationRepository locations;
    @Autowired BuyerAccountLinkRepository buyers;
    @Autowired SellerMembershipRepository memberships;
    @Autowired SellerDeliveryAreaRepository areas;
    @Autowired DealPersistenceService deals;
    @Autowired SellerBidSubmissionService bidding;
    @Autowired BidAcceptanceService accepting;
    @Autowired OrderConfirmationService confirming;
    @Autowired InventoryBalanceRepository balances;
    @Autowired InventoryReservationRepository reservations;
    @Autowired OrderOutboxEventRepository events;
    @Autowired CustomerOrderRepository orders;
    @Autowired InventoryStockService stock;
    @Autowired OrderCancellationService cancellations;
    @Autowired InventoryMovementRepository movements;

    record Market(UUID sellerId,UUID locationId,String sellerSubject,List<UUID> products,
                  List<UUID> skus) {}
    Market market(int numberOfSkus,List<BigDecimal> stock){
        var seller=UUID.randomUUID();var subject="merchant-"+UUID.randomUUID();
        memberships.saveAndFlush(SellerMembership.grant(
            "https://idp.example.test",subject,seller,SellerMembership.Role.SELLER_ADMIN));
        var location=locations.saveAndFlush(InventoryLocation.create(seller,"WH-"+UUID.randomUUID(),
            "Test shop","KZ-ALA","Almaty region","Almaty","Test street 1",
            LocationType.SHOP,null,true));
        areas.saveAndFlush(SellerDeliveryArea.allow(seller,"Almaty"));
        var productIds=new ArrayList<UUID>();var skuIds=new ArrayList<UUID>();
        for(int i=0;i<numberOfSkus;i++){
            String code=String.format("%02d%02d%03d",1+Math.floorMod(UUID.randomUUID().hashCode(),99),
                1+Math.floorMod(UUID.randomUUID().hashCode(),99),
                1+Math.floorMod(UUID.randomUUID().hashCode(),999))+"0000000000000";
            var category=categories.saveAndFlush(Category.create(code,ProductType.PHYSICAL,"Device",null));
            var product=products.saveAndFlush(CanonicalProduct.create(category,"Item-"+i));
            var listing=listings.saveAndFlush(SellerListing.draft(seller,product));
            var sku=skus.saveAndFlush(SellerSku.create(listing,"SKU-"+i,
                StockTrackingMode.TRACKED,MeasurementUnit.PCS));
            publishing.publishForSeller(seller,listing.getId());
            balances.saveAndFlush(InventoryBalance.open(seller,location.getId(),sku.getId(),stock.get(i)));
            productIds.add(product.getId());skuIds.add(sku.getId());
        }
        return new Market(seller,location.getId(),subject,List.copyOf(productIds),List.copyOf(skuIds));
    }
    JwtAuthenticationToken token(String subject) {
        return new JwtAuthenticationToken(Jwt.withTokenValue("signed-by-test-decoder")
            .header("alg","RS256").issuer("https://idp.example.test").subject(subject)
            .claim("aud",List.of("zakaz-api")).build(),List.of());
    }
    UUID placedOrder(Market m) {
        var buyer=UUID.randomUUID();var subject="buyer-"+UUID.randomUUID();
        buyers.saveAndFlush(BuyerAccountLink.provision("https://idp.example.test",subject,buyer));
        var demandLines=new ArrayList<BuyerDemand.DemandLine>();
        var bidLines=new ArrayList<SellerBid.BidLine>();
        for(int i=0;i<m.products().size();i++){
            demandLines.add(new BuyerDemand.DemandLine(m.products().get(i),BigDecimal.ONE,
                null,MeasurementUnit.PCS));
            bidLines.add(new SellerBid.BidLine(m.products().get(i),m.skus().get(i),BigDecimal.ONE,
                new BigDecimal("100.00")));
        }
        var d=new BuyerDemand(UUID.randomUUID(),buyer,"Almaty",NOW.plusSeconds(3600),demandLines);
        deals.createDemand(d,NOW);
        var bid=new SellerBid(UUID.randomUUID(),d.id(),m.sellerId(),1,NOW.plusSeconds(1800),bidLines);
        bidding.submit(m.sellerId(),bid,new SellerBidSubmissionService.QuoteRequest(
            SellerBidQuoteSnapshot.Method.DELIVERY,m.locationId(),new BigDecimal("20.00")),NOW);
        return accepting.accept(token(subject),d.id(),bid.id(),UUID.randomUUID().toString(),NOW);
    }
    @Test void confirmationReservesAtomicallyEmitsOneOutboxAndRetryIsIdempotent(){
        var m=market(1,List.of(BigDecimal.ONE));UUID orderId=placedOrder(m);
        assertThat(reservations.findByOrderId(orderId)).isEmpty();
        assertThat(events.findByAggregateIdAndEventType(orderId,"ORDER_CONFIRMED")).isEmpty();
        confirming.confirm(token(m.sellerSubject()),m.sellerId(),orderId,NOW.plusSeconds(20));
        confirming.confirm(token(m.sellerSubject()),m.sellerId(),orderId,NOW.plusSeconds(21));
        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(reservations.findByOrderId(orderId)).hasSize(1);
        assertThat(events.findByAggregateIdAndEventType(orderId,"ORDER_CONFIRMED")).isPresent();
        var balance=balances.findBySellerIdAndLocationIdAndSkuId(
            m.sellerId(),m.locationId(),m.skus().getFirst()).orElseThrow();
        assertThat(balance.getReserved()).isEqualByComparingTo("1.000");
    }
    @Test void insufficientSecondSkuRollsBackEveryReservationAndStatusChange(){
        var m=market(2,List.of(BigDecimal.ONE,BigDecimal.ZERO));UUID id=placedOrder(m);
        assertThatThrownBy(()->confirming.confirm(token(m.sellerSubject()),m.sellerId(),id,NOW.plusSeconds(1)))
            .isInstanceOf(InsufficientStockException.class);
        assertThat(reservations.findByOrderId(id)).isEmpty();
        assertThat(events.findByAggregateIdAndEventType(id,"ORDER_CONFIRMED")).isEmpty();
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.PLACED);
        var first=balances.findBySellerIdAndLocationIdAndSkuId(
            m.sellerId(),m.locationId(),m.skus().getFirst()).orElseThrow();
        assertThat(first.getReserved()).isEqualByComparingTo("0");
    }
    @Test void competingOrdersCannotOversellLastUnit() throws Exception {
        var m=market(1,List.of(BigDecimal.ONE));
        UUID a=placedOrder(m),b=placedOrder(m);
        var executor=Executors.newFixedThreadPool(2);
        var start=new CountDownLatch(1);
        try{
            List<Future<Boolean>> tasks=new ArrayList<>();
            for(var id:List.of(a,b))tasks.add(executor.submit(()->{
                start.await();
                try{
                    confirming.confirm(token(m.sellerSubject()),m.sellerId(),id,NOW.plusSeconds(5));
                    return true;
                } catch(InsufficientStockException e) {return false;}
            }));
            start.countDown();
            int successes=0;
            for(var future:tasks)if(future.get(20,TimeUnit.SECONDS))successes++;
            assertThat(successes).isEqualTo(1);
            assertThat(reservations.findByOrderId(a).size() + reservations.findByOrderId(b).size()).isEqualTo(1);
            assertThat((events.findByAggregateIdAndEventType(a,"ORDER_CONFIRMED").isPresent()?1:0)
                +(events.findByAggregateIdAndEventType(b,"ORDER_CONFIRMED").isPresent()?1:0)).isEqualTo(1);
            assertThat(balances.findBySellerIdAndLocationIdAndSkuId(
                m.sellerId(),m.locationId(),m.skus().getFirst()).orElseThrow()
                .getReserved()).isEqualByComparingTo("1");
        } finally {executor.shutdownNow();}
    }
    @Test void trustedSellerReceiptIsAuditedAndIdempotent() {
        var m=market(1,List.of(BigDecimal.ZERO));
        UUID sku=m.skus().getFirst();
        stock.receive(token(m.sellerSubject()),m.sellerId(),m.locationId(),sku,
            new BigDecimal("2.000"),"stock-receipt-one",NOW);
        stock.receive(token(m.sellerSubject()),m.sellerId(),m.locationId(),sku,
            new BigDecimal("2.000"),"stock-receipt-one",NOW);
        var balance=balances.findBySellerIdAndLocationIdAndSkuId(
            m.sellerId(),m.locationId(),sku).orElseThrow();
        assertThat(balance.getOnHand()).isEqualByComparingTo("2.000");
        assertThat(movements.findBySellerIdAndKindAndSourceKey(
            m.sellerId(),InventoryMovement.Kind.RECEIPT,"stock-receipt-one")).isPresent();
        assertThatThrownBy(()->stock.receive(token(m.sellerSubject()),m.sellerId(),m.locationId(),
            sku,BigDecimal.ONE,"stock-receipt-one",NOW))
            .isInstanceOf(InventoryOperationConflictException.class);
    }

    @Test void sellerCancellationReleasesEveryReservationExactlyOnceAndPublishesOutbox() {
        var m=market(1,List.of(BigDecimal.ONE)); UUID orderId=placedOrder(m);
        var actor=token(m.sellerSubject());
        confirming.confirm(actor,m.sellerId(),orderId,NOW.plusSeconds(1));
        cancellations.cancelBySeller(actor,m.sellerId(),orderId,NOW.plusSeconds(2));
        cancellations.cancelBySeller(actor,m.sellerId(),orderId,NOW.plusSeconds(3));
        assertThat(orders.findById(orderId).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.CANCELLED_BY_SELLER);
        assertThat(reservations.findByOrderId(orderId)).hasSize(1);
        assertThat(reservations.findByOrderId(orderId).getFirst().getStatus())
            .isEqualTo("RELEASED");
        assertThat(balances.findBySellerIdAndLocationIdAndSkuId(
            m.sellerId(),m.locationId(),m.skus().getFirst()).orElseThrow().getReserved())
            .isEqualByComparingTo("0.000");
        assertThat(movements.findByOrderId(orderId)).hasSize(2);
        assertThat(events.findByAggregateIdAndEventType(orderId,"ORDER_CONFIRMED")).isPresent();
        assertThat(events.findByAggregateIdAndEventType(orderId,"ORDER_CANCELLED_BY_SELLER")).isPresent();
    }

    @Test void wrongSellerCannotCancelOrReceiveAnotherMerchantsStock() {
        var m=market(1,List.of(BigDecimal.ONE)); UUID id=placedOrder(m);
        assertThatThrownBy(()->cancellations.cancelBySeller(token("attacker"),m.sellerId(),id,NOW))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->stock.receive(token("attacker"),m.sellerId(),
            m.locationId(),m.skus().getFirst(),BigDecimal.ONE,"forged",NOW))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.PLACED);
        assertThat(movements.findBySellerIdAndKindAndSourceKey(
            m.sellerId(),InventoryMovement.Kind.RECEIPT,"forged")).isEmpty();
    }

    @Test void cancellingPlacedOrderWritesOutboxWithoutInventingReservations() {
        var m=market(1,List.of(BigDecimal.ONE));UUID id=placedOrder(m);
        cancellations.cancelBySeller(token(m.sellerSubject()),m.sellerId(),id,NOW.plusSeconds(1));
        assertThat(orders.findById(id).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.CANCELLED_BY_SELLER);
        assertThat(reservations.findByOrderId(id)).isEmpty();
        assertThat(movements.findByOrderId(id)).isEmpty();
        assertThat(events.findByAggregateIdAndEventType(id,"ORDER_CANCELLED_BY_SELLER")).isPresent();
    }
}
