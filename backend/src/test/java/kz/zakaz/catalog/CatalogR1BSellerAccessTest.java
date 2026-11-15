package kz.zakaz.catalog;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import kz.zakaz.domain.types.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest @ActiveProfiles("test") @Import(CatalogPublishingService.class)
class CatalogR1BSellerAccessTest {
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerSkuRepository skus;
    @Autowired CategoryAttributeRuleRepository rules;
    @Autowired SkuAttributeValueRepository attributes;
    @Autowired CatalogPublishingService publisher;
    @Autowired org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager entityManager;

    @Test void sellerCannotPublishOtherSellerListingAndIncompleteSkuCannotBePublished() {
        var cat=categories.saveAndFlush(Category.create("01020030000000000000",ProductType.PHYSICAL,"Phone",null));
        var p=products.saveAndFlush(CanonicalProduct.create(cat,"Galaxy A56"));
        UUID seller=UUID.randomUUID(),attacker=UUID.randomUUID();
        var listing=listings.saveAndFlush(SellerListing.draft(seller,p));
        var sku=skus.saveAndFlush(SellerSku.create(listing,"A56",StockTrackingMode.TRACKED));
        var attr=entityManager.persistAndFlush(AttributeDefinition.create("STORAGE_GB",AttributeDefinition.Kind.DECIMAL,"GB"));
        var rule=rules.saveAndFlush(CategoryAttributeRule.define(cat,attr,true,true));
        assertThatThrownBy(()->publisher.publishForSeller(attacker,listing.getId()))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->publisher.publishForSeller(seller,listing.getId()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("STORAGE_GB");
        assertThat(listings.findById(listing.getId()).orElseThrow().getStatus()).isEqualTo(ListingStatus.DRAFT);
        attributes.saveAndFlush(SkuAttributeValue.of(sku,rule,"0256.00"));
        publisher.publishForSeller(seller,listing.getId());
        listings.flush();
        assertThat(listings.findById(listing.getId()).orElseThrow().getStatus()).isEqualTo(ListingStatus.PUBLIC);
        assertThatThrownBy(()->publisher.publishForSeller(attacker,listing.getId()))
            .isInstanceOf(SecurityException.class);
    }
    @Test void noSkuCannotPublish() {
        var cat=categories.saveAndFlush(Category.create("01020030000000000000",ProductType.PHYSICAL,"Phone",null));
        var p=products.saveAndFlush(CanonicalProduct.create(cat,"A"));
        var listing=listings.saveAndFlush(SellerListing.draft(UUID.randomUUID(),p));
        assertThatThrownBy(()->publisher.publishForSeller(listing.getSellerId(),listing.getId()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No SKUs");
    }
}
