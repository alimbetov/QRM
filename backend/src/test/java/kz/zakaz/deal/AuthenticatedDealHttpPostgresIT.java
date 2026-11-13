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
}
