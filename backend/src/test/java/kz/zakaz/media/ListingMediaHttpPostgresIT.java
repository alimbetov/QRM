package kz.zakaz.media;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import javax.imageio.ImageIO;
import kz.zakaz.catalog.*;
import kz.zakaz.domain.types.*;
import kz.zakaz.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test-pg")
@TestPropertySource(properties={
    "zakaz.catalog.media.enabled=true",
    "zakaz.media.storage.provider=filesystem",
    "zakaz.media.scanner.provider=dev-only",
    "zakaz.search.http.enabled=true",
    "zakaz.catalog.feed.enabled=true",
    "zakaz.search.cursor-secret-base64=U0VBUkNILVQwMS1ub24tcHJvZHVjdGlvbi1yYW5kb20tMzItYnl0ZXM="
})
class ListingMediaHttpPostgresIT {
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:17-alpine");
    private static final String STORAGE=System.getProperty("java.io.tmpdir")+
            "/zakaz-s2-test-"+UUID.randomUUID();

    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",pg::getJdbcUrl);
        r.add("spring.datasource.username",pg::getUsername);
        r.add("spring.datasource.password",pg::getPassword);
        r.add("zakaz.media.storage.root",()->STORAGE);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerSkuRepository skus;
    @Autowired CatalogPublishingService publishing;
    @Autowired SellerMembershipRepository memberships;
    @MockitoBean JwtDecoder decoder;

    private record Fixture(UUID sellerId,UUID listingId){}
    private Fixture draft(String subject) {
        String n=UUID.randomUUID().toString().replace("-","");
        String code=String.format("%02d%02d%03d",1+Integer.parseInt(n.substring(0,2),16)%99,
           1+Integer.parseInt(n.substring(2,4),16)%99,1+Integer.parseInt(n.substring(4,7),16)%999)
           +"0000000000000";
        var category=categories.saveAndFlush(Category.create(code,ProductType.PHYSICAL,"Test",null));
        var product=products.saveAndFlush(CanonicalProduct.create(category,"Test image "+n));
        UUID seller=UUID.randomUUID();
        var listing=listings.saveAndFlush(SellerListing.draft(seller,product));
        skus.saveAndFlush(SellerSku.create(listing,"ONE",StockTrackingMode.TRACKED,MeasurementUnit.PCS));
        memberships.saveAndFlush(SellerMembership.grant(
            "https://idp.example.test",subject,seller,SellerMembership.Role.CATALOG_EDITOR));
        return new Fixture(seller,listing.getId());
    }

    private void auth(String sub) {
        when(decoder.decode(anyString())).thenReturn(Jwt.withTokenValue("signed")
            .header("alg","RS256").issuer("https://idp.example.test")
            .subject(sub).claim("scope","catalog:write").build());
    }

    private MockMultipartFile image() throws Exception {
        var pixels=new BufferedImage(640,360,BufferedImage.TYPE_INT_RGB);
        var out=new ByteArrayOutputStream();
        ImageIO.write(pixels,"png",out);
        return new MockMultipartFile("image","product.png","image/png",out.toByteArray());
    }

    @Test void uploadPublishGalleryReadAndUnpublishAllRespectLiveStatus() throws Exception {
        var f=draft("seller-image-owner");
        auth("seller-image-owner");
        var uploaded=mvc.perform(multipart("/api/v1/sellers/"+f.sellerId()+
                    "/listings/"+f.listingId()+"/media").file(image())
                    .param("cover","true").header("Authorization","Bearer token"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.mediaId").isString())
            .andReturn();
        String response=uploaded.getResponse().getContentAsString();
        UUID mediaId=UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(response).path("mediaId").asText());

        // The media cannot be fetched before Listing PUBLIC and a QR link exists.
        mvc.perform(get("/api/v1/public/media/"+mediaId)).andExpect(status().isNotFound());
        publishing.publishForSeller(f.sellerId(),f.listingId());
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM catalog_media_audit_event
            WHERE media_id=? AND event_type='UPLOADED'
            """,Integer.class,mediaId)).isEqualTo(1);
        String uniqueTitle=jdbc.queryForObject("""
            SELECT p.canonical_name FROM catalog_listing l
            JOIN catalog_product p ON p.id=l.product_id WHERE l.id=?
            """,String.class,f.listingId());
        var feed=mvc.perform(get("/api/v1/public/catalog/feed")
            .param("query",uniqueTitle).param("limit","24"))
            .andExpect(status().isOk()).andReturn();
        String feedBody=feed.getResponse().getContentAsString();
        assertThat(feedBody).contains(mediaId.toString());
        assertThat(feedBody).contains("coverUrl");
        mvc.perform(get("/api/v1/public/catalog/"+f.listingId()+"/media"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].mediaId").value(mediaId.toString()))
            .andExpect(jsonPath("$[0].cover").value(true));
        byte[] body=mvc.perform(get("/api/v1/public/media/"+mediaId+"?size=thumb"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type","image/jpeg"))
            .andReturn().getResponse().getContentAsByteArray();
        assertThat(body.length).isGreaterThan(100);
        assertThat(body[0]&255).isEqualTo(255);
        jdbc.update("UPDATE catalog_listing SET status='UNLISTED' WHERE id=?",f.listingId());
        mvc.perform(get("/api/v1/public/media/"+mediaId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/catalog/"+f.listingId()+"/media"))
            .andExpect(status().isNotFound());
    }

    @Test void forgedSellerOrUnsafeUploadIsRejectedBeforePersistence() throws Exception {
        var own=draft("seller-a");
        var other=draft("seller-b");
        auth("seller-a");
        mvc.perform(multipart("/api/v1/sellers/"+other.sellerId()+
                    "/listings/"+other.listingId()+"/media").file(image())
                    .header("Authorization","Bearer token"))
            .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/v1/sellers/"+own.sellerId()+
                    "/listings/"+other.listingId()+"/media").file(image())
                    .header("Authorization","Bearer token"))
            .andExpect(status().isNotFound());

        var svg=new MockMultipartFile("image","fake.svg","image/jpeg",
                                     "<svg></svg>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/v1/sellers/"+own.sellerId()+
                    "/listings/"+own.listingId()+"/media").file(svg)
                    .header("Authorization","Bearer token"))
            .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_listing_media WHERE listing_id=?",
            Integer.class,own.listingId())).isZero();
    }
}
