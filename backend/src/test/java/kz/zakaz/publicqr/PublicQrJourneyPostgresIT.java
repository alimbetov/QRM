package kz.zakaz.publicqr;

import java.util.*;
import kz.zakaz.catalog.*;
import kz.zakaz.domain.types.*;
import kz.zakaz.order.CustomerOrderRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test-pg") @Testcontainers
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
}
