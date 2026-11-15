package kz.zakaz.catalog;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import kz.zakaz.domain.types.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@Import({CatalogDraftMutationService.class,CatalogPublishingService.class})
class CatalogR1B2DraftIntegrityTest {
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerSkuRepository skus;
    @Autowired CategoryAttributeRuleRepository rules;
    @Autowired SkuAttributeValueRepository values;
    @Autowired CatalogDraftMutationService mutation;
    @Autowired CatalogPublishingService publisher;
    @Autowired org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager em;

    @Test void ownerMayEditDraftButForeignSellerCannotChangeIt() {
        var cat=categories.saveAndFlush(Category.create("01020030000000000000",ProductType.PHYSICAL,"Phones",null));
        var product=products.saveAndFlush(CanonicalProduct.create(cat,"Phone"));
        UUID seller=UUID.randomUUID(),intruder=UUID.randomUUID();
        var listing=listings.saveAndFlush(SellerListing.draft(seller,product));
        assertThatThrownBy(()->mutation.createSku(intruder,listing.getId(),"A56",StockTrackingMode.TRACKED))
            .isInstanceOf(SecurityException.class);
        var sku=mutation.createSku(seller,listing.getId(),"A56",StockTrackingMode.TRACKED);
        assertThat(skus.findById(sku).orElseThrow().getListing().getSellerId()).isEqualTo(seller);
    }

    @Test void draftAttributeUpsertIsIdempotentAndPublicationFreezesSkuAndFacts() {
        var cat=categories.saveAndFlush(Category.create("01020030000000000000",ProductType.PHYSICAL,"Phones",null));
        var product=products.saveAndFlush(CanonicalProduct.create(cat,"Phone"));
        UUID seller=UUID.randomUUID(),intruder=UUID.randomUUID();
        var listing=listings.saveAndFlush(SellerListing.draft(seller,product));
        var attr=em.persistAndFlush(AttributeDefinition.create("STORAGE_GB",AttributeDefinition.Kind.DECIMAL,"GB"));
        rules.saveAndFlush(CategoryAttributeRule.define(cat,attr,true,true));
        var sku=mutation.createSku(seller,listing.getId(),"A56",StockTrackingMode.TRACKED);
        assertThatThrownBy(()->mutation.putAttribute(intruder,sku,attr.getId(),"256"))
            .isInstanceOf(SecurityException.class);
        mutation.putAttribute(seller,sku,attr.getId(),"0256.0");
        mutation.putAttribute(seller,sku,attr.getId(),"256.00");
        assertThat(values.findBySkuId(sku)).hasSize(1);
        assertThat(values.findBySkuId(sku).getFirst().getCanonicalValue()).isEqualTo("256");
        publisher.publishForSeller(seller,listing.getId());
        listings.flush();
        assertThatThrownBy(()->mutation.putAttribute(seller,sku,attr.getId(),"512"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->mutation.createSku(seller,listing.getId(),"A56-B",StockTrackingMode.TRACKED))
            .isInstanceOf(IllegalStateException.class);
        assertThat(values.findBySkuId(sku).getFirst().getCanonicalValue()).isEqualTo("256");
    }

    @Test void enumMustBeApprovedAndActiveBothOnDraftAndPublish() {
        var cat=categories.saveAndFlush(Category.create("01020030000000000000",ProductType.PHYSICAL,"Phones",null));
        var product=products.saveAndFlush(CanonicalProduct.create(cat,"Phone"));
        UUID seller=UUID.randomUUID();
        var listing=listings.saveAndFlush(SellerListing.draft(seller,product));
        var color=em.persistAndFlush(AttributeDefinition.create("COLOR",AttributeDefinition.Kind.ENUM,null));
        rules.saveAndFlush(CategoryAttributeRule.define(cat,color,true,true));
        em.persistAndFlush(AttributeEnumOption.create(color,"BLACK"));
        var sku=mutation.createSku(seller,listing.getId(),"BLACK-01",StockTrackingMode.TRACKED);
        assertThatThrownBy(()->mutation.putAttribute(seller,sku,color.getId(),"PURPLE"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown enum option");
        mutation.putAttribute(seller,sku,color.getId(),"BLACK");
        publisher.publishForSeller(seller,listing.getId());
        assertThat(values.findBySkuId(sku)).extracting(SkuAttributeValue::getCanonicalValue)
            .containsExactly("BLACK");
    }

    @Test void completeListingCannotBePublishedWithUnapprovedEnumFromDirectJpaWrite() {
        var cat=categories.saveAndFlush(Category.create("01020030000000000000",ProductType.PHYSICAL,"Phones",null));
        var product=products.saveAndFlush(CanonicalProduct.create(cat,"Phone"));
        UUID seller=UUID.randomUUID();
        var listing=listings.saveAndFlush(SellerListing.draft(seller,product));
        var sku=skus.saveAndFlush(SellerSku.create(listing,"BLACK-01",StockTrackingMode.TRACKED));
        var color=em.persistAndFlush(AttributeDefinition.create("COLOR",AttributeDefinition.Kind.ENUM,null));
        var rule=rules.saveAndFlush(CategoryAttributeRule.define(cat,color,true,true));
        values.saveAndFlush(SkuAttributeValue.of(sku,rule,"UNAPPROVED"));
        assertThatThrownBy(()->publisher.publishForSeller(seller,listing.getId()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown enum option");
    }
}
