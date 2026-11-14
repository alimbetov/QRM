package kz.zakaz.catalog;

import java.util.*;
import kz.zakaz.domain.types.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc @Testcontainers
@ActiveProfiles("test-pg")
@TestPropertySource(properties={
    "zakaz.search.http.enabled=true",
    "zakaz.catalog.feed.enabled=true",
    "zakaz.catalog.media.enabled=false",
    "zakaz.search.cursor-secret-base64=U0VBUkNILVQwMS1ub24tcHJvZHVjdGlvbi1yYW5kb20tMzItYnl0ZXM="
})
class CatalogFeedPostgresIT {
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",pg::getJdbcUrl);
        r.add("spring.datasource.username",pg::getUsername);
        r.add("spring.datasource.password",pg::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerSkuRepository skus;
    @Autowired CatalogPublishingService publishing;

    @Test void pageByCursorAndFilterPrivateListingsWithoutMedia() throws Exception {
        String random=UUID.randomUUID().toString().replace("-","");
        String code=String.format("%02d%02d%03d",1+Integer.parseInt(random.substring(0,2),16)%99,
            1+Integer.parseInt(random.substring(2,4),16)%99,
            1+Integer.parseInt(random.substring(4,7),16)%999)+"0000000000000";
        var category=categories.saveAndFlush(Category.create(code,ProductType.PHYSICAL,"Tools",null));
        Set<String> expected=new HashSet<>();
        UUID hidden=null;
        for(int i=0;i<6;i++) {
            var product=products.saveAndFlush(CanonicalProduct.create(category,"Search photo "+random+"-"+i));
            UUID seller=UUID.randomUUID();
            var listing=listings.saveAndFlush(SellerListing.draft(seller,product));
            skus.saveAndFlush(SellerSku.create(listing,"CODE",StockTrackingMode.TRACKED,MeasurementUnit.PCS));
            if(i<5) {
                publishing.publishForSeller(seller,listing.getId());
                if(i==4) hidden=listing.getId();
                else expected.add(listing.getId().toString());
            }
        }
        jdbc.update("UPDATE catalog_listing SET status='UNLISTED' WHERE id=?",hidden);
        Set<String> found=new HashSet<>();
        String cursor=null;
        for(int page=0;page<3;page++) {
            var req=get("/api/v1/public/catalog/feed").param("query",random)
                .param("categoryCode",code).param("limit","2");
            if(cursor!=null) req.param("cursor",cursor);
            var body=mvc.perform(req).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
            var response=json.readTree(body);
            for(var item:response.path("items")) {
                String id=item.path("listingId").asText();
                assertThat(found.add(id)).isTrue();
                assertThat(item.path("coverUrl").isNull()).isTrue();
                assertThat(item.path("imageCount").asInt()).isZero();
            }
            cursor=response.path("nextCursor").isNull()?null:response.path("nextCursor").asText();
            if(cursor==null) break;
        }
        assertThat(found).containsExactlyInAnyOrderElementsOf(expected);
        mvc.perform(get("/api/v1/public/catalog/feed").param("limit","100"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/public/catalog/feed").param("query",random)
            .param("categoryCode",code).param("cursor","not-signed"))
            .andExpect(status().isBadRequest());
    }
}
