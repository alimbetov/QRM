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
    @Autowired SellerBidSubmissionService bidWriter;
    @Autowired MlInvitationConsentService consent;
    @Autowired MlSellerInvitationService evaluator;
    @Autowired JdbcMlEventQueue queue;
    @Autowired JdbcMlEventProjectionSink sink;
    @Autowired MlSellerResponseDatasetBuilder dataset;
    @MockitoBean JwtDecoder decoder;

    @BeforeEach void isolateConsumerAndResearchEvidence() {
        jdbc.execute("""
            TRUNCATE ml_invitation_bid_attribution,ml_seller_invitation_view,
              ml_invitation_consent_audit,
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
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ml_invitation_bid_attribution
            WHERE invitation_id=? AND demand_id=? AND seller_id=?
            """,Integer.class,f.invitation(),f.demand(),f.seller()))
            .as("The actual HTTP bid must be linked to this exact invitation")
            .isEqualTo(1);
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
        // A revision after the view must not fabricate an initial response.
        String revised=bid(f).replaceFirst("1,", "2,");
        http.perform(post(url(f,"bid"))
                .header("Authorization","Bearer "+f.token())
                .contentType(MediaType.APPLICATION_JSON).content(revised))
            .andExpect(status().isCreated());
        var result=dataset.build(Instant.now());
        assertThat(result.samples()).hasSize(1);
        assertThat(result.positives()).isZero();
        assertThat(result.samples().getFirst().label())
            .isEqualTo(MlSellerResponseDatasetBuilder.Label.CENSORED);
        assertThat(result.samples().getFirst().reason())
            .isEqualTo("PRIOR_VALID_BID_BEFORE_VIEW");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ml_invitation_bid_attribution
            WHERE invitation_id=?
            """,Integer.class,f.invitation()))
            .as("A prior bid and its revision must not be attributed to a later view")
            .isZero();
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
            .isEqualTo("PRE_MONITOR_EXPOSURE");
        assertThat(result.negatives()).isZero();
        assertThat(result.trainable()).isFalse();
        assertThatThrownBy(()->dataset.build(Instant.now().plusSeconds(3600)))
            .isInstanceOf(IllegalArgumentException.class);
    }


    @Test void repeatedPolicyInvitationsCountAsOneSellerDemandObservation()
        throws Exception {
        var f=fixture(true,true);
        http.perform(post(url(f,"view"))
                .header("Authorization","Bearer "+f.token()))
            .andExpect(status().isNoContent());
        var next=evaluator.evaluate(f.demand(),f.seller(),2,Instant.now());
        assertThat(next.status()).isEqualTo("ELIGIBLE");
        var dispatcher=new MlEventDispatcher(queue,sink,"r3-repeat",24,3,
            Duration.ofSeconds(30));
        for(int i=0;i<6;i++)dispatcher.pollOnce();
        String second="/api/v1/sellers/"+f.seller()+"/invitations/"+
            next.id()+"/view";
        http.perform(post(second)
                .header("Authorization","Bearer "+f.token()))
            .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ml_seller_invitation_view
            WHERE demand_id=? AND seller_id=?
            """,Integer.class,f.demand(),f.seller())).isEqualTo(2);
        var snapshot=dataset.build(Instant.now());
        assertThat(snapshot.samples()).hasSize(1);
        assertThat(snapshot.samples().getFirst().invitationId())
            .isEqualTo(f.invitation());
        // A real first bid against the SECOND URI must never be reassigned to
        // the earlier invitation, nor be double-counted as two observations.
        http.perform(post(second.replace("/view","/bid"))
                .header("Authorization","Bearer "+f.token())
                .contentType(MediaType.APPLICATION_JSON).content(bid(f)))
            .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ml_invitation_bid_attribution
            WHERE invitation_id=?
            """,Integer.class,next.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ml_invitation_bid_attribution
            WHERE invitation_id=?
            """,Integer.class,f.invitation())).isZero();
        var after=dataset.build(Instant.now());
        assertThat(after.samples()).hasSize(1);
        assertThat(after.positives()).isZero();
        assertThat(after.samples().getFirst().reason())
            .isEqualTo("MULTIPLE_EXPOSURES_IN_WINDOW");
    }


    /**
     * Synthetic clock shift is test-only. Production views are server
     * timestamps, and pre-monitor views can NEVER become negative.
     */
    @Test void completeAuditedTwentyFourHoursCanProduceOneVerifiedNegative()
        throws Exception {
        var f=fixture(true,true);
        Instant exposedAt=Instant.now().minus(Duration.ofDays(2));
        jdbc.update("""
            UPDATE ml_seller_invitation_inbox SET delivered_at=?
            WHERE invitation_id=?
            """,Timestamp.from(exposedAt.minusSeconds(30)),f.invitation());
        jdbc.update("""
            INSERT INTO ml_seller_invitation_view(
              invitation_id,demand_id,seller_id,viewed_at,recorded_at,requested_lines)
            VALUES (?,?,?,?,?,1)
            """,f.invitation(),f.demand(),f.seller(),
            Timestamp.from(exposedAt),Timestamp.from(exposedAt));
        try {
            // Test fixture only: emulate a migration epoch before the
            // synthetic observation. Real production epoch is immutable.
            jdbc.execute("TRUNCATE ml_r31_monitor_epoch");
            jdbc.update("""
                INSERT INTO ml_r31_monitor_epoch(singleton,started_at)
                VALUES (true,?)
                """,Timestamp.from(exposedAt.minusSeconds(3600)));
            var negative=dataset.build(Instant.now());
            assertThat(negative.samples()).hasSize(1);
            assertThat(negative.negatives()).isEqualTo(1);
            assertThat(negative.samples().getFirst().label())
                .isEqualTo(MlSellerResponseDatasetBuilder.Label.NEGATIVE);
            assertThat(negative.samples().getFirst().reason())
                .isEqualTo("VERIFIED_NO_RESPONSE_24H");
            assertThat(negative.trainable()).isFalse();

            // Even one warehouse stock change in the window voids the proof.
            jdbc.update("""
                INSERT INTO ml_r31_eligibility_change(
                    seller_id,source_table,recorded_at) VALUES (?,?,?)
                """,f.seller(),"inventory_balance",
                Timestamp.from(exposedAt.plusSeconds(3600)));
            var changed=dataset.build(Instant.now());
            assertThat(changed.negatives()).isZero();
            assertThat(changed.samples().getFirst().reason())
                .isEqualTo("ELIGIBILITY_CHANGED_IN_WINDOW");
            assertThat(changed.sha256()).isNotEqualTo(negative.sha256());
        } finally {
            jdbc.execute("TRUNCATE ml_r31_monitor_epoch");
            jdbc.execute("""
                INSERT INTO ml_r31_monitor_epoch(singleton,started_at)
                VALUES (true,clock_timestamp())
                """);
        }
    }

    @Test void qualifiedBidWithoutExactInvitationProvenanceIsNeverPositive()
        throws Exception {
        var f=fixture(true,true);
        http.perform(post(url(f,"view"))
                .header("Authorization","Bearer "+f.token()))
            .andExpect(status().isNoContent());
        var directBid=new SellerBid(UUID.randomUUID(),f.demand(),f.seller(),1,
            Instant.now().plusSeconds(900),List.of(
                new SellerBid.BidLine(f.product(),f.sku(),
                    BigDecimal.ONE,new BigDecimal("120.00"))));
        var directQuote=new SellerBidSubmissionService.QuoteRequest(
            SellerBidQuoteSnapshot.Method.PICKUP,f.origin(),BigDecimal.ZERO);
        // Valid authoritative commerce write, but deliberately NOT initiated
        // through the invitation-scoped HTTP endpoint.
        bidWriter.submit(f.seller(),directBid,directQuote,Instant.now());
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ml_invitation_bid_attribution
            WHERE invitation_id=?
            """,Integer.class,f.invitation())).isZero();
        var rows=dataset.build(Instant.now());
        assertThat(rows.positives()).isZero();
        assertThat(rows.negatives()).isZero();
        assertThat(rows.samples()).hasSize(1);
        assertThat(rows.samples().getFirst().reason())
            .isEqualTo("UNATTRIBUTED_BID_IN_WINDOW");
    }

    @Test void realInventoryMutationLeavesAnImmutableSellerScopedAuditEvent()
        throws Exception {
        var f=fixture(true,true);
        http.perform(post(url(f,"view"))
                .header("Authorization","Bearer "+f.token()))
            .andExpect(status().isNoContent());
        Instant before=Instant.now();
        assertThat(jdbc.update("""
            UPDATE inventory_balance SET reserved=1.000,version=version+1
            WHERE seller_id=? AND location_id=? AND sku_id=?
            """,f.seller(),f.origin(),f.sku())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ml_r31_eligibility_change
            WHERE seller_id=? AND source_table='inventory_balance'
              AND recorded_at>=?
            """,Integer.class,f.seller(),Timestamp.from(before)))
            .isGreaterThan(0);
        // An audit event cannot be retrospectively deleted by an app worker.
        assertThatThrownBy(()->jdbc.update("""
            DELETE FROM ml_r31_eligibility_change WHERE seller_id=?
            """,f.seller())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void unquotedTradeActivityCannotBecomeAFalseNegative() throws Exception {
        var f=fixture(true,true);
        http.perform(post(url(f,"view"))
                .header("Authorization","Bearer "+f.token()))
            .andExpect(status().isNoContent());
        // Synthetic direct-SQL anomaly: an immutable SellerBid and its V24
        // event exist, but the corresponding quote snapshot was never saved.
        // This must never be treated as no seller activity.
        Instant now=Instant.now();
        assertThat(jdbc.update("""
            INSERT INTO seller_bid_snapshot(
                id,demand_id,seller_id,revision,expires_at,submitted_at,version)
            VALUES (?,?,?,?,?,?,0)
            """,UUID.randomUUID(),f.demand(),f.seller(),1,
            Timestamp.from(now.plusSeconds(1800)),Timestamp.from(now)))
            .isEqualTo(1);
        var result=dataset.build(Instant.now());
        assertThat(result.samples()).hasSize(1);
        assertThat(result.negatives()).isZero();
        assertThat(result.positives()).isZero();
        assertThat(result.samples().getFirst().reason())
            .isEqualTo("UNATTRIBUTED_BID_IN_WINDOW");
    }

    @Test void membershipTerritoryAndGlobalSchemaMutationsAreAudited()
        throws Exception {
        var f=fixture(true,true);
        http.perform(post(url(f,"view"))
                .header("Authorization","Bearer "+f.token()))
            .andExpect(status().isNoContent());
        assertThat(jdbc.update("""
            UPDATE seller_membership SET active=false
            WHERE seller_id=? AND active=true
            """,f.seller())).isEqualTo(1);
        assertThat(jdbc.update("""
            UPDATE seller_delivery_area SET active=false
            WHERE seller_id=? AND active=true
            """,f.seller())).isEqualTo(1);
        var sources=jdbc.query("""
            SELECT DISTINCT source_table FROM ml_r31_eligibility_change
            WHERE seller_id=?
            """,(rs,n)->rs.getString(1),f.seller());
        assertThat(sources).contains("seller_membership","seller_delivery_area");

        assertThat(jdbc.update("""
            INSERT INTO catalog_attribute_definition(
                id,code,kind,active)
            VALUES (?,?,'BOOLEAN',true)
            """,UUID.randomUUID(),"AUDIT_"+UUID.randomUUID().toString().replace("-","")
            )).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM ml_r31_eligibility_change
            WHERE seller_id IS NULL
              AND source_table='catalog_attribute_definition'
            """,Integer.class)).isGreaterThan(0);
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
