package kz.zakaz.publicqr;

import java.util.*;
import java.math.BigDecimal;
import kz.zakaz.catalog.*;
import kz.zakaz.domain.types.*;
import kz.zakaz.order.CustomerOrderRepository;
import kz.zakaz.security.SellerMembership;
import kz.zakaz.security.SellerMembershipRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test-pg") @Testcontainers
@TestPropertySource(properties="zakaz.qr.http.enabled=true")
class PublicQrJourneyPostgresIT {
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",pg::getJdbcUrl);
        r.add("spring.datasource.username",pg::getUsername);
        r.add("spring.datasource.password",pg::getPassword);
        r.add("zakaz.leads.phone-key-base64",
            ()->"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="); // isolated test key only
    }

    @Autowired MockMvc mvc;
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerSkuRepository skus;
    @Autowired CatalogPublishingService publisher;
    @Autowired PublicListingLinkRepository links;
    @Autowired PurchaseLeadRepository leads;
    @Autowired CustomerOrderRepository orders;
    @Autowired SellerMembershipRepository memberships;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JwtDecoder decoder;

    private SellerListing createListing(boolean publish) {
        String code=String.format("%02d%02d%03d",1+Math.floorMod(UUID.randomUUID().hashCode(),99),
                1+Math.floorMod(UUID.randomUUID().hashCode(),99),
                1+Math.floorMod(UUID.randomUUID().hashCode(),999))+"0000000000000";
        var category=categories.saveAndFlush(Category.create(
            code,ProductType.PHYSICAL,"Phones",null));
        var product=products.saveAndFlush(CanonicalProduct.create(category,"Public Phone"));
        var seller=UUID.randomUUID();
        var listing=listings.saveAndFlush(SellerListing.draft(seller,product));
        skus.saveAndFlush(SellerSku.create(listing,"PHONE-128",StockTrackingMode.TRACKED,
                                           MeasurementUnit.PCS));
        if(publish)publisher.publishForSeller(seller,listing.getId());
        return listing;
    }
    private String path(SellerListing listing) {
        String token=links.findByListing_Id(listing.getId()).orElseThrow().getToken();
        return "/api/v1/public/q/"+token;
    }

    @Test void publishedQrIsStableAndShowsOnlyWhitelistedCatalogFields() throws Exception {
        var draft=createListing(false);
        assertThat(links.findByListing_Id(draft.getId())).isEmpty();
        mvc.perform(get("/api/v1/public/q/"+UUID.randomUUID()))
            .andExpect(status().isNotFound());
        var listing=createListing(true);
        var issued=links.findByListing_Id(listing.getId()).orElseThrow();
        assertThat(issued.getToken()).hasSizeGreaterThanOrEqualTo(32);
        assertThat(issued.getToken()).doesNotContain(listing.getId().toString());
        var url=path(listing);
        mvc.perform(get(url))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Public Phone"))
            .andExpect(jsonPath("$.sellerId").value(listing.getSellerId().toString()))
            .andExpect(jsonPath("$.variants[0].unit").value("PCS"))
            .andExpect(jsonPath("$.variants[0].code").value("PHONE-128"))
            .andExpect(jsonPath("$.stock").doesNotExist())
            .andExpect(jsonPath("$.buyerId").doesNotExist())
            .andExpect(jsonPath("$.phone").doesNotExist());
    }
    @Test void guestConsentAndIdempotencyProduceLeadNeverOrderOrPlaintext() throws Exception {
        var listing=createListing(true);
        var url=path(listing)+"/leads";
        long initialOrders=orders.count();
        String valid="{\"phone\":\"+77011234567\",\"consent\":true}";
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key","callback-1").content(valid))
            .andExpect(status().isAccepted());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key","callback-1").content(valid))
            .andExpect(status().isAccepted());
        var entries=leads.findByListingId(listing.getId());
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().getPhoneCiphertext()).doesNotContain("77011234567");
        assertThat(orders.count()).isEqualTo(initialOrders);
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key","callback-1")
                .content("{\"phone\":\"+77019876543\",\"consent\":true}"))
            .andExpect(status().isConflict());
        assertThat(leads.findByListingId(listing.getId())).hasSize(1);
    }
    @Test void guestLeadRejectsConsentErrorsAndRateLimitsByIpPerQr() throws Exception {
        var url=path(createListing(true))+"/leads";
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key","without-consent")
                .content("{\"phone\":\"+77011234567\",\"consent\":false}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key","wrong-phone")
                .content("{\"phone\":\"555\",\"consent\":true}"))
            .andExpect(status().isBadRequest());
        for(int i=0;i<5;i++)mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key","guest-"+i)
                .content("{\"phone\":\"+77011234567\",\"consent\":true}"))
            .andExpect(status().isAccepted());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key","guest-six")
                .content("{\"phone\":\"+77011234567\",\"consent\":true}"))
            .andExpect(status().isTooManyRequests());
    }
    @Test void sellerSetsRealDraftPriceAndQrDisplaysExactKztString() throws Exception {
        var listing=createListing(false);
        var sku=skus.findByListingId(listing.getId()).getFirst();
        var url="/api/v1/sellers/"+listing.getSellerId()+"/listings/"+listing.getId()
            +"/skus/"+sku.getId()+"/price";
        var body="{\"priceKzt\":125000.50}";
        mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());

        var subject="price-editor-"+UUID.randomUUID();
        when(decoder.decode(anyString())).thenReturn(Jwt.withTokenValue("signed-test")
            .header("alg","RS256").issuer("https://idp.example.test").subject(subject)
            .claim("aud",List.of("zakaz-api")).claim("scope","catalog:write").build());
        mvc.perform(put(url).header("Authorization","Bearer signed-test")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
        memberships.saveAndFlush(SellerMembership.grant("https://idp.example.test",
            subject,listing.getSellerId(),SellerMembership.Role.CATALOG_EDITOR));

        mvc.perform(put(url).header("Authorization","Bearer signed-test")
                .contentType(MediaType.APPLICATION_JSON).content("{\"priceKzt\":-1.00}"))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(put(url).header("Authorization","Bearer signed-test")
                .contentType(MediaType.APPLICATION_JSON).content("{\"priceKzt\":12.345}"))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(put(url).header("Authorization","Bearer signed-test")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isNoContent());
        assertThat(skus.findById(sku.getId()).orElseThrow().getPublicUnitPriceKzt())
            .isEqualByComparingTo(new BigDecimal("125000.50"));

        publisher.publishForSeller(listing.getSellerId(),listing.getId());
        mvc.perform(get(path(listing)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.variants[0].priceKzt").value("125000.50"))
            .andExpect(jsonPath("$.variants[0].currencyCode").doesNotExist())
            .andExpect(jsonPath("$.stock").doesNotExist());

        mvc.perform(put(url).header("Authorization","Bearer signed-test")
                .contentType(MediaType.APPLICATION_JSON).content("{\"priceKzt\":999.00}"))
            .andExpect(status().isConflict());
        assertThatThrownBy(()->jdbc.update(
            "UPDATE catalog_sku SET public_unit_price_kzt = ? WHERE id = ?",
            new BigDecimal("999.00"),sku.getId()))
            .isInstanceOf(DataAccessException.class);
        assertThat(skus.findById(sku.getId()).orElseThrow().getPublicUnitPriceKzt())
            .isEqualByComparingTo(new BigDecimal("125000.50"));
    }

    @Test void merchantCannotSetPriceOfAnotherSellersSku() throws Exception {
        var listing=createListing(false);
        var sku=skus.findByListingId(listing.getId()).getFirst();
        UUID attackerSeller=UUID.randomUUID();
        var subject="foreign-price-"+UUID.randomUUID();
        memberships.saveAndFlush(SellerMembership.grant("https://idp.example.test",
            subject,attackerSeller,SellerMembership.Role.CATALOG_EDITOR));
        when(decoder.decode(anyString())).thenReturn(Jwt.withTokenValue("attacker")
            .header("alg","RS256").issuer("https://idp.example.test").subject(subject)
            .claim("aud",List.of("zakaz-api")).claim("scope","catalog:write").build());
        String url="/api/v1/sellers/"+attackerSeller+"/listings/"+listing.getId()
            +"/skus/"+sku.getId()+"/price";
        mvc.perform(put(url).header("Authorization","Bearer attacker")
                .contentType(MediaType.APPLICATION_JSON).content("{\"priceKzt\":500.00}"))
            .andExpect(status().isForbidden());
        assertThat(skus.findById(sku.getId()).orElseThrow().getPublicUnitPriceKzt())
            .isNull();
    }


    /**
     * QA03-003 / EDGE-01: a QR opened while PUBLIC cannot be used to create
     * a callback after the listing becomes ARCHIVED. This also proves
     * that the public read guard and the lead submission guard agree.
     */
    @Test void archivedBetweenQrViewAndLeadSubmitCannotCreateLeadOrOrder() throws Exception {
        var listing=createListing(true);
        String publicPath=path(listing);
        long ordersBefore=orders.count();
        int leadsBefore=leads.findByListingId(listing.getId()).size();

        mvc.perform(get(publicPath))
            .andExpect(status().isOk());
        assertThat(jdbc.update("""
            UPDATE catalog_listing SET status='ARCHIVED'
            WHERE id=? AND status='PUBLIC'
            """,listing.getId())).isEqualTo(1);

        mvc.perform(get(publicPath))
            .andExpect(status().isNotFound());
        mvc.perform(post(publicPath+"/leads")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key","archived-after-view")
                .content("{\"phone\":\"+77011234567\",\"consent\":true}"))
            .andExpect(status().isNotFound());

        assertThat(leads.findByListingId(listing.getId())).hasSize(leadsBefore);
        assertThat(orders.count()).isEqualTo(ordersBefore);
    }

}
