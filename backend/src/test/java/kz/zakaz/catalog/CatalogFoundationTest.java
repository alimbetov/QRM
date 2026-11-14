package kz.zakaz.catalog;

import java.util.*;
import org.junit.jupiter.api.Test;
import kz.zakaz.domain.types.*;
import static org.assertj.core.api.Assertions.*;

class CatalogFoundationTest {
    private Category phones(){return Category.create("01020030000000000000",ProductType.PHYSICAL,"Phones",null);}
    @Test void hccIsOpaqueFixedWidthAndParentMustBeCompatible(){
        var p=phones();
        assertThat(p.getHccCode()).hasSize(20);
        var child=Category.create("01020030000100000000",ProductType.PHYSICAL,"Smartphones",p);
        assertThat(child.getHccCode()).startsWith("0102003");
        assertThatThrownBy(()->Category.create("12",ProductType.PHYSICAL,"Broken",null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->Category.create("01020030000100000000",ProductType.SERVICE,"Wrong",p)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void canonicalProductAndSellerScopedSkuRemainSeparate(){
        var product=CanonicalProduct.create(phones(),"Galaxy A56");
        var sellerId=UUID.randomUUID();
        var listing=SellerListing.draft(sellerId,product);
        var sku=SellerSku.create(listing,"GALAXY-A56-256",StockTrackingMode.TRACKED);
        assertThat(sku.getListing().getSellerId()).isEqualTo(sellerId);
        assertThat(listing.getStatus()).isEqualTo(ListingStatus.DRAFT);
        assertThat(product.getCategory().getHccCode()).hasSize(20);
    }
    @Test void attributeValuesAreNormalizedAndClassRulesEnforced(){
        var p=phones();
        var a=AttributeDefinition.create("STORAGE_GB",AttributeDefinition.Kind.DECIMAL,"GB");
        var rule=CategoryAttributeRule.define(p,a,true,true);
        var product=CanonicalProduct.create(p,"Galaxy A56");
        var sku=SellerSku.create(SellerListing.draft(UUID.randomUUID(),product),"A56-256",StockTrackingMode.TRACKED);
        assertThat(SkuAttributeValue.of(sku,rule,"0256.00").getCanonicalValue()).isEqualTo("256");
        assertThatThrownBy(()->SkuAttributeValue.of(sku,rule,"-1")).isInstanceOf(IllegalArgumentException.class);
        var wrong=CategoryAttributeRule.define(Category.create("03000000000000000000",ProductType.PHYSICAL,"Clothing",null),a,true,true);
        assertThatThrownBy(()->SkuAttributeValue.of(sku,wrong,"256")).isInstanceOf(IllegalArgumentException.class);
    }
}
