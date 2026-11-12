package kz.zakaz.catalog;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import kz.zakaz.domain.types.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest @ActiveProfiles("test")
class CatalogR1BSellerAccessTest {
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerSkuRepository skus;
    @Autowired CategoryAttributeRuleRepository rules;
    @Autowired SkuAttributeValueRepository values;

    @Test void sellerCannotPublishOtherSellerListingAndIncompleteSkuCannotBePublished() {
        var cat=categories.saveAndFlush(Category.create("01020030000000000000",ProductType.PHYSICAL,"Phone",null));
        var p=products.saveAndFlush(CanonicalProduct.create(cat,"Galaxy A56"));
        UUID seller=UUID.randomUUID(),attacker=UUID.randomUUID();
        var listing=listings.saveAndFlush(SellerListing.draft(seller,p));
        var sku=skus.saveAndFlush(SellerSku.create(listing,"A56",StockTrackingMode.TRACKED));
        var attr=AttributeDefinition.create("STORAGE_GB",AttributeDefinition.Kind.DECIMAL,"GB");
        // publish cannot proceed until the required attribute rule and value are persisted.
        assertThatThrownBy(()->CatalogPublishingService.publishForSeller(
            attacker,listing.getId(),listings,skus,rules,values))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->CatalogPublishingService.publishForSeller(
            seller,listing.getId(),listings,skus,rules,values))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(listings.findById(listing.getId()).orElseThrow().getStatus())
            .isEqualTo(ListingStatus.DRAFT);
    }
}
