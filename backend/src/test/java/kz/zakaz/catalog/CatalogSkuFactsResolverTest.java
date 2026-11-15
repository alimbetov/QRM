package kz.zakaz.catalog;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import kz.zakaz.domain.types.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest @ActiveProfiles("test")
@Import({CatalogPublishingService.class,CatalogSkuFactsResolver.class})
class CatalogSkuFactsResolverTest {
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerSkuRepository skus;
    @Autowired CatalogPublishingService publisher;
    @Autowired CatalogSkuFactsResolver facts;
    @Autowired CategoryAttributeRuleRepository rules;
    @Autowired SkuAttributeValueRepository values;
    @Autowired org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager em;

    @Test void resolvesOnlyOwnedPublishedSkuAndReturnsCanonicalFacts() {
        var cat=categories.saveAndFlush(Category.create("01020030000000000000",ProductType.PHYSICAL,"Phone",null));
        var prod=products.saveAndFlush(CanonicalProduct.create(cat,"Galaxy A56"));
        UUID owner=UUID.randomUUID(),intruder=UUID.randomUUID();
        var listing=listings.saveAndFlush(SellerListing.draft(owner,prod));
        var sku=skus.saveAndFlush(SellerSku.create(listing,"A56-256",StockTrackingMode.TRACKED));
        var attr=em.persistAndFlush(AttributeDefinition.create("STORAGE_GB",AttributeDefinition.Kind.DECIMAL,"GB"));
        var rule=rules.saveAndFlush(CategoryAttributeRule.define(cat,attr,true,true));
        values.saveAndFlush(SkuAttributeValue.of(sku,rule,"0256.00"));
        assertThatThrownBy(()->facts.resolveForSeller(owner,sku.getId()))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->facts.resolveForSeller(intruder,sku.getId()))
            .isInstanceOf(SecurityException.class);
        publisher.publishForSeller(owner,listing.getId());
        var result=facts.resolveForSeller(owner,sku.getId());
        assertThat(result.productId()).isEqualTo(prod.getId());
        assertThat(result.hccCode()).isEqualTo("01020030000000000000");
        assertThat(result.sellerId()).isEqualTo(owner);
        assertThat(result.attributes()).containsEntry("STORAGE_GB","256");
        assertThatThrownBy(()->facts.resolveForSeller(intruder,sku.getId()))
            .isInstanceOf(SecurityException.class);
    }
}
