package kz.zakaz.catalog;

import java.math.BigDecimal;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import kz.zakaz.domain.types.ListingStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explicit seller-controlled draft price. This is a public catalog reference
 * price, not an accepted all-in bid or an immutable order price snapshot.
 * Publication and price changes serialize through the listing row lock.
 */
@Service
public class CatalogPublicPriceService {
    private final SellerListingRepository listings;
    private final SellerSkuRepository skus;

    public CatalogPublicPriceService(SellerListingRepository listings, SellerSkuRepository skus) {
        this.listings = listings;
        this.skus = skus;
    }

    @Transactional
    public void setDraftPrice(UUID actorSellerId, UUID listingId, UUID skuId, BigDecimal priceKzt) {
        Objects.requireNonNull(actorSellerId);
        Objects.requireNonNull(listingId);
        Objects.requireNonNull(skuId);
        var listing = listings.findForUpdate(listingId)
            .orElseThrow(() -> new NoSuchElementException("Listing not found"));
        if (!actorSellerId.equals(listing.getSellerId()))
            throw new SecurityException("Listing belongs to another seller");
        if (listing.getStatus() != ListingStatus.DRAFT)
            throw new IllegalStateException("Only draft prices can be edited");
        var sku = skus.findById(skuId)
            .orElseThrow(() -> new NoSuchElementException("SKU not found"));
        if (!listingId.equals(sku.getListing().getId()))
            throw new NoSuchElementException("SKU not found in listing");
        sku.setDraftPublicPriceKzt(priceKzt);
        skus.saveAndFlush(sku);
    }
}
