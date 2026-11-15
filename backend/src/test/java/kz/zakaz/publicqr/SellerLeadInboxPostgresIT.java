package kz.zakaz.publicqr;

import java.util.*;
import kz.zakaz.catalog.*;
import kz.zakaz.domain.types.*;
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
@TestPropertySource(properties="zakaz.qr.http.enabled=true")
class SellerLeadInboxPostgresIT {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",db::getJdbcUrl);
        r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
        r.add("zakaz.leads.phone-key-base64",
            ()->"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }
    @Autowired MockMvc mvc;
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerSkuRepository skus;
    @Autowired CatalogPublishingService publisher;
    @Autowired PublicListingLinkRepository links;
    @Autowired SellerMembershipRepository members;
    @Autowired LeadReadAuditRepository reads;
    @Autowired PurchaseLeadRepository leads;
    @MockitoBean JwtDecoder decoder;

    @Test void onlySellerAdminCanReadEncryptedGuestContactWithAuditableAccess() throws Exception {
        var category=categories.saveAndFlush(Category.create(
            "01020700000000000000",ProductType.PHYSICAL,"Devices",null));
        var product=products.saveAndFlush(CanonicalProduct.create(category,"Headphones"));
        UUID seller=UUID.randomUUID();
        var listing=listings.saveAndFlush(SellerListing.draft(seller,product));
        skus.saveAndFlush(SellerSku.create(listing,"HEADPHONES",StockTrackingMode.TRACKED,MeasurementUnit.PCS));
        publisher.publishForSeller(seller,listing.getId());
        String token=links.findByListing_Id(listing.getId()).orElseThrow().getToken();
        mvc.perform(post("/api/v1/public/q/"+token+"/leads")
            .header("Idempotency-Key","private-lead-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"phone\":\"+77011234567\",\"consent\":true}"))
            .andExpect(status().isAccepted());
        String inbox="/api/v1/sellers/"+seller+"/leads";
        mvc.perform(get(inbox)).andExpect(status().isUnauthorized());
        var jwt=Jwt.withTokenValue("internal-mock").header("alg","RS256")
            .issuer("https://idp.example.test").subject("seller-owner")
            .claim("scope","leads:read").build();
        when(decoder.decode("merchant-token")).thenReturn(jwt);
        mvc.perform(get(inbox).header("Authorization","Bearer merchant-token"))
            .andExpect(status().isForbidden());
        members.saveAndFlush(SellerMembership.grant(
            "https://idp.example.test","seller-owner",seller,SellerMembership.Role.SELLER_ADMIN));
        mvc.perform(get("/api/v1/sellers/"+UUID.randomUUID()+"/leads")
            .header("Authorization","Bearer merchant-token")).andExpect(status().isForbidden());
        mvc.perform(get(inbox).header("Authorization","Bearer merchant-token"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$[0].listingId").value(listing.getId().toString()))
            .andExpect(jsonPath("$[0].phone").value("+77011234567"))
            .andExpect(jsonPath("$[0].buyerId").doesNotExist());

        assertThat(reads.count()).isEqualTo(1);
        UUID leadId=leads.findByListingId(listing.getId()).getFirst().getId();
        String leadStatus=inbox+"/"+leadId+"/status";
        mvc.perform(put(leadStatus)
            .header("Authorization","Bearer merchant-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"CONTACTED\"}"))
            .andExpect(status().isNoContent());
        mvc.perform(put(leadStatus)
            .header("Authorization","Bearer merchant-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"CONTACTED\"}"))
            .andExpect(status().isNoContent());
        mvc.perform(put(leadStatus)
            .header("Authorization","Bearer merchant-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"DECLINED\"}"))
            .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/sellers/"+UUID.randomUUID()+"/leads/"+leadId+"/status")
            .header("Authorization","Bearer merchant-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"DECLINED\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(get(inbox).header("Authorization","Bearer merchant-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].leadId").value(leadId.toString()))
            .andExpect(jsonPath("$[0].status").value("CONTACTED"));
        assertThat(reads.count()).isEqualTo(2);
    }
}
