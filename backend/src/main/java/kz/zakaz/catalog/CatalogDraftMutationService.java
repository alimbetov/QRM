package kz.zakaz.catalog;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import kz.zakaz.domain.types.StockTrackingMode;
import kz.zakaz.domain.types.ListingStatus;

/**
 * Server-side draft mutations. actorSellerId must originate from a trusted
 * authentication/merchant-ownership adapter, not an HTTP DTO. No HTTP routes
 * exist until that adapter is implemented. Publication and draft mutation
 * acquire the same listing-level pessimistic DB lock.
 */
@Service
public class CatalogDraftMutationService {
    private final SellerListingRepository listings;
    private final SellerSkuRepository skus;
    private final CategoryAttributeRuleRepository rules;
    private final SkuAttributeValueRepository attributes;
    private final AttributeEnumOptionRepository enumOptions;

    public CatalogDraftMutationService(SellerListingRepository listings,SellerSkuRepository skus,
                                       CategoryAttributeRuleRepository rules,
                                       SkuAttributeValueRepository attributes,
                                       AttributeEnumOptionRepository enumOptions) {
        this.listings=listings;this.skus=skus;this.rules=rules;
        this.attributes=attributes;this.enumOptions=enumOptions;
    }
    @Transactional
    public UUID createSku(UUID actorSellerId,UUID listingId,String skuCode,StockTrackingMode mode) {
        var listing=lockedOwnedDraft(actorSellerId,listingId);
        var sku=skus.saveAndFlush(SellerSku.create(listing,skuCode,mode));
        return sku.getId();
    }
    @Transactional
    public UUID putAttribute(UUID actorSellerId,UUID skuId,UUID attributeId,String rawValue) {
        Objects.requireNonNull(skuId);Objects.requireNonNull(attributeId);
        var sku=skus.findById(skuId).orElseThrow(()->new NoSuchElementException("SKU not found"));
        var listing=lockedOwnedDraft(actorSellerId,sku.getListing().getId());
        if(!sku.getListing().getId().equals(listing.getId()))
            throw new SecurityException("SKU does not belong to listing");
        var category=listing.getProduct().getCategory();
        var rule=rules.findByCategoryId(category.getId()).stream()
            .filter(r->r.getAttribute().getId().equals(attributeId))
            .findFirst().orElseThrow(()->new IllegalArgumentException("Attribute not applicable to SKU"));
        String canonical=rule.getAttribute().canonicalize(rawValue);
        CatalogEnumPolicy.validate(rule.getAttribute(),canonical,enumOptions);
        var current=attributes.findBySkuIdAndAttributeId(skuId,attributeId);
        if(current.isPresent()) {
            current.get().replaceCanonicalValue(canonical);
            attributes.flush();
            return current.get().getId();
        }
        return attributes.saveAndFlush(SkuAttributeValue.of(sku,rule,canonical)).getId();
    }
    private SellerListing lockedOwnedDraft(UUID actorSellerId,UUID listingId) {
        Objects.requireNonNull(actorSellerId);Objects.requireNonNull(listingId);
        var listing=listings.findForUpdate(listingId)
            .orElseThrow(()->new NoSuchElementException("Listing not found"));
        if(!actorSellerId.equals(listing.getSellerId()))
            throw new SecurityException("Seller does not own listing");
        if(listing.getStatus()!=ListingStatus.DRAFT)
            throw new IllegalStateException("Only DRAFT listings may be edited");
        return listing;
    }
}
