package kz.zakaz.catalog;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.dao.DataIntegrityViolationException;
import kz.zakaz.domain.types.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest @ActiveProfiles("test")
class CatalogJpaH2Test {
    @Autowired CategoryRepository categories;
    @Autowired CanonicalProductRepository products;
    @Autowired SellerListingRepository listings;
    @Autowired SellerSkuRepository skus;
    @Autowired org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager em;
    @Test void sameCanonicalProductTwoMerchantsDifferentSkuOwnership(){
        var category=categories.saveAndFlush(Category.create("01020030000000000000",ProductType.PHYSICAL,"Phones",null));
        var product=products.saveAndFlush(CanonicalProduct.create(category,"Galaxy A56"));
        var sellerA=UUID.randomUUID();var sellerB=UUID.randomUUID();
        var a=listings.saveAndFlush(SellerListing.draft(sellerA,product));
        var b=listings.saveAndFlush(SellerListing.draft(sellerB,product));
        skus.saveAndFlush(SellerSku.create(a,"A56-256",StockTrackingMode.TRACKED));
        skus.saveAndFlush(SellerSku.create(b,"A56-256",StockTrackingMode.TRACKED));
        assertThat(listings.findBySellerId(sellerA)).extracting(SellerListing::getId).containsExactly(a.getId());
        assertThat(listings.findBySellerId(sellerB)).extracting(SellerListing::getId).containsExactly(b.getId());
    }
    @Test void globallyUniqueHccCodeIsEnforcedByDatabase(){
        categories.saveAndFlush(Category.create("01020030000000000000",ProductType.PHYSICAL,"Phones",null));
        assertThatThrownBy(()->categories.saveAndFlush(
            Category.create("01020030000000000000",ProductType.PHYSICAL,"Duplicate",null)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
