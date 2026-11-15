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
class CatalogR1B2PublishedImmutabilityTest {
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerSkuRepository skus;
    @Autowired CategoryAttributeRuleRepository rules;
    @Autowired SkuAttributeValueRepository values;
    @Autowired CatalogPublishingService publisher;
    @Autowired CatalogSkuFactsResolver resolver;
    @Autowired org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager em;

    @Test void factoryBlocksPublishedChangesAndResolverRejectsRevokedEnum() {
        var cat=categories.saveAndFlush(Category.create(
            "01020030000000000000",ProductType.PHYSICAL,"Phones",null));
        var product=products.saveAndFlush(CanonicalProduct.create(cat,"Phone"));
        var listing=listings.saveAndFlush(SellerListing.draft(UUID.randomUUID(),product));
        var sku=skus.saveAndFlush(SellerSku.create(listing,"BLACK",StockTrackingMode.TRACKED));
        var color=em.persistAndFlush(AttributeDefinition.create("COLOR",AttributeDefinition.Kind.ENUM,null));
        var rule=rules.saveAndFlush(CategoryAttributeRule.define(cat,color,true,true));
        var option=em.persistAndFlush(AttributeEnumOption.create(color,"BLACK"));
        values.saveAndFlush(SkuAttributeValue.of(sku,rule,"BLACK"));
        publisher.publishForSeller(listing.getSellerId(),listing.getId());
        assertThatThrownBy(()->SellerSku.create(listing,"NEW",StockTrackingMode.TRACKED))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->SkuAttributeValue.of(sku,rule,"BLACK"))
            .isInstanceOf(IllegalStateException.class);
        assertThat(resolver.resolveForSeller(listing.getSellerId(),sku.getId()).attributes())
            .containsEntry("COLOR","BLACK");
        em.getEntityManager().createNativeQuery(
            "UPDATE catalog_attribute_enum_option SET active = false WHERE id = :id")
            .setParameter("id",option.getId()).executeUpdate();
        assertThatThrownBy(()->resolver.resolveForSeller(listing.getSellerId(),sku.getId()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown enum option");
    }
}
