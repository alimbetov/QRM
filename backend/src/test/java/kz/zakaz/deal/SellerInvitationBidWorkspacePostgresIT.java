package kz.zakaz.deal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import kz.zakaz.catalog.*;
import kz.zakaz.domain.types.*;
import kz.zakaz.inventory.*;
import kz.zakaz.intelligence.events.*;
import kz.zakaz.location.*;
import kz.zakaz.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL end-to-end: consent -> delivered invitation -> quote -> bid -> buyer order -> reservation. */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test-pg") @Testcontainers
@TestPropertySource(properties={
    "zakaz.ml.r2.consent-api.enabled=true",
    "zakaz.ml.r2.invitation-evaluation.enabled=true",
    "zakaz.seller.bid-workspace.enabled=true",
    "zakaz.commerce.http.enabled=true"
})
class SellerInvitationBidWorkspacePostgresIT {
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",pg::getJdbcUrl);
        registry.add("spring.datasource.username",pg::getUsername);
        registry.add("spring.datasource.password",pg::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerSkuRepository skus;
    @Autowired CatalogPublishingService publishing;
    @Autowired SellerMembershipRepository memberships;
    @Autowired InventoryLocationRepository origins;
    @Autowired InventoryBalanceRepository balances;
    @Autowired SellerDeliveryAreaRepository areas;
    @Autowired DealPersistenceService deals;
    @Autowired MlInvitationConsentService consent;
    @Autowired MlSellerInvitationService evaluator;
    @Autowired MlInvitationCandidateScanner scanner;
    @Autowired JdbcMlEventQueue queue;
    @Autowired JdbcMlEventProjectionSink sink;
    @Autowired BuyerAccountLinkRepository buyers;
    @MockitoBean JwtDecoder decoder;

    record Fixture(UUID seller,UUID buyer,UUID demand,UUID sku,UUID origin,
                   UUID invitation,String sellerToken,String buyerToken) {}

    private Jwt jwt(String sub,String scopes) {
        return Jwt.withTokenValue("test-signed").header("alg","RS256")
            .issuer("https://idp.example.test").subject(sub)
            .claim("aud",List.of("zakaz-api")).claim("scope",scopes)
            .claim("email","test@example.test").claim("email_verified",true).build();
    }
    private Fixture fixture(BigDecimal stock,boolean share,boolean optIn) {
        return fixture(stock,share,optIn,true);
    }
    private Fixture fixture(BigDecimal stock,boolean share,boolean optIn,
                            boolean manuallyEvaluate) {
        return fixtureForUnit(stock,share,optIn,manuallyEvaluate,MeasurementUnit.PCS);
    }
    private Fixture fixtureForUnit(BigDecimal stock,boolean share,boolean optIn,
                            boolean manuallyEvaluate,MeasurementUnit unit) {
        String n=UUID.randomUUID().toString().replace("-","");
        String hcc=String.format("%02d%02d%03d",
            1+Integer.parseInt(n.substring(0,2),16)%99,
            1+Integer.parseInt(n.substring(2,4),16)%99,
            1+Integer.parseInt(n.substring(4,7),16)%999)+"0000000000000";
        var cat=categories.saveAndFlush(Category.create(hcc,ProductType.PHYSICAL,"Bid R2",null));
        var product=products.saveAndFlush(CanonicalProduct.create(cat,"Bid product "+n));
        UUID seller=UUID.randomUUID(),buyer=UUID.randomUUID();
        var listing=listings.saveAndFlush(SellerListing.draft(seller,product));
        var sku=skus.saveAndFlush(SellerSku.create(listing,"R2-SKU",
            StockTrackingMode.TRACKED,unit));
        publishing.publishForSeller(seller,listing.getId());
        var location=origins.saveAndFlush(InventoryLocation.create(
            seller,"SHOP-"+n,"Test shop","KZ-ALA","Almaty region",
            "Almaty","Main 10",LocationType.SHOP,null,true));
        balances.saveAndFlush(InventoryBalance.open(seller,location.getId(),sku.getId(),stock));
        areas.saveAndFlush(SellerDeliveryArea.allow(seller,"Almaty"));
        String ss="seller-"+n,bs="buyer-"+n,st="seller-token-"+n,bt="buyer-token-"+n;
        memberships.saveAndFlush(SellerMembership.grant(
            "https://idp.example.test",ss,seller,SellerMembership.Role.SELLER_ADMIN));
        buyers.saveAndFlush(BuyerAccountLink.provision("https://idp.example.test",bs,buyer));
        when(decoder.decode(st)).thenReturn(jwt(ss,"deal:bid order:confirm"));
        when(decoder.decode(bt)).thenReturn(jwt(bs,"deal:write order:read"));
        var demand=new BuyerDemand(UUID.randomUUID(),buyer,"Almaty",
            Instant.now().plusSeconds(3600),List.of(
            new BuyerDemand.DemandLine(product.getId(),BigDecimal.ONE,
                new BigDecimal("250.00"),unit)));
        deals.createDemand(demand,Instant.now());
        consent.setDemandSharing(buyer,demand.id(),share);
        consent.setSellerInApp(seller,optIn);
        UUID invitationId=null;
        if(manuallyEvaluate) {
            var decision=evaluator.evaluate(demand.id(),seller,1,Instant.now());
            assertThat(decision.status()).isEqualTo("ELIGIBLE");
            // Same production at-least-once consumer, not a hand-inserted fake inbox.
            var dispatcher=new MlEventDispatcher(queue,sink,"bid-integration",20,3,Duration.ofSeconds(30));
            for(int i=0;i<5;i++)dispatcher.pollOnce();
            invitationId=decision.id();
        }
        return new Fixture(seller,buyer,demand.id(),sku.getId(),location.getId(),
            invitationId,st,bt);
    }

    private String quoteUrl(Fixture f) {
        return "/api/v1/sellers/"+f.seller()+"/invitations/"+f.invitation()+"/quote-context";
    }
    private String bidUrl(Fixture f) {
        return "/api/v1/sellers/"+f.seller()+"/invitations/"+f.invitation()+"/bid";
    }
    private String offer(Fixture f,UUID sku,String price,int revision) {
        return "{\"revision\":"+revision+",\"expiresAt\":\""+
          Instant.now().plusSeconds(900)+"\",\"lines\":[{\"productId\":\""+
          jdbc.queryForObject("SELECT product_id FROM catalog_sku sku JOIN catalog_listing l ON l.id=sku.listing_id WHERE sku.id=?",
              UUID.class,f.sku())+"\",\"skuId\":\""+sku+
          "\",\"quantity\":\"1.000\",\"unitPrice\":\""+price+"\"}],"+
          "\"method\":\"PICKUP\",\"originLocationId\":\""+f.origin()+
          "\",\"deliveryFee\":\"0.00\"}";
    }
    private UUID from(String body,String key) throws Exception {
        return UUID.fromString(json.readTree(body).get(key).asText());
    }

    @Test void consentedSellerBidThenBuyerAcceptThenSellerConfirmReservesExactlyOnce() throws Exception {
        var f=fixture(new BigDecimal("5.000"),true,true);
        var detail=mvc.perform(get(quoteUrl(f))
                .header("Authorization","Bearer "+f.sellerToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.demandId").value(f.demand().toString()))
            .andExpect(jsonPath("$.nextRevision").value(1))
            .andExpect(jsonPath("$.lines[0].skus[0].skuId").value(f.sku().toString()))
            .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(detail).path("lines").get(0).path("skus")
            .get(0).path("availableByOrigin").path(f.origin().toString()).asText())
            .isEqualTo("5.000");
        assertThat(detail).doesNotContain("buyerId","phone","email");
        String input=offer(f,f.sku(),"120.00",1);
        var created=mvc.perform(post(bidUrl(f))
                .header("Authorization","Bearer "+f.sellerToken())
                .contentType(MediaType.APPLICATION_JSON).content(input))
            .andExpect(status().isCreated()).andReturn();
        UUID bid=from(created.getResponse().getContentAsString(),"bidId");
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM seller_bid_snapshot WHERE id=?",
            Integer.class,bid)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT all_in_amount FROM bid_quote_snapshot WHERE bid_id=?",
            BigDecimal.class,bid)).isEqualByComparingTo("120.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_order",Long.class))
            .isZero();
        mvc.perform(post(bidUrl(f)).header("Authorization","Bearer "+f.sellerToken())
                .contentType(MediaType.APPLICATION_JSON).content(input))
            .andExpect(status().isConflict());
        var accepted=mvc.perform(post("/api/v1/buyers/demands/"+f.demand()+
                "/bids/"+bid+"/accept")
                .header("Authorization","Bearer "+f.buyerToken())
                .header("Idempotency-Key","r2-accept-"+f.demand()))
            .andExpect(status().isCreated()).andReturn();
        UUID order=from(accepted.getResponse().getContentAsString(),"orderId");
        // Acceptance creates a PLACED economic snapshot, never an early stock hold.
        assertThat(jdbc.queryForObject(
            "SELECT status FROM customer_order WHERE id=?",String.class,order))
            .isEqualTo("PLACED");
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM inventory_reservation WHERE order_id=?",
            Integer.class,order)).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM order_outbox_event WHERE aggregate_id=? AND event_type='ORDER_CONFIRMED'",
            Integer.class,order)).isZero();
        mvc.perform(post("/api/v1/sellers/"+f.seller()+"/orders/"+order+"/confirm")
                .header("Authorization","Bearer "+f.sellerToken()))
            .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM inventory_reservation WHERE order_id=?",
            Integer.class,order)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT status FROM customer_order WHERE id=?",String.class,order))
            .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject(
            "SELECT reserved FROM inventory_balance WHERE seller_id=? AND location_id=? AND sku_id=?",
            BigDecimal.class,f.seller(),f.origin(),f.sku()))
            .isEqualByComparingTo("1.000");
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM inventory_movement WHERE order_id=? AND kind='RESERVATION'",
            Integer.class,order)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM order_outbox_event WHERE aggregate_id=? AND event_type='ORDER_CONFIRMED'",
            Integer.class,order)).isEqualTo(1);
        // Retry is no new stock movement, order or outbox event.
        mvc.perform(post("/api/v1/sellers/"+f.seller()+"/orders/"+order+"/confirm")
                .header("Authorization","Bearer "+f.sellerToken()))
            .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM inventory_movement WHERE order_id=? AND kind='RESERVATION'",
            Integer.class,order)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM order_outbox_event WHERE aggregate_id=? AND event_type='ORDER_CONFIRMED'",
            Integer.class,order)).isEqualTo(1);
    }

    @Test void nonConsentedOrRevokedInvitationNeverExposesDemandOrCreatesBid() throws Exception {
        var f=fixture(new BigDecimal("5"),false,true);
        mvc.perform(get(quoteUrl(f)).header("Authorization","Bearer "+f.sellerToken()))
            .andExpect(status().isNotFound());
        mvc.perform(post(bidUrl(f)).header("Authorization","Bearer "+f.sellerToken())
                .contentType(MediaType.APPLICATION_JSON).content(offer(f,f.sku(),"10.00",1)))
            .andExpect(status().isNotFound());
        var g=fixture(new BigDecimal("5"),true,true);
        consent.setDemandSharing(g.buyer(),g.demand(),false);
        mvc.perform(get(quoteUrl(g)).header("Authorization","Bearer "+g.sellerToken()))
            .andExpect(status().isNotFound());
        mvc.perform(post(bidUrl(g)).header("Authorization","Bearer "+g.sellerToken())
                .contentType(MediaType.APPLICATION_JSON).content(offer(g,g.sku(),"10.00",1)))
            .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM seller_bid_snapshot WHERE demand_id IN (?,?)",
            Integer.class,f.demand(),g.demand())).isZero();
    }

    @Test void foreignSellerWrongSkuAndInsufficientStockFailClosed() throws Exception {
        var f=fixture(new BigDecimal("0.000"),true,true);
        mvc.perform(get(quoteUrl(f))).andExpect(status().isUnauthorized());
        var other=fixture(new BigDecimal("5"),true,true);
        mvc.perform(get(quoteUrl(f))
            .header("Authorization","Bearer "+other.sellerToken()))
            .andExpect(status().isForbidden());
        mvc.perform(post(bidUrl(f)).header("Authorization","Bearer "+f.sellerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(offer(f,UUID.randomUUID(),"10.00",1)))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(post(bidUrl(f)).header("Authorization","Bearer "+f.sellerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(offer(f,f.sku(),"10.00",1)))
            .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM seller_bid_snapshot WHERE demand_id=?",
            Integer.class,f.demand())).isZero();
    }
    @Test void legacyDemandUuidEndpointCannotBypassBuyerInvitationConsent() throws Exception {
        var f=fixture(new BigDecimal("5.000"),false,true);
        String legacy="/api/v1/sellers/"+f.seller()+"/demands/"+f.demand()+"/bids";
        mvc.perform(post(legacy)
                .header("Authorization","Bearer "+f.sellerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(offer(f,f.sku(),"99.00",1)))
            .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM seller_bid_snapshot WHERE demand_id=?",
            Integer.class,f.demand())).isZero();
    }

    @Test void keyedSellerBidRetryReturnsSameReceiptAndRejectsChangedTerms() throws Exception {
        var f=fixture(new BigDecimal("5.000"),true,true);
        String key="seller-bid-retry-"+UUID.randomUUID();
        String body=offer(f,f.sku(),"120.00",1);
        String original=mvc.perform(post(bidUrl(f))
                .header("Authorization","Bearer "+f.sellerToken())
                .header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID created=from(original,"bidId");
        mvc.perform(post(bidUrl(f)).header("Authorization","Bearer "+f.sellerToken())
                .header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.bidId").value(created.toString()));
        mvc.perform(post(bidUrl(f)).header("Authorization","Bearer "+f.sellerToken())
                .header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(offer(f,f.sku(),"121.00",1)))
            .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM seller_bid_snapshot WHERE demand_id=?",
            Integer.class,f.demand())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM seller_bid_http_receipt WHERE seller_id=? AND invitation_id=?",
            Integer.class,f.seller(),f.invitation())).isEqualTo(1);
    }

    @Test void autonomousCandidateScannerFindsConsentedStockedSellerOnlyOnce() throws Exception {
        var f=fixture(new BigDecimal("5.000"),true,true,false);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM ml_seller_invitation WHERE demand_id=?",
            Integer.class,f.demand())).isZero();
        assertThat(scanner.scanOnce(24)).isGreaterThanOrEqualTo(1);
        UUID invitationId=jdbc.queryForObject("""
            SELECT id FROM ml_seller_invitation
            WHERE demand_id=? AND seller_id=? AND policy_version=1
            """,UUID.class,f.demand(),f.seller());
        assertThat(invitationId).isNotNull();
        var dispatcher=new MlEventDispatcher(queue,sink,"scan-test",24,3,
            Duration.ofSeconds(30));
        for(int i=0;i<5;i++)dispatcher.pollOnce();
        var auto=new Fixture(f.seller(),f.buyer(),f.demand(),f.sku(),f.origin(),
            invitationId,f.sellerToken(),f.buyerToken());
        mvc.perform(get(quoteUrl(auto))
                .header("Authorization","Bearer "+auto.sellerToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.invitationId").value(invitationId.toString()));
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM ml_seller_invitation WHERE demand_id=? AND seller_id=?",
            Integer.class,f.demand(),f.seller())).isEqualTo(1);
    }

    @Test void autonomousScannerNeverInvitesWithoutBothOptInsOrFreeStock() {
        var noBuyerConsent=fixture(new BigDecimal("5.000"),false,true,false);
        var noSellerPreference=fixture(new BigDecimal("5.000"),true,false,false);
        var zeroStock=fixture(BigDecimal.ZERO,true,true,false);
        scanner.scanOnce(24);
        for(var f:List.of(noBuyerConsent,noSellerPreference,zeroStock)) {
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ml_seller_invitation WHERE demand_id=?",
                Integer.class,f.demand())).isZero();
        }
    }

    /**
     * MATCH-001: candidate discovery cannot invite for a unit rejected by
     * the authoritative SellerBidSubmissionService. SQL is not used to
     * insert invitation decisions or fabricated business events.
     */
    @Test void scannerDoesNotInviteForUnitsRejectedByRealBidWriter() {
        var service=fixtureForUnit(new BigDecimal("5.000"),true,true,false,
            MeasurementUnit.SERVICE);
        var license=fixtureForUnit(new BigDecimal("5.000"),true,true,false,
            MeasurementUnit.LICENSE);
        scanner.scanOnce(24);
        for(var f:List.of(service,license)) {
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ml_seller_invitation WHERE demand_id=?",
                Integer.class,f.demand()))
                .as("No invitation for an unquotable service/license unit").isZero();
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ml_seller_invitation_inbox WHERE demand_id=?",
                Integer.class,f.demand())).isZero();
        }
    }

    /**
     * MATCH-002: when one qualifying SKU is after the twenty-item UI quote
     * window, the system must still offer a quoteable stocked SKU. All
     * initial catalog/stock inserts are isolated synthetic test fixtures.
     */
    @Test void scannerInvitedSellerCanQuoteStockedSkuOutsideFirstTwenty() throws Exception {
        var f=fixture(new BigDecimal("0.000"),true,true,false);
        UUID productId=jdbc.queryForObject("""
            SELECT l.product_id FROM catalog_sku s
            JOIN catalog_listing l ON l.id=s.listing_id WHERE s.id=?
            """,UUID.class,f.sku());
        var draft=listings.saveAndFlush(SellerListing.draft(
            f.seller(),products.findById(productId).orElseThrow()));
        for(int i=0;i<21;i++) {
            skus.saveAndFlush(SellerSku.create(draft,"EXTRA-"+i,
                StockTrackingMode.TRACKED,MeasurementUnit.PCS));
        }
        publishing.publishForSeller(f.seller(),draft.getId());
        UUID stocked=jdbc.queryForObject("""
            SELECT s.id FROM catalog_sku s
            JOIN catalog_listing l ON l.id=s.listing_id
            WHERE l.seller_id=? AND l.product_id=?
            ORDER BY s.id DESC LIMIT 1
            """,UUID.class,f.seller(),productId);
        if(stocked.equals(f.sku())) {
            assertThat(jdbc.update("""
                UPDATE inventory_balance SET on_hand=3.000,version=version+1
                WHERE seller_id=? AND location_id=? AND sku_id=? AND reserved=0
                """,f.seller(),f.origin(),stocked)).isEqualTo(1);
        } else {
            balances.saveAndFlush(InventoryBalance.open(
                f.seller(),f.origin(),stocked,new BigDecimal("3.000")));
        }
        assertThat(scanner.scanOnce(24)).isGreaterThanOrEqualTo(1);
        UUID invitationId=jdbc.queryForObject("""
            SELECT id FROM ml_seller_invitation
            WHERE demand_id=? AND seller_id=? AND policy_version=1
            """,UUID.class,f.demand(),f.seller());
        var dispatcher=new MlEventDispatcher(queue,sink,"match-pagination",24,3,
            Duration.ofSeconds(30));
        for(int i=0;i<5;i++)dispatcher.pollOnce();
        var context=new Fixture(f.seller(),f.buyer(),f.demand(),f.sku(),
            f.origin(),invitationId,f.sellerToken(),f.buyerToken());
        var response=mvc.perform(get(quoteUrl(context))
                .header("Authorization","Bearer "+f.sellerToken()))
            .andExpect(status().isOk()).andReturn();
        var options=json.readTree(response.getResponse().getContentAsString())
            .path("lines").get(0).path("skus");
        boolean stockedVisible=false;
        for(var option:options) {
            if(stocked.toString().equals(option.path("skuId").asText()) &&
                new BigDecimal(option.path("availableByOrigin")
                    .path(f.origin().toString()).asText("0"))
                    .compareTo(BigDecimal.ONE)>=0) {
                stockedVisible=true;
            }
        }
        assertThat(stockedVisible)
            .as("An eligible seller must see a stocked SKU within the bounded quote context")
            .isTrue();
        mvc.perform(post(bidUrl(context))
                .header("Authorization","Bearer "+f.sellerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(offer(context,stocked,"120.00",1)))
            .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM seller_bid_snapshot WHERE demand_id=?",
            Integer.class,f.demand())).isEqualTo(1);
    }

    @Test void olderActiveInvitationRemainsUsableBeyondNewestHundredInboxRows() throws Exception {
        var f=fixture(new BigDecimal("5.000"),true,true);
        // Regression for previous preview code: listSellerInbox(seller,100)
        // hid any valid invitation older than the newest hundred.
        // These extra invitations are synthetic SQL fixtures ONLY for pagination,
        // not proof that the ML dispatcher evaluated or delivered them.
        int policies=jdbc.update("""
            INSERT INTO ml_seller_invitation(
                id,demand_id,seller_id,policy_version,status,reason_code,evaluated_at)
            SELECT gen_random_uuid(),?,?,series.version,'ELIGIBLE','MATCHED',clock_timestamp()
            FROM generate_series(2,102) AS series(version)
            """,f.demand(),f.seller());
        assertThat(policies).isEqualTo(101);
        int delivered=jdbc.update("""
            INSERT INTO ml_seller_invitation_inbox(
                invitation_id,demand_id,seller_id,delivered_at)
            SELECT id,demand_id,seller_id,clock_timestamp()+interval '1 minute'
            FROM ml_seller_invitation
            WHERE demand_id=? AND seller_id=? AND policy_version BETWEEN 2 AND 102
            """,f.demand(),f.seller());
        assertThat(delivered).isEqualTo(101);
        assertThat(consent.listSellerInbox(f.seller(),100))
            .extracting(MlInvitationConsentService.InboxItem::invitationId)
            .doesNotContain(f.invitation());
        assertThat(consent.isSellerInvitationVisible(f.seller(),f.invitation()))
            .isTrue();
        mvc.perform(get(quoteUrl(f))
            .header("Authorization","Bearer "+f.sellerToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.invitationId").value(f.invitation().toString()));
        consent.setDemandSharing(f.buyer(),f.demand(),false);
        assertThat(consent.isSellerInvitationVisible(f.seller(),f.invitation()))
            .isFalse();
        mvc.perform(get(quoteUrl(f))
            .header("Authorization","Bearer "+f.sellerToken()))
            .andExpect(status().isNotFound());
    }

}
