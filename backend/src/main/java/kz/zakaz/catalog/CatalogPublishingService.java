package kz.zakaz.catalog;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain authorization boundary: actorSellerId MUST come from authenticated server
 * principal in future HTTP adapter, never untrusted sellerId request field.
 * Seller verification, stock and geography are separate gates before order/offer.
 */
@Service
public class CatalogPublishingService {
    private final SellerListingRepository listings;
    private final SellerSkuRepository skus;
    private final CategoryAttributeRuleRepository rules;
    private final SkuAttributeValueRepository attributes;
    public CatalogPublishingService(SellerListingRepository listings,SellerSkuRepository skus,
                                    CategoryAttributeRuleRepository rules,SkuAttributeValueRepository attributes) {
        this.listings=listings;this.skus=skus;this.rules=rules;this.attributes=attributes;
    }
    @Transactional
    public void publishForSeller(UUID actorSellerId,UUID listingId) {
        Objects.requireNonNull(actorSellerId);Objects.requireNonNull(listingId);
        var listing=listings.findById(listingId).orElseThrow(()->new NoSuchElementException("Listing not found"));
        if(!actorSellerId.equals(listing.getSellerId()))
            throw new SecurityException("Seller does not own listing");
        if(!listing.getProduct().getCategory().isActive())
            throw new IllegalArgumentException("Category inactive");
        var variants=skus.findByListingId(listingId);
        if(variants.isEmpty()) throw new IllegalArgumentException("No SKUs to publish");
        var category=listing.getProduct().getCategory();
        var applicable=rules.findByCategoryId(category.getId());
        for(var sku:variants) {
            var facts=attributes.findBySkuId(sku.getId());
            CatalogPublicationPolicy.verify(category,applicable,facts);
        }
        listing.publish(); // managed entity dirty checking, same transaction
    }
}
