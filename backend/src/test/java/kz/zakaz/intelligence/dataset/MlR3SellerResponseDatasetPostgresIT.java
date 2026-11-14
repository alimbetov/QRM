package kz.zakaz.intelligence.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import kz.zakaz.catalog.*;
import kz.zakaz.deal.*;
import kz.zakaz.domain.types.*;
import kz.zakaz.inventory.*;
import kz.zakaz.intelligence.events.*;
import kz.zakaz.location.*;
import kz.zakaz.security.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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

/** Real PostgreSQL + authorized HTTP evidence for the ML-R3 research-only slice. */
@SpringBootTest @AutoConfigureMockMvc @Testcontainers @ActiveProfiles("test-pg")
@TestPropertySource(properties={
    "zakaz.ml.r2.consent-api.enabled=true",
    "zakaz.ml.r2.invitation-evaluation.enabled=true",
    "zakaz.seller.bid-workspace.enabled=true",
    "zakaz.ml.r3.exposure.enabled=true",
    "zakaz.commerce.http.enabled=true"
})
class MlR3SellerResponseDatasetPostgresIT {
    @Container static PostgreSQLContainer<?> pg =
        new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",pg::getJdbcUrl);
        r.add("spring.datasource.username",pg::getUsername);
        r.add("spring.datasource.password",pg::getPassword);
    }
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerSkuRepository skus;
    @Autowired CatalogPublishingService publishing;
    @Autowired SellerMembershipRepository members;
    @Autowired InventoryLocationRepository locations;
    @Autowired InventoryBalanceRepository balances;
    @Autowired SellerDeliveryAreaRepository areas;
    @Autowired DealPersistenceService deals;
    @Autowired MlInvitationConsentService consent;
    @Autowired MlSellerInvitationService evaluator;
    @Autowired JdbcMlEventQueue queue;
    @Autowired JdbcMlEventProjectionSink sink;
    @Autowired MlSellerResponseDatasetBuilder dataset;
    @MockitoBean JwtDecoder decoder;

    @BeforeEach void isolateConsumerAndResearchEvidence() {
        jdbc.execute("""
            TRUNCATE ml_seller_invitation_view,ml_invitation_consent_audit,
              seller_bid_http_receipt,ml_event_projection_inbox,ml_seller_invitation_inbox,ml_event_delivery
            RESTART IDENTITY
            """);
    }

    private record Fixture(UUID seller,UUID buyer,UUID demand,UUID product,
                           UUID sku,UUID origin,UUID invitation,String token) {}

    private Jwt jwt(String sub) {
        return Jwt.withTokenValue("authenticated")
            .header("alg","RS256")
            .issuer("https://idp.example.test").subject(sub)
            .claim("aud",List.of("zakaz-api"))
            .claim("scope","deal:bid").build();
    }

    private Fixture fixture(boolean buyerOptIn,boolean sellerOptIn) {
        String salt=UUID.randomUUID().toString().replace("-","");
        String hcc=String.format("%02d%02d%03d",
            1+Integer.parseInt(salt.substring(0,2),16)%99,
            1+Integer.parseInt(salt.substring(2,4),16)%99,
            1+Integer.parseInt(salt.substring(4,7),16)%999)+"0000000000000";
        var category=categories.saveAndFlush(
            Category.create(hcc,ProductType.PHYSICAL,"R3 "+salt,null));
        var product=products.saveAndFlush(
            CanonicalProduct.create(category,"R3 Product "+salt));
        UUID seller=UUID.randomUUID(),buyer=UUID.randomUUID();
        var listing=listings.saveAndFlush(SellerListing.draft(seller,product));
        var sku=skus.saveAndFlush(SellerSku.create(
            listing,"R3-SKU",StockTrackingMode.TRACKED,MeasurementUnit.PCS));
        publishing.publishForSeller(seller,listing.getId());
        var origin=locations.saveAndFlush(InventoryLocation.create(
            seller,"R3-"+salt,"R3 shop","KZ-ALA","Almaty Region",
            "Almaty","Test avenue",LocationType.SHOP,null,true));
        balances.saveAndFlush(InventoryBalance.open(seller,origin.getId(),
            sku.getId(),new BigDecimal("10.000")));
        areas.saveAndFlush(SellerDeliveryArea.allow(seller,"Almaty"));
        String sub="r3-"+salt,token="r3-token-"+salt;
        members.saveAndFlush(SellerMembership.grant(
            "https://idp.example.test",sub,seller,
            SellerMembership.Role.SELLER_ADMIN));
        when(decoder.decode(token)).thenReturn(jwt(sub));
        var demand=new BuyerDemand(UUID.randomUUID(),buyer,"Almaty",
            Instant.now().plus(Duration.ofDays(4)),List.of(
                new BuyerDemand.DemandLine(product.getId(),BigDecimal.ONE,
                    new BigDecimal("500.00"),MeasurementUnit.PCS)));
        deals.createDemand(demand,Instant.now());
        consent.setDemandSharing(buyer,demand.id(),buyerOptIn);
        consent.setSellerInApp(seller,sellerOptIn);
        var decision=evaluator.evaluate(demand.id(),seller,1,Instant.now());
        assertThat(decision.status()).isEqualTo("ELIGIBLE");
        var dispatcher=new MlEventDispatcher(queue,sink,"r3-"+salt,24,3,
            Duration.ofSeconds(30));
        for(int i=0;i<6;i++)dispatcher.pollOnce();
        return new Fixture(seller,buyer,demand.id(),product.getId(),sku.getId(),
            origin.getId(),decision.id(),token);
    }

    private String url(Fixture f,String action) {
        return "/api/v1/sellers/"+f.seller()+"/invitations/"+
            f.invitation()+"/"+action;
    }
    private String bid(Fixture f) {
        return "{\"revision\":1,\"expiresAt\":\""+
            Instant.now().plusSeconds(900)+"\",\"lines\":[{\"productId\":\""+
            f.product()+"\",\"skuId\":\""+f.sku()+
            "\",\"quantity\":\"1.000\",\"unitPrice\":\"120.00\"}],"+
            "\"method\":\"PICKUP\",\"originLocationId\":\""+f.origin()+
            "\",\"deliveryFee\":\"0.00\"}";
    }
    private Instant viewedAt(Fixture f) {
        return Objects.requireNonNull(jdbc.queryForObject("""
            SELECT viewed_at FROM ml_seller_invitation_view WHERE invitation_id=?
            """,Timestamp.class,f.invitation())).toInstant();
    }

    @Test void inboxDeliveryAndQuoteGetAreNotAutomaticallyAView() throws Exception {
        var f=fixture(true,true);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM ml_seller_invitation_inbox WHERE invitation_id=?",
            Integer.class,f.invitation())).isEqualTo(1);
        assertThat(dataset.build(Instant.now()).samples()).isEmpty();
        http.perform(get(url(f,"quote-context"))
                .header("Authorization","Bearer "+f.token()))
            .andExpect(status().isOk());
        assertThat(dataset.build(Instant.now()).samples()).isEmpty();
        http.perform(post(url(f,"view"))).andExpect(status().isUnauthorized());
        http.perform(post(url(f,"view"))
                .header("Authorization","Bearer "+f.token()))
            .andExpect(status().isNoContent())
            .andExpect(header().string("Cache-Control",
                org.hamcrest.Matchers.containsString("no-store")));
        Instant first=viewedAt(f);
        http.perform(post(url(f,"view"))
                .header("Authorization","Bearer "+f.token()))
            .andExpect(status().isNoContent());
        assertThat(viewedAt(f)).isEqualTo(first);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM ml_seller_invitation_view WHERE invitation_id=?",
            Integer.class,f.invitation())).isEqualTo(1);
        var snapshot=dataset.build(Instant.now());
        assertThat(snapshot.samples()).hasSize(1);
        assertThat(snapshot.samples().getFirst().label())
            .isEqualTo(MlSellerResponseDatasetBuilder.Label.CENSORED);
        assertThat(snapshot.samples().getFirst().reason())
            .isEqualTo("INCOMPLETE_24H_HORIZON");
        assertThat(snapshot.trainable()).isFalse();
    }

    @Test void revokedConsentIsFailClosedAndNeverBecomesANegativeLabel() throws Exception {
        var f=fixture(true,true);
        http.perform(post(url(f,"view"))
                .header("Authorization","Bearer "+f.token()))
            .andExpect(status().isNoContent());
        consent.setDemandSharing(f.buyer(),f.demand(),false);
        http.perform(post(url(f,"view"))
                .header("Authorization","Bearer "+f.token()))
            .andExpect(status().isNotFound());
        var result=dataset.build(Instant.now());
        assertThat(result.samples()).hasSize(1);
        assertThat(result.samples().getFirst().reason()).isEqualTo("CONSENT_REVOKED");
        assertThat(result.negatives()).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ml_invitation_consent_audit
            WHERE scope='DEMAND' AND object_id=? AND enabled=false
            """,Integer.class,f.demand())).isEqualTo(1);
    }

    @Test void bidAfterAuthenticatedViewIsPositiveButCannotLeakIntoEarlierCutoff()
        throws Exception {
        var f=fixture(true,true);
        http.perform(post(url(f,"view"))
                .header("Authorization","Bearer "+f.token()))
            .andExpect(status().isNoContent());
        Instant cutoffBeforeBid=Instant.now();
        var before=dataset.build(cutoffBeforeBid);
        assertThat(before.samples()).hasSize(1);
        assertThat(before.positives()).isZero();

        http.perform(post(url(f,"bid"))
                .header("Authorization","Bearer "+f.token())
                .contentType(MediaType.APPLICATION_JSON).content(bid(f)))
            .andExpect(status().isCreated());

        var sameHistoricalCutoff=dataset.build(cutoffBeforeBid);
        assertThat(sameHistoricalCutoff.sha256()).isEqualTo(before.sha256());
        assertThat(sameHistoricalCutoff.samples()).isEqualTo(before.samples());

        Instant afterBid=Instant.now();
        var actual=dataset.build(afterBid);
        assertThat(actual.samples()).hasSize(1);
        assertThat(actual.positives()).isEqualTo(1);
        assertThat(actual.samples().getFirst().reason())
            .isEqualTo("VALID_BID_WITHIN_24H");
        assertThat(actual.samples().getFirst().label())
            .isEqualTo(MlSellerResponseDatasetBuilder.Label.POSITIVE);
        assertThat(dataset.build(afterBid).sha256()).isEqualTo(actual.sha256());
        assertThat(actual.sha256()).matches("[0-9a-f]{64}");
        assertThat(actual.trainable()).isFalse();
        assertThat(actual.gate()).isEqualTo("BLOCKED_NO_VERIFIED_NEGATIVE_LABELS");
    }

    @Test void bidBeforeViewCannotBeClaimedAsAResponseToTheView() throws Exception {
        var f=fixture(true,true);
        http.perform(post(url(f,"bid"))
                .header("Authorization","Bearer "+f.token())
                .contentType(MediaType.APPLICATION_JSON).content(bid(f)))
            .andExpect(status().isCreated());
        assertThat(dataset.build(Instant.now()).samples()).isEmpty();
        http.perform(post(url(f,"view"))
                .header("Authorization","Bearer "+f.token()))
            .andExpect(status().isNoContent());
        var result=dataset.build(Instant.now());
        assertThat(result.samples()).hasSize(1);
        assertThat(result.positives()).isZero();
        assertThat(result.samples().getFirst().label())
            .isEqualTo(MlSellerResponseDatasetBuilder.Label.CENSORED);
    }

    @Test void maturedUnansweredExposureIsCensoredWithoutContinuousEligibilityAudit() {
        var f=fixture(true,true);
        // Synthetic historical timestamps ONLY in this test fixture.
        // Production writes server-controlled clock_timestamp(), never backfill.
        Instant simulated=Instant.now().minus(Duration.ofDays(2));
        assertThat(jdbc.update("""
            UPDATE ml_seller_invitation_inbox
               SET delivered_at=? WHERE invitation_id=?
            """,Timestamp.from(simulated.minusSeconds(60)),f.invitation())).isEqualTo(1);
        assertThat(jdbc.update("""
            INSERT INTO ml_seller_invitation_view(
                invitation_id,demand_id,seller_id,viewed_at,recorded_at,requested_lines)
            VALUES (?,?,?,?,?,1)
            """,f.invitation(),f.demand(),f.seller(),
            Timestamp.from(simulated),Timestamp.from(simulated))).isEqualTo(1);
        var result=dataset.build(Instant.now());
        assertThat(result.samples()).hasSize(1);
        assertThat(result.samples().getFirst().reason())
            .isEqualTo("UNVERIFIED_CONTINUOUS_ELIGIBILITY");
        assertThat(result.negatives()).isZero();
        assertThat(result.trainable()).isFalse();
        assertThatThrownBy(()->dataset.build(Instant.now().plusSeconds(3600)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void viewAndConsentHistoryAreAppendOnlyAtTheDatabaseBoundary()
        throws Exception {
        var f=fixture(true,true);
        http.perform(post(url(f,"view"))
                .header("Authorization","Bearer "+f.token()))
            .andExpect(status().isNoContent());
        assertThatThrownBy(()->jdbc.update("""
            UPDATE ml_seller_invitation_view SET requested_lines=4
            WHERE invitation_id=?
            """,f.invitation())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("""
            DELETE FROM ml_invitation_consent_audit
            WHERE scope='DEMAND' AND object_id=?
            """,f.demand())).isInstanceOf(DataIntegrityViolationException.class);
    }
}
