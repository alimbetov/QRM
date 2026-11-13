package kz.zakaz.deal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import kz.zakaz.catalog.*;
import kz.zakaz.domain.types.*;
import kz.zakaz.inventory.*;
import kz.zakaz.location.*;
import kz.zakaz.order.*;
import kz.zakaz.publicqr.PublicListingLinkRepository;
import kz.zakaz.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test-pg") @Testcontainers
@TestPropertySource(properties={
    "zakaz.commerce.http.enabled=true",
    "zakaz.qr.http.enabled=true",
    "zakaz.buyer.enrollment.enabled=true",
    "zakaz.identity.require-verified-email=true",
    "zakaz.identity.phone-key-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "zakaz.ml.r2.consent-api.enabled=true"
})
class AuthenticatedDealHttpPostgresIT {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",db::getJdbcUrl);
        r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean JwtDecoder decoder;
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerSkuRepository skus;
    @Autowired CatalogPublishingService publishing;
    @Autowired SellerMembershipRepository memberships;
    @Autowired BuyerAccountLinkRepository buyerLinks;
    @Autowired InventoryLocationRepository locations;
    @Autowired SellerDeliveryAreaRepository areas;
    @Autowired InventoryBalanceRepository balances;
    @Autowired CustomerOrderRepository orders;
    @Autowired InventoryReservationRepository reservations;
    @Autowired OrderOutboxEventRepository events;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired PublicListingLinkRepository publicLinks;

    private record Fixture(UUID productId,UUID skuId,UUID locationId,
                           UUID sellerId,UUID buyerId,String buyerToken,String sellerToken,UUID listingId){}
    private Jwt jwt(String subject,String scope) {
        return Jwt.withTokenValue("mocked-decoder-result").header("alg","RS256")
            .issuer("https://idp.example.test").subject(subject)
            .claim("aud",List.of("zakaz-api")).claim("scope",scope)
            .claim("email","buyer@example.test").claim("email_verified",true).build();
    }
    private Fixture seed(){ return seed(true); }
    private Fixture seed(boolean buyerPreProvisioned){
        String code=String.format("%02d%02d%03d",1+Math.floorMod(UUID.randomUUID().hashCode(),99),
                1+Math.floorMod(UUID.randomUUID().hashCode(),99),
                1+Math.floorMod(UUID.randomUUID().hashCode(),999))+"0000000000000";
        var category=categories.saveAndFlush(Category.create(
            code,ProductType.PHYSICAL,"Phones",null));
        var product=products.saveAndFlush(CanonicalProduct.create(category,"Phone"));
        UUID seller=UUID.randomUUID(),buyer=UUID.randomUUID();
        var listing=listings.saveAndFlush(SellerListing.draft(seller,product));
        var sku=skus.saveAndFlush(SellerSku.create(listing,"SKU-1",StockTrackingMode.TRACKED,MeasurementUnit.PCS));
        publishing.publishForSeller(seller,listing.getId());
        var location=locations.saveAndFlush(InventoryLocation.create(
            seller,"WH-"+UUID.randomUUID(),"Test pickup","KZ-ALA","Almaty region",
            "Almaty","Testing avenue 1",LocationType.SHOP,null,true));
        balances.saveAndFlush(InventoryBalance.open(seller,location.getId(),sku.getId(),BigDecimal.ONE));
        areas.saveAndFlush(SellerDeliveryArea.allow(seller,"Almaty"));
        String buyerSubject="buyer-"+UUID.randomUUID(), sellerSubject="seller-"+UUID.randomUUID();
        if(buyerPreProvisioned)
            buyerLinks.saveAndFlush(BuyerAccountLink.provision("https://idp.example.test",buyerSubject,buyer));
        memberships.saveAndFlush(SellerMembership.grant("https://idp.example.test",sellerSubject,
            seller,SellerMembership.Role.SELLER_ADMIN));
        String bt="buyer-"+UUID.randomUUID(),st="seller-"+UUID.randomUUID();
        when(decoder.decode(bt)).thenReturn(jwt(buyerSubject,"deal:write order:read"));
        when(decoder.decode(st)).thenReturn(jwt(sellerSubject,"deal:bid order:confirm"));
        return new Fixture(product.getId(),sku.getId(),location.getId(),seller,buyer,bt,st,listing.getId());
    }
    private UUID idFrom(String body,String name) throws Exception{
        return UUID.fromString(json.readTree(body).get(name).asText());
    }
    @Test void signedBuyerSellerWorkflowFromHttpCreatesAndConfirmsAtomicOrder() throws Exception{
        var f=seed();
        String expires=Instant.now().plusSeconds(900).toString();
        String demandBody="{\"city\":\"Almaty\",\"expiresAt\":\""+expires+
            "\",\"lines\":[{\"productId\":\""+f.productId()+
            "\",\"quantity\":1,\"unit\":\"PCS\"}]}";
        var demandCreated=mvc.perform(post("/api/v1/buyers/demands")
                .header("Authorization","Bearer "+f.buyerToken())
                .contentType(MediaType.APPLICATION_JSON).content(demandBody))
            .andExpect(status().isCreated()).andReturn();
        UUID demandId=idFrom(demandCreated.getResponse().getContentAsString(),"demandId");
        String bidBody="{\"revision\":1,\"expiresAt\":\""+expires+
            "\",\"lines\":[{\"productId\":\""+f.productId()+
            "\",\"skuId\":\""+f.skuId()+
            "\",\"quantity\":1,\"unitPrice\":100}],"+
            "\"method\":\"PICKUP\",\"originLocationId\":\""+f.locationId()+
            "\",\"deliveryFee\":0}";
        var bidCreated=mvc.perform(post("/api/v1/sellers/"+f.sellerId()+"/demands/"+demandId+"/bids")
                .header("Authorization","Bearer "+f.sellerToken())
                .contentType(MediaType.APPLICATION_JSON).content(bidBody))
            .andExpect(status().isCreated()).andReturn();
        UUID bidId=idFrom(bidCreated.getResponse().getContentAsString(),"bidId");
        String quotesUrl="/api/v1/buyers/demands/"+demandId+"/bids";
        mvc.perform(get(quotesUrl).header("Authorization","Bearer "+f.buyerToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.acceptedOrderId").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.bids[0].bidId").value(bidId.toString()))
            .andExpect(jsonPath("$.bids[0].totalKzt").value("100.00"))
            .andExpect(jsonPath("$.bids[0].method").value("PICKUP"))
            .andExpect(jsonPath("$.bids[0].lines[0].skuId").value(f.skuId().toString()));
        mvc.perform(get(quotesUrl).header("Authorization","Bearer "+f.sellerToken()))
            .andExpect(status().isForbidden());

        String accept="/api/v1/buyers/demands/"+demandId+"/bids/"+bidId+"/accept";
        var accepted=mvc.perform(post(accept)
                .header("Authorization","Bearer "+f.buyerToken())
                .header("Idempotency-Key","buyer-accept-one"))
            .andExpect(status().isCreated()).andReturn();
        UUID orderId=idFrom(accepted.getResponse().getContentAsString(),"orderId");
        mvc.perform(post(accept)
                .header("Authorization","Bearer "+f.buyerToken())
                .header("Idempotency-Key","buyer-accept-one"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.orderId").value(orderId.toString()));
        mvc.perform(get("/api/v1/buyers/orders/"+orderId)
                .header("Authorization","Bearer "+f.buyerToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PLACED"))
            // Preserve BigDecimal precision at the HTTP boundary for JS/Dart clients.
            .andExpect(jsonPath("$.totalAmount").value("100.00"))
            .andExpect(jsonPath("$.deliveryFee").value("0.00"))
            .andExpect(jsonPath("$.lines[0].lineTotal").value("100.00"))
            .andExpect(jsonPath("$.lines[0].quantity").isString());
        // A separately provisioned buyer cannot read or enumerate another buyer's order.
        String anotherSubject="unrelated-buyer-"+UUID.randomUUID();
        String anotherToken="unrelated-token-"+UUID.randomUUID();
        buyerLinks.saveAndFlush(BuyerAccountLink.provision(
            "https://idp.example.test",anotherSubject,UUID.randomUUID()));
        when(decoder.decode(anotherToken)).thenReturn(jwt(anotherSubject,"order:read"));
        String unauthorizedBuyer="foreign-bid-"+UUID.randomUUID();
        when(decoder.decode(unauthorizedBuyer)).thenReturn(jwt(anotherSubject,"deal:write"));
        mvc.perform(get(quotesUrl).header("Authorization","Bearer "+unauthorizedBuyer))
            .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/buyers/orders/"+orderId)
                .header("Authorization","Bearer "+anotherToken))
            .andExpect(status().isForbidden());
        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PLACED);
        assertThat(reservations.findByOrderId(orderId)).isEmpty();
        var confirmation="/api/v1/sellers/"+f.sellerId()+"/orders/"+orderId+"/confirm";
        mvc.perform(post(confirmation).header("Authorization","Bearer "+f.sellerToken()))
            .andExpect(status().isNoContent());
        mvc.perform(post(confirmation).header("Authorization","Bearer "+f.sellerToken()))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/buyers/orders/"+orderId)
                .header("Authorization","Bearer "+f.buyerToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(reservations.findByOrderId(orderId)).hasSize(1);
        assertThat(events.findByAggregateIdAndEventType(orderId,"ORDER_CONFIRMED")).isPresent();
    }

    /**
     * QA-02: real PostgreSQL/MockMvc commerce journey across registration, QR,
     * buyer demand, seller quote, acceptance and exactly-once reservation.
     * JWT decoder is simulated; external Keycloak/SMTP browser UAT is separate.
     */
    @Test void buyerEnrollmentQrDemandOfferOrderAndReserveShareOneDbJourney() throws Exception {
        var f=seed(false);
        long beforeOrders=orders.count();
        String qrToken=publicLinks.findByListing_Id(f.listingId()).orElseThrow().getToken();

        mvc.perform(get("/api/v1/public/q/"+qrToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.listingId").value(f.listingId().toString()))
            .andExpect(jsonPath("$.variants[0].id").value(f.skuId().toString()));
        assertThat(orders.count()).isEqualTo(beforeOrders);

        mvc.perform(get("/api/v1/buyers/me")
                .header("Authorization","Bearer "+f.buyerToken()))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/buyers/enroll")
                .header("Authorization","Bearer "+f.buyerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"BuyerQA02\",\"phone\":\"+77000000000\","
                    +"\"consentToStorePhone\":true}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.nickname").value("BuyerQA02"));

        String expires=Instant.now().plusSeconds(900).toString();
        String demandBody="{\"city\":\"Almaty\",\"expiresAt\":\""+expires+
            "\",\"lines\":[{\"productId\":\""+f.productId()+
            "\",\"quantity\":1,\"unit\":\"PCS\"}]}";
        var demand=mvc.perform(post("/api/v1/buyers/demands")
                .header("Authorization","Bearer "+f.buyerToken())
                .contentType(MediaType.APPLICATION_JSON).content(demandBody))
            .andExpect(status().isCreated()).andReturn();
        UUID demandId=idFrom(demand.getResponse().getContentAsString(),"demandId");

        String bidBody="{\"revision\":1,\"expiresAt\":\""+expires+
            "\",\"lines\":[{\"productId\":\""+f.productId()+
            "\",\"skuId\":\""+f.skuId()+
            "\",\"quantity\":1,\"unitPrice\":100}],"
            +"\"method\":\"PICKUP\",\"originLocationId\":\""+
              f.locationId()+"\",\"deliveryFee\":0}";
        var bid=mvc.perform(post("/api/v1/sellers/"+f.sellerId()+
                "/demands/"+demandId+"/bids")
                .header("Authorization","Bearer "+f.sellerToken())
                .contentType(MediaType.APPLICATION_JSON).content(bidBody))
            .andExpect(status().isCreated()).andReturn();
        UUID bidId=idFrom(bid.getResponse().getContentAsString(),"bidId");

        var accepted=mvc.perform(post("/api/v1/buyers/demands/"+demandId+
                "/bids/"+bidId+"/accept")
                .header("Authorization","Bearer "+f.buyerToken())
                .header("Idempotency-Key","qa02-"+UUID.randomUUID()))
            .andExpect(status().isCreated()).andReturn();
        UUID orderId=idFrom(accepted.getResponse().getContentAsString(),"orderId");
        assertThat(reservations.findByOrderId(orderId)).isEmpty();
        assertThat(orders.findById(orderId).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.PLACED);

        var confirm="/api/v1/sellers/"+f.sellerId()+"/orders/"+orderId+"/confirm";
        mvc.perform(post(confirm).header("Authorization","Bearer "+f.sellerToken()))
            .andExpect(status().isNoContent());
        mvc.perform(post(confirm).header("Authorization","Bearer "+f.sellerToken()))
            .andExpect(status().isNoContent());
        assertThat(orders.findById(orderId).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.CONFIRMED);
        assertThat(reservations.findByOrderId(orderId)).hasSize(1);
        assertThat(events.findByAggregateIdAndEventType(orderId,"ORDER_CONFIRMED"))
            .isPresent();
        // ML-R2 reads the SAME committed business transitions; no extra
        // notifications or duplicated trade labels on repeated confirmation.
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ml_trade_event
            WHERE demand_id=? AND event_type='DEMAND_CREATED'
            """,Integer.class,demandId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ml_trade_event
            WHERE demand_id=? AND event_type='BID_SUBMITTED'
            """,Integer.class,demandId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ml_trade_event
            WHERE demand_id=? AND event_type='BID_ACCEPTED'
            """,Integer.class,demandId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ml_trade_event
            WHERE aggregate_id=? AND event_type='ORDER_CONFIRMED'
            """,Integer.class,orderId)).isEqualTo(1);
        assertThat(orders.count()).isEqualTo(beforeOrders+1);
    }

    @Test void unverifiedAndForeignActorsCannotCreateDemandOrConfirmOrder() throws Exception{
        var f=seed();
        var request="{\"city\":\"Almaty\",\"expiresAt\":\""+
            Instant.now().plusSeconds(900)+"\",\"lines\":[{\"productId\":\""+
            f.productId()+"\",\"quantity\":1,\"unit\":\"PCS\"}]}";
        mvc.perform(post("/api/v1/buyers/demands")
                .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/buyers/demands")
                .header("Authorization","Bearer "+f.sellerToken())
                .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/sellers/"+UUID.randomUUID()+"/orders/"+UUID.randomUUID()+"/confirm")
                .header("Authorization","Bearer "+f.sellerToken()))
            .andExpect(status().isForbidden());
    }

    @Test void invitationSharingRequiresLinkedBuyerAndSellerAdminAndIsNotAutomatic() throws Exception {
        var f=seed();
        String deadline=Instant.now().plusSeconds(900).toString();
        String payload=json.writeValueAsString(Map.of(
            "city","Almaty","expiresAt",deadline,
            "lines",List.of(Map.of("productId",f.productId(),"quantity",1,"unit","PCS"))));
        String enable=json.writeValueAsString(Map.of("enabled",true));
        String disable=json.writeValueAsString(Map.of("enabled",false));
        var created=mvc.perform(post("/api/v1/buyers/demands")
            .header("Authorization","Bearer "+f.buyerToken())
            .contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(status().isCreated()).andReturn();
        UUID demand=idFrom(created.getResponse().getContentAsString(),"demandId");
        String sharing="/api/v1/buyers/demands/"+demand+"/invitation-sharing";
        mvc.perform(put(sharing).contentType(MediaType.APPLICATION_JSON)
            .content(enable)).andExpect(status().isUnauthorized());
        mvc.perform(put(sharing).header("Authorization","Bearer "+f.buyerToken())
            .contentType(MediaType.APPLICATION_JSON).content(enable))
            .andExpect(status().isNoContent());

        String sellerPref="/api/v1/sellers/"+f.sellerId()+"/invitation-preference";
        mvc.perform(put(sellerPref).header("Authorization","Bearer "+f.sellerToken())
            .contentType(MediaType.APPLICATION_JSON).content(enable))
            .andExpect(status().isNoContent());
        mvc.perform(put("/api/v1/sellers/"+UUID.randomUUID()+"/invitation-preference")
            .header("Authorization","Bearer "+f.sellerToken())
            .contentType(MediaType.APPLICATION_JSON).content(enable))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/sellers/"+f.sellerId()+"/invitations")
            .header("Authorization","Bearer "+f.sellerToken()))
            .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(put(sharing).header("Authorization","Bearer "+f.buyerToken())
            .contentType(MediaType.APPLICATION_JSON).content(disable))
            .andExpect(status().isNoContent());
        // Consent records alone must never create an order or send a push.
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ml_seller_invitation_inbox WHERE demand_id=?
            """,Integer.class,demand)).isZero();
    }

    private record ReadyBid(Fixture fixture,UUID demandId,UUID bidId) {}

    private ReadyBid createBuyerReadyBid(Fixture f) throws Exception {
        String deadline=Instant.now().plusSeconds(900).toString();
        String demand=json.writeValueAsString(Map.of(
            "city","Almaty","expiresAt",deadline,
            "lines",List.of(Map.of("productId",f.productId(),"quantity",1,"unit","PCS"))));
        var created=mvc.perform(post("/api/v1/buyers/demands")
            .header("Authorization","Bearer "+f.buyerToken())
            .contentType(MediaType.APPLICATION_JSON).content(demand))
            .andExpect(status().isCreated()).andReturn();
        UUID demandId=idFrom(created.getResponse().getContentAsString(),"demandId");
        String bid=json.writeValueAsString(Map.of(
            "revision",1,"expiresAt",deadline,"method","PICKUP",
            "originLocationId",f.locationId(),"deliveryFee",0,
            "lines",List.of(Map.of("productId",f.productId(),
                "skuId",f.skuId(),"quantity",1,"unitPrice",100))));
        var submitted=mvc.perform(post("/api/v1/sellers/"+f.sellerId()+
            "/demands/"+demandId+"/bids")
            .header("Authorization","Bearer "+f.sellerToken())
            .contentType(MediaType.APPLICATION_JSON).content(bid))
            .andExpect(status().isCreated()).andReturn();
        return new ReadyBid(f,demandId,idFrom(submitted.getResponse().getContentAsString(),"bidId"));
    }

    /**
     * QA03-007 / EDGE-03: two distinct idempotency keys race through the
     * actual HTTP controller and PostgreSQL. One demand must produce ONE order.
     */
    @Test void twoConcurrentHttpBidAcceptancesCreateExactlyOnePlacedOrder() throws Exception {
        ReadyBid sale=createBuyerReadyBid(seed());
        String url="/api/v1/buyers/demands/"+sale.demandId()+"/bids/"+sale.bidId()+"/accept";
        long before=orders.count();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<org.springframework.mock.web.MockHttpServletResponse>> futures=
                new ArrayList<>();
            for (int i=0;i<2;i++) {
                final int attempt=i;
                futures.add(pool.submit(()->{
                    start.await();
                    return mvc.perform(post(url)
                        .header("Authorization","Bearer "+sale.fixture().buyerToken())
                        .header("Idempotency-Key","qa03-concurrent-"+attempt))
                        .andReturn().getResponse();
                }));
            }
            start.countDown();
            var first=futures.get(0).get(25,java.util.concurrent.TimeUnit.SECONDS);
            var second=futures.get(1).get(25,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(List.of(first.getStatus(),second.getStatus()))
                .containsExactlyInAnyOrder(201,409);
            String createdBody=first.getStatus()==201?first.getContentAsString():second.getContentAsString();
            UUID orderId=idFrom(createdBody,"orderId");
            assertThat(orders.count()).isEqualTo(before+1);
            assertThat(orders.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PLACED);
            assertThat(reservations.findByOrderId(orderId)).isEmpty();
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM bid_acceptance WHERE demand_id=?",
                Integer.class,sale.demandId())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void buyerCanCancelPlacedOrderExactlyOnceButCannotCancelConfirmedOrder() throws Exception {
        ReadyBid sale=createBuyerReadyBid(seed());
        String accept="/api/v1/buyers/demands/"+sale.demandId()+"/bids/"+sale.bidId()+"/accept";
        String idem="qa03-cancel-"+UUID.randomUUID();
        var created=mvc.perform(post(accept)
            .header("Authorization","Bearer "+sale.fixture().buyerToken())
            .header("Idempotency-Key",idem))
            .andExpect(status().isCreated()).andReturn();
        UUID orderId=idFrom(created.getResponse().getContentAsString(),"orderId");
        String cancel="/api/v1/buyers/orders/"+orderId+"/cancel";
        long beforeOrders=orders.count();
        mvc.perform(post(cancel)).andExpect(status().isUnauthorized());
        mvc.perform(post(cancel).header("Authorization","Bearer "+sale.fixture().sellerToken()))
            .andExpect(status().isForbidden());
        mvc.perform(post(cancel).header("Authorization","Bearer "+sale.fixture().buyerToken()))
            .andExpect(status().isNoContent());
        mvc.perform(post(cancel).header("Authorization","Bearer "+sale.fixture().buyerToken()))
            .andExpect(status().isNoContent());
        assertThat(orders.findById(orderId).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.CANCELLED_BY_BUYER);
        assertThat(orders.count()).isEqualTo(beforeOrders);
        assertThat(reservations.findByOrderId(orderId)).isEmpty();
        assertThat(events.findByAggregateIdAndEventType(orderId,"ORDER_CANCELLED_BY_BUYER"))
            .isPresent();
        mvc.perform(post("/api/v1/sellers/"+sale.fixture().sellerId()+
            "/orders/"+orderId+"/confirm")
            .header("Authorization","Bearer "+sale.fixture().sellerToken()))
            .andExpect(status().isConflict());

        ReadyBid other=createBuyerReadyBid(seed());
        var otherOrder=mvc.perform(post("/api/v1/buyers/demands/"+other.demandId()+
            "/bids/"+other.bidId()+"/accept")
            .header("Authorization","Bearer "+other.fixture().buyerToken())
            .header("Idempotency-Key","qa03-other-"+UUID.randomUUID()))
            .andExpect(status().isCreated()).andReturn();
        UUID foreignOrder=idFrom(otherOrder.getResponse().getContentAsString(),"orderId");
        mvc.perform(post("/api/v1/buyers/orders/"+foreignOrder+"/cancel")
            .header("Authorization","Bearer "+sale.fixture().buyerToken()))
            .andExpect(status().isForbidden());
        assertThat(orders.findById(foreignOrder).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.PLACED);

        mvc.perform(post("/api/v1/sellers/"+other.fixture().sellerId()+
            "/orders/"+foreignOrder+"/confirm")
            .header("Authorization","Bearer "+other.fixture().sellerToken()))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/buyers/orders/"+foreignOrder+"/cancel")
            .header("Authorization","Bearer "+other.fixture().buyerToken()))
            .andExpect(status().isConflict());
        assertThat(orders.findById(foreignOrder).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.CONFIRMED);
        assertThat(reservations.findByOrderId(foreignOrder)).hasSize(1);
        assertThat(events.findByAggregateIdAndEventType(foreignOrder,"ORDER_CANCELLED_BY_BUYER"))
            .isEmpty();
    }


    /** QA03-009: buyer cancellation and seller confirmation serialize on
     * the same PostgreSQL order lock. No partial stock release or double event. */
    @Test void buyerCancellationRacingSellerConfirmationLeavesOneLegalOutcome() throws Exception {
        ReadyBid sale=createBuyerReadyBid(seed());
        String accept="/api/v1/buyers/demands/"+sale.demandId()+"/bids/"+sale.bidId()+"/accept";
        var placed=mvc.perform(post(accept)
            .header("Authorization","Bearer "+sale.fixture().buyerToken())
            .header("Idempotency-Key","qa03-cancel-confirm-"+UUID.randomUUID()))
            .andExpect(status().isCreated()).andReturn();
        UUID orderId=idFrom(placed.getResponse().getContentAsString(),"orderId");
        String cancel="/api/v1/buyers/orders/"+orderId+"/cancel";
        String confirm="/api/v1/sellers/"+sale.fixture().sellerId()+"/orders/"+orderId+"/confirm";

        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var buyer=pool.submit(()->{
                start.await();
                return mvc.perform(post(cancel)
                    .header("Authorization","Bearer "+sale.fixture().buyerToken()))
                    .andReturn().getResponse().getStatus();
            });
            var seller=pool.submit(()->{
                start.await();
                return mvc.perform(post(confirm)
                    .header("Authorization","Bearer "+sale.fixture().sellerToken()))
                    .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int buyerCode=buyer.get(30,java.util.concurrent.TimeUnit.SECONDS);
            int sellerCode=seller.get(30,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(List.of(buyerCode,sellerCode)).containsExactlyInAnyOrder(204,409);
            var status=orders.findById(orderId).orElseThrow().getStatus();
            var held=reservations.findByOrderId(orderId);
            boolean buyerEvent=events.findByAggregateIdAndEventType(
                orderId,"ORDER_CANCELLED_BY_BUYER").isPresent();
            boolean confirmedEvent=events.findByAggregateIdAndEventType(
                orderId,"ORDER_CONFIRMED").isPresent();
            if(buyerCode==204){
                assertThat(status).isEqualTo(OrderStatus.CANCELLED_BY_BUYER);
                assertThat(held).isEmpty();
                assertThat(buyerEvent).isTrue();
                assertThat(confirmedEvent).isFalse();
            } else {
                assertThat(status).isEqualTo(OrderStatus.CONFIRMED);
                assertThat(held).hasSize(1);
                assertThat(held.getFirst().getStatus()).isEqualTo("RESERVED");
                assertThat(buyerEvent).isFalse();
                assertThat(confirmedEvent).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }

}
