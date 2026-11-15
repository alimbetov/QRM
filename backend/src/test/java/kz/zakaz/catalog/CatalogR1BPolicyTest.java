package kz.zakaz.catalog;

import java.util.*;
import org.junit.jupiter.api.Test;
import kz.zakaz.domain.types.*;
import static org.assertj.core.api.Assertions.*;

class CatalogR1BPolicyTest {
    @Test void hccRequiresContiguousSegmentsAtBoundaries() {
        var family=Category.create("01020000000000000000",ProductType.PHYSICAL,"Electronics",null);
        var kind=Category.create("01020030000000000000",ProductType.PHYSICAL,"Phones",family);
        assertThat(kind.getHccCode()).hasSize(20);
        assertThatThrownBy(()->Category.create("01000030000000000000",ProductType.PHYSICAL,"Gap",null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->Category.create("01020040000000000000",ProductType.PHYSICAL,"Sibling",kind))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->Category.create("01020030000000000000",ProductType.PHYSICAL,"Same",kind))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->Category.create("01020030000100000000",ProductType.SERVICE,"Other",kind))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void publishedSkuMustContainEveryRequiredApplicableAttribute() {
        var cat=Category.create("01020030000000000000",ProductType.PHYSICAL,"Phone",null);
        var product=CanonicalProduct.create(cat,"Galaxy A56");
        var listing=SellerListing.draft(UUID.randomUUID(),product);
        var sku=SellerSku.create(listing,"A56",StockTrackingMode.TRACKED);
        var brand=AttributeDefinition.create("BRAND",AttributeDefinition.Kind.ENUM,null);
        var storage=AttributeDefinition.create("STORAGE_GB",AttributeDefinition.Kind.DECIMAL,"GB");
        var brandRule=CategoryAttributeRule.define(cat,brand,true,true);
        var storageRule=CategoryAttributeRule.define(cat,storage,true,true);
        var supplied=List.of(SkuAttributeValue.of(sku,brandRule,"SAMSUNG"));
        assertThatThrownBy(()->CatalogPublicationPolicy.verify(cat,
            List.of(brandRule,storageRule),supplied)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("STORAGE_GB");
        var complete=new ArrayList<>(supplied);
        complete.add(SkuAttributeValue.of(sku,storageRule,"0256.00"));
        assertThatCode(()->CatalogPublicationPolicy.verify(cat,
            List.of(brandRule,storageRule),complete)).doesNotThrowAnyException();
        assertThatThrownBy(()->CatalogPublicationPolicy.verify(cat,
            List.of(brandRule,storageRule),List.of(complete.get(0),complete.get(0))))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
