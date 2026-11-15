package kz.zakaz.security;

import java.util.*;
import kz.zakaz.catalog.*;
import kz.zakaz.domain.types.ProductType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Seller UI real PostgreSQL gate: no partial draft on failed SKU price or foreign identity. */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test-pg") @Testcontainers
@TestPropertySource(properties="zakaz.seller.workspace.enabled=true")
class SellerCatalogWorkspacePostgresIT {
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",pg::getJdbcUrl);
        r.add("spring.datasource.username",pg::getUsername);
        r.add("spring.datasource.password",pg::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SellerMembershipRepository memberships;
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @MockitoBean JwtDecoder decoder;

    private UUID product() {
        String bits=UUID.randomUUID().toString().replace("-","");
        String code=String.format("%02d%02d%03d",
            1+Integer.parseInt(bits.substring(0,2),16)%99,
            1+Integer.parseInt(bits.substring(2,4),16)%99,
            1+Integer.parseInt(bits.substring(4,7),16)%999)+"0000000000000";
        var cat=categories.saveAndFlush(Category.create(code,ProductType.PHYSICAL,
            "Workspace PostgreSQL item",null));
        return products.saveAndFlush(
            CanonicalProduct.create(cat,"Workspace PostgreSQL item")).getId();
    }
    private String body(UUID productId,String price) {
        return "{\"productId\":\""+productId+"\",\"skuCode\":\"ITEM-ONE\","
            +"\"unit\":\"PCS\",\"trackingMode\":\"TRACKED\","
            +"\"publicPriceKzt\":\""+price+"\"}";
    }
    private void authenticate(UUID seller) {
        memberships.saveAndFlush(SellerMembership.grant(
            "https://idp.example.test","merchant-"+seller,seller,
            SellerMembership.Role.CATALOG_EDITOR));
        when(decoder.decode(anyString())).thenReturn(
            Jwt.withTokenValue("signed").header("alg","RS256")
                .issuer("https://idp.example.test").subject("merchant-"+seller)
                .claim("aud",List.of("zakaz-api"))
                .claim("scope","catalog:write").build());
    }

    @Test void catalogEditorFindsUnpublishedCanonicalProductWithoutGuessingId() throws Exception {
        UUID seller=UUID.randomUUID(),hiddenCategoryProduct=product();
        UUID categoryId=jdbc.queryForObject(
            "SELECT category_id FROM catalog_product WHERE id=?",
            UUID.class,hiddenCategoryProduct);
        var category=categories.findById(categoryId).orElseThrow();
        String uniqueName="Rare seller widget "+UUID.randomUUID().toString().substring(0,8);
        var eligible=products.saveAndFlush(CanonicalProduct.create(category,uniqueName));
        // A canonical product exists before any seller creates a Listing.
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM catalog_listing WHERE product_id=?",
            Integer.class,eligible.getId())).isZero();
        authenticate(seller);
        String url="/api/v1/sellers/"+seller+"/catalog-products";
        mvc.perform(get(url).param("query",uniqueName)
                .header("Authorization","Bearer signed"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(eligible.getId().toString()))
            .andExpect(jsonPath("$[0].name").value(uniqueName))
            .andExpect(jsonPath("$[0].categoryCode").value(category.getHccCode()))
            .andExpect(jsonPath("$[0].categoryLabel").isNotEmpty())
            .andExpect(jsonPath("$[0].sellerId").doesNotExist());
        // The product shown by the selector can actually be drafted using the
        // same protected real PostgreSQL route, without a manual UUID input.
        mvc.perform(post("/api/v1/sellers/"+seller+"/listings")
                .header("Authorization","Bearer signed")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(eligible.getId(),"250.50")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.productId").value(eligible.getId().toString()));
    }

    @Test void catalogPickerRejectsForeignAnonymousAndInvalidSearches() throws Exception {
        UUID seller=UUID.randomUUID(),foreign=UUID.randomUUID();
        authenticate(seller);
        String url="/api/v1/sellers/"+seller+"/catalog-products";
        mvc.perform(get(url).param("query","widget"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/sellers/"+foreign+"/catalog-products")
                .param("query","widget")
                .header("Authorization","Bearer signed"))
            .andExpect(status().isForbidden());
        for(String invalid:List.of("x","%","a_b","ab%","a".repeat(81))) {
            mvc.perform(get(url).param("query",invalid)
                    .header("Authorization","Bearer signed"))
                .andExpect(status().isBadRequest());
        }
        when(decoder.decode(anyString())).thenReturn(
            Jwt.withTokenValue("signed").header("alg","RS256")
                .issuer("https://idp.example.test").subject("merchant-"+seller)
                .claim("aud",List.of("zakaz-api"))
                .claim("scope","deal:bid").build());
        mvc.perform(get(url).param("query","widget")
                .header("Authorization","Bearer signed"))
            .andExpect(status().isForbidden());
    }

    @Test void postDraftIsAtomicWithSkuAndExactPriceInPostgresql() throws Exception {
        UUID product=product(),seller=UUID.randomUUID();
        authenticate(seller);
        String url="/api/v1/sellers/"+seller+"/listings";
        mvc.perform(post(url).header("Authorization","Bearer signed")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(product,"125000.50")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.variants[0].priceKzt").value("125000.50"));
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM catalog_listing WHERE seller_id=?",
            Integer.class,seller)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM catalog_sku s JOIN catalog_listing l ON l.id=s.listing_id
            WHERE l.seller_id=? AND s.public_unit_price_kzt=125000.50
            """,Integer.class,seller)).isEqualTo(1);
    }

    @Test void invalidSkuPriceRollsBackBothListingAndVariant() throws Exception {
        UUID product=product(),seller=UUID.randomUUID();
        authenticate(seller);
        String url="/api/v1/sellers/"+seller+"/listings";
        mvc.perform(post(url).header("Authorization","Bearer signed")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(product,"10.999")))
            .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM catalog_listing WHERE seller_id=?",
            Integer.class,seller)).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM catalog_sku s JOIN catalog_listing l ON l.id=s.listing_id
            WHERE l.seller_id=?
            """,Integer.class,seller)).isZero();
    }

    @Test void forgedCompanyCannotReadOrCreateDraft() throws Exception {
        UUID seller=UUID.randomUUID(),foreign=UUID.randomUUID();
        authenticate(seller);
        UUID product=product();
        String url="/api/v1/sellers/"+foreign+"/listings";
        mvc.perform(get(url).header("Authorization","Bearer signed"))
            .andExpect(status().isForbidden());
        mvc.perform(post(url).header("Authorization","Bearer signed")
                .contentType(MediaType.APPLICATION_JSON).content(body(product,"1.00")))
            .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM catalog_listing WHERE seller_id=?",
            Integer.class,foreign)).isZero();
    }
}
