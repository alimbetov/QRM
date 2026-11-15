package kz.zakaz.catalog;

import java.util.UUID;
import kz.zakaz.domain.types.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;

/**
 * SEARCH-S2 RED oracles. V22 must enforce media ownership lifecycle invariants
 * even when a caller bypasses Java/JPA. No storage provider is assumed.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test-pg")
class ListingMediaSchemaPostgresIT {
    @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerSkuRepository skus;
    @Autowired CatalogPublishingService publishing;

    private SellerListing draft() {
        String random = UUID.randomUUID().toString().replace("-", "");
        String hcc = String.format("%02d%02d%03d",
            1 + Integer.parseInt(random.substring(0,2), 16) % 99,
            1 + Integer.parseInt(random.substring(2,4), 16) % 99,
            1 + Integer.parseInt(random.substring(4,7), 16) % 999) + "0000000000000";
        var category=categories.saveAndFlush(Category.create(hcc,ProductType.PHYSICAL,"Media test",null));
        var product=products.saveAndFlush(CanonicalProduct.create(category,"Item "+random.substring(0,7)));
        var listing=listings.saveAndFlush(SellerListing.draft(UUID.randomUUID(),product));
        skus.saveAndFlush(SellerSku.create(listing,"SKU",StockTrackingMode.TRACKED,MeasurementUnit.PCS));
        return listing;
    }

    private UUID addMedia(SellerListing listing,String key,boolean cover,String status) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO catalog_listing_media(
                id,listing_id,object_key,content_type,sha256,width,height,
                sort_position,is_cover,status,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,clock_timestamp())
            """,id,listing.getId(),key,"image/jpeg","a".repeat(64),
                800,600,cover?0:1,cover,status);
        return id;
    }

    @Test void onlyOneReadyCoverAndInvalidMediaRejectedAtDatabase() {
        var listing=draft();
        UUID cover=addMedia(listing,"catalog/"+UUID.randomUUID()+"/cover.jpg",true,"READY");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM catalog_media_audit_event
            WHERE media_id=? AND event_type='UPLOADED'
            """,Integer.class,cover)).isEqualTo(1);
        assertThatThrownBy(()->jdbc.update(
            "DELETE FROM catalog_media_audit_event WHERE media_id=?",cover))
            .isInstanceOf(DataAccessException.class);
        assertThat(cover).isNotNull();
        assertThatThrownBy(()->addMedia(listing,"catalog/"+UUID.randomUUID()+"/duplicate.jpg",true,"READY"))
            .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("""
            INSERT INTO catalog_listing_media(
                id,listing_id,object_key,content_type,sha256,width,height,
                sort_position,is_cover,status,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,clock_timestamp())
            """,UUID.randomUUID(),listing.getId(),
                "catalog/"+UUID.randomUUID()+"/bad.svg","image/svg+xml","a".repeat(64),
                0,0,2,false,"READY"))
            .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("""
            INSERT INTO catalog_listing_media(
                id,listing_id,object_key,content_type,sha256,width,height,
                sort_position,is_cover,status,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,clock_timestamp())
            """,UUID.randomUUID(),UUID.randomUUID(),
                "catalog/"+UUID.randomUUID()+"/orphan.jpg","image/jpeg","a".repeat(64),
                800,600,3,false,"READY"))
            .isInstanceOf(DataAccessException.class);
    }

    @Test void publicationSealsMediaAndUnlistingHidesItFromPublicProjection() {
        var listing=draft();
        UUID mediaId=addMedia(listing,"catalog/"+UUID.randomUUID()+"/a.jpg",true,"READY");
        publishing.publishForSeller(listing.getSellerId(),listing.getId());
        assertThatThrownBy(()->addMedia(listing,"catalog/"+UUID.randomUUID()+"/late.jpg",false,"READY"))
            .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update(
            "UPDATE catalog_listing_media SET object_key=? WHERE id=?",
            "catalog/"+UUID.randomUUID()+"/tampered.jpg",mediaId))
            .isInstanceOf(DataAccessException.class);

        var publicSql="""
            SELECT COUNT(*) FROM catalog_listing_media m
            JOIN catalog_listing l ON l.id=m.listing_id
            JOIN catalog_product p ON p.id=l.product_id
            JOIN catalog_category c ON c.id=p.category_id
            JOIN qr_public_link q ON q.listing_id=l.id
            WHERE m.id=? AND m.status='READY' AND l.status='PUBLIC' AND c.active=true
            """;
        assertThat(jdbc.queryForObject(publicSql,Integer.class,mediaId)).isEqualTo(1);
        jdbc.update("UPDATE catalog_listing SET status='UNLISTED' WHERE id=?",listing.getId());
        assertThat(jdbc.queryForObject(publicSql,Integer.class,mediaId)).isZero();
    }

    @Test void stagedImageMustNotBePublicAndCannotBePromotedAfterPublishing() {
        var listing=draft();
        UUID staged=addMedia(listing,"catalog/"+UUID.randomUUID()+"/staged.jpg",true,"STAGED");
        publishing.publishForSeller(listing.getSellerId(),listing.getId());
        assertThatThrownBy(()->jdbc.update(
            "UPDATE catalog_listing_media SET status='READY' WHERE id=?",staged))
            .isInstanceOf(DataAccessException.class);
        int ready=jdbc.queryForObject("""
            SELECT count(*) FROM catalog_listing_media m
            JOIN catalog_listing l ON l.id=m.listing_id
            WHERE l.id=? AND l.status='PUBLIC' AND m.status='READY'
            """,Integer.class,listing.getId());
        assertThat(ready).isZero();
    }
}
