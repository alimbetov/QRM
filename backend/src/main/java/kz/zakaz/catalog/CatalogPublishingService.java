package kz.zakaz.catalog;

import java.util.*;
import kz.zakaz.publicqr.PublicListingLink;
import kz.zakaz.publicqr.PublicListingLinkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-side publishing requires a trusted seller ID from authenticated principal.
 * No HTTP endpoints are enabled until identity/merchant membership integration exists.
 * Publishing and draft edits serialize on one listing pessimistic lock.
 */
@Service
public class CatalogPublishingService {
    private final SellerListingRepository listings;
    private final PublicListingLinkRepository publicLinks;
    private final SellerSkuRepository skus;
    private final CategoryAttributeRuleRepository rules;
    private final SkuAttributeValueRepository attributes;
    private final AttributeEnumOptionRepository enumOptions;
    public CatalogPublishingService(SellerListingRepository listings,SellerSkuRepository skus,
                                    CategoryAttributeRuleRepository rules,
                                    SkuAttributeValueRepository attributes,
                                    AttributeEnumOptionRepository enumOptions,
                                    PublicListingLinkRepository publicLinks) {
        this.listings=listings;this.skus=skus;this.rules=rules;
        this.attributes=attributes;this.enumOptions=enumOptions;
        this.publicLinks=publicLinks;
    }
    @Transactional
    public void publishForSeller(UUID actorSellerId,UUID listingId) {
        Objects.requireNonNull(actorSellerId);Objects.requireNonNull(listingId);
        var listing=listings.findForUpdate(listingId)
            .orElseThrow(()->new NoSuchElementException("Listing not found"));
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
            CatalogEnumPolicy.verifyFacts(facts,enumOptions);
        }
        listing.publish();
        // Atomic with publication: never emit a QR identity for an unpublished draft.
        publicLinks.saveAndFlush(PublicListingLink.issue(listing));
    }
}
