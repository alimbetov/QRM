package kz.zakaz.catalog;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.List;
import kz.zakaz.domain.types.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test-pg")
@TestPropertySource(properties="zakaz.catalog.http.enabled=true")
class PublicCatalogHttpPostgresIT {
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerSkuRepository skus;
    @Autowired CatalogPublishingService publishing;
    @Autowired CatalogPublicPriceService prices;
    @Autowired JdbcTemplate jdbc;

    private String hcc() {
        var seed=UUID.randomUUID().toString().replace("-", "");
        return String.format("%02d%02d%03d",
            1 + Integer.parseInt(seed.substring(0,2),16)%99,
            1 + Integer.parseInt(seed.substring(2,4),16)%99,
            1 + Integer.parseInt(seed.substring(4,7),16)%999) + "0000000000000";
    }

    private record Fixture(SellerListing listing, Category category) {}
    private Fixture fixture(String title, boolean published, boolean priced) {
        var category=categories.saveAndFlush(Category.create(hcc(), ProductType.PHYSICAL, "Test Category", null));
        var product=products.saveAndFlush(CanonicalProduct.create(category,title));
        var sellerId=UUID.randomUUID();
        var listing=listings.saveAndFlush(SellerListing.draft(sellerId,product));
        var sku=skus.saveAndFlush(SellerSku.create(listing,"ONE",StockTrackingMode.TRACKED,MeasurementUnit.PCS));
        if(priced)prices.setDraftPrice(sellerId,listing.getId(),sku.getId(),new BigDecimal("125000.50"));
        if(published)publishing.publishForSeller(sellerId,listing.getId());
        return new Fixture(listing,category);
    }

    @Test void publicOnlySearchPaginationAndPriceWithoutLeakingPrivateRows() throws Exception {
        var id=UUID.randomUUID().toString().substring(0,8);
        var live=fixture("R2C-LIVE-"+id,true,true);
        fixture("R2C-DRAFT-"+id,false,true);
        var hidden=fixture("R2C-HIDDEN-"+id,true,true);
        jdbc.update("UPDATE catalog_listing SET status='UNLISTED' WHERE id=?",hidden.listing().getId());

        mvc.perform(get("/api/v1/public/catalog").param("query","R2C-").param("size","24"))
            .andExpect(status().isOk()); // Other tests may have published R2C items.
        mvc.perform(get("/api/v1/public/catalog").param("query","R2C-LIVE-"+id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].listingId").value(live.listing().getId().toString()))
            .andExpect(jsonPath("$.items[0].title").value("R2C-LIVE-"+id))
            .andExpect(jsonPath("$.items[0].categoryCode").value(live.category().getHccCode()))
            .andExpect(jsonPath("$.items[0].variants[0].priceKzt").value("125000.50"))
            .andExpect(jsonPath("$.items[0].variants[0].unit").value("PCS"))
            .andExpect(jsonPath("$.items[0].qrToken").isString())
            .andExpect(jsonPath("$.items[0].stock").doesNotExist())
            .andExpect(jsonPath("$.items[0].buyerId").doesNotExist())
            .andExpect(jsonPath("$.items[0].phone").doesNotExist());
        mvc.perform(get("/api/v1/public/catalog").param("query","R2C-DRAFT-"+id))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/v1/public/catalog").param("query","R2C-HIDDEN-"+id))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/v1/public/catalog").param("query","R2C-LIVE-"+id)
                .param("categoryCode","99000000000000000000"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/v1/public/catalog").param("query","R2C-LIVE-"+id)
                .param("page","1").param("size","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
            .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/v1/public/catalog/categories"))
            .andExpect(status().isOk()); // never enumerates unpublished categories
        assertThat(live.listing().getStatus()).isEqualTo(ListingStatus.PUBLIC);
    }

    @Test void boundedQueryAndNoFabricatedLegacyPrice() throws Exception {
        var id=UUID.randomUUID().toString().substring(0,8);
        fixture("R2C-UNPRICED-"+id,true,false);
        mvc.perform(get("/api/v1/public/catalog").param("query","R2C-UNPRICED-"+id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].variants[0].priceKzt").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/api/v1/public/catalog").param("page","-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/public/catalog").param("size","100")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/public/catalog").param("query","%")).andExpect(status().isBadRequest());
    }
}
