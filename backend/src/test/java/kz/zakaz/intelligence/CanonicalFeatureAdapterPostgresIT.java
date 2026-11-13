package kz.zakaz.intelligence;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import kz.zakaz.catalog.*;
import kz.zakaz.domain.types.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;

/**
 * ML-R1 PostgreSQL RED contract: never manufacture a second HCC/attribute
 * dictionary and never rank before the seller/catalog hard gates.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test-pg")
@Transactional
class CanonicalFeatureAdapterPostgresIT {
    @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",pg::getJdbcUrl);
        registry.add("spring.datasource.username",pg::getUsername);
        registry.add("spring.datasource.password",pg::getPassword);
    }

    @Autowired CanonicalFeatureAdapter adapter;
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerSkuRepository skus;
    @Autowired CategoryAttributeRuleRepository rules;
    @Autowired SkuAttributeValueRepository values;
    @Autowired AttributeEnumOptionRepository enumOptions;
    @Autowired CatalogPublishingService publisher;
    @Autowired EntityManager entities;
    @Autowired JdbcTemplate jdbc;

    private record Sample(UUID seller,UUID sku,UUID product,String hcc,UUID enumOption){}

    private Sample sample(boolean publish) {
        String hcc=String.format("%02d%02d%03d",1+Math.abs(UUID.randomUUID().hashCode()%90),
            1+Math.abs(UUID.randomUUID().hashCode()%90),
            1+Math.abs(UUID.randomUUID().hashCode()%900))+"0000000000000";
        var category=categories.saveAndFlush(Category.create(
            hcc,ProductType.PHYSICAL,"Smart Phone",null));
        var product=products.saveAndFlush(CanonicalProduct.create(category,"Phone "+UUID.randomUUID()));
        UUID seller=UUID.randomUUID();
        var listing=listings.saveAndFlush(SellerListing.draft(seller,product));
        var sku=skus.saveAndFlush(SellerSku.create(listing,"256GB",
            StockTrackingMode.TRACKED,MeasurementUnit.PCS));
        var brand=AttributeDefinition.create("BRAND",AttributeDefinition.Kind.ENUM,null);
        var storage=AttributeDefinition.create("STORAGE_GB",AttributeDefinition.Kind.DECIMAL,"GB");
        entities.persist(brand);
        entities.persist(storage);
        entities.flush();
        var approved=enumOptions.saveAndFlush(AttributeEnumOption.create(brand,"ACME"));
        var brandRule=rules.saveAndFlush(CategoryAttributeRule.define(category,brand,true,true));
        var storageRule=rules.saveAndFlush(CategoryAttributeRule.define(category,storage,true,true));
        values.saveAndFlush(SkuAttributeValue.of(sku,brandRule,"ACME"));
        values.saveAndFlush(SkuAttributeValue.of(sku,storageRule,"0256.00"));
        if(publish) publisher.publishForSeller(seller,listing.getId());
        return new Sample(seller,sku.getId(),product.getId(),hcc,approved.getId());
    }

    private ClassifiedOfferMatcher.RequiredItem demand(Sample s,String capacity) {
        return new ClassifiedOfferMatcher.RequiredItem(s.product(),s.hcc(),
            Map.of("BRAND","ACME","STORAGE_GB",capacity),Map.of());
    }

    private CanonicalFeatureAdapter.EligibilityEvidence verified() {
        // Verification and coverage MUST come from trusted server services,
        // not a browser, client DTO or ML model.
        return new CanonicalFeatureAdapter.EligibilityEvidence(true,true);
    }

    @Test void canonicalTypedDecimalAndEnumProduceReproducibleFeatures() {
        Sample s=sample(true);
        var projection=adapter.project(s.seller(),s.sku(),demand(s,"0256.00"),verified());
        assertThat(projection.match().eligible()).isTrue();
        assertThat(projection.request().hardAttributes()).containsEntry("STORAGE_GB","256");
        assertThat(projection.seller().attributes()).containsEntry("STORAGE_GB","256");
        assertThat(projection.seller().classCode()).isEqualTo(s.hcc());
        var a=projection.encodeEligible(true,true,new BigDecimal("45000.00"),
            new BigDecimal("50000.00"),0.75);
        var b=projection.encodeEligible(true,true,new BigDecimal("45000.00"),
            new BigDecimal("50000.00"),0.75);
        assertThat(a.dense()).containsExactly(b.dense());
        assertThat(a.dense()).hasSize(DealFeatureProjector.V1.orderedNames().size());
    }

    @Test void forgedHccAndUnknownHardAttributeAreFailClosed() {
        Sample s=sample(true);
        var wrong=new ClassifiedOfferMatcher.RequiredItem(s.product(),
            "99000000000000000000",Map.of("BRAND","ACME"),Map.of());
        assertThatThrownBy(()->adapter.project(s.seller(),s.sku(),wrong,verified()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("HCC");
        var invented=new ClassifiedOfferMatcher.RequiredItem(s.product(),s.hcc(),
            Map.of("CAN_SUPERCHARGE","true"),Map.of());
        assertThatThrownBy(()->adapter.project(s.seller(),s.sku(),invented,verified()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void hardAttributeMismatchExcludesSellerBeforeAnyMlScoring() {
        Sample s=sample(true);
        var projection=adapter.project(s.seller(),s.sku(),demand(s,"128"),verified());
        assertThat(projection.match().eligible()).isFalse();
        assertThat(projection.match().reasons())
            .contains("HARD_ATTRIBUTE_MISMATCH:STORAGE_GB");
        assertThatThrownBy(()->projection.encodeEligible(true,true,
            BigDecimal.ONE,BigDecimal.TEN,0.80)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void draftForeignSellerAndInvalidEnumOptionAreNeverRankable() {
        Sample draft=sample(false);
        assertThatThrownBy(()->adapter.project(draft.seller(),draft.sku(),
            demand(draft,"256"),verified())).isInstanceOf(IllegalStateException.class);

        Sample published=sample(true);
        assertThatThrownBy(()->adapter.project(UUID.randomUUID(),published.sku(),
            demand(published,"256"),verified())).isInstanceOf(SecurityException.class);

        jdbc.update("UPDATE catalog_attribute_enum_option SET active=false WHERE id=?",
                    published.enumOption());
        assertThatThrownBy(()->adapter.project(published.seller(),published.sku(),
            demand(published,"256"),verified())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void unverifiedSellerRemainsIneligibleEvenWithPerfectSkuAttributes() {
        Sample s=sample(true);
        var evidence=new CanonicalFeatureAdapter.EligibilityEvidence(false,true);
        var result=adapter.project(s.seller(),s.sku(),demand(s,"256"),evidence);
        assertThat(result.match().eligible()).isFalse();
        assertThat(result.match().reasons()).contains("SELLER_NOT_VERIFIED");
        assertThatThrownBy(()->result.encodeEligible(true,true,
            BigDecimal.ONE,BigDecimal.TEN,0.5)).isInstanceOf(IllegalArgumentException.class);
    }
}
