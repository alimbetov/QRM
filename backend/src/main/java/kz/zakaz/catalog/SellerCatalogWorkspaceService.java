package kz.zakaz.catalog;

import java.math.BigDecimal;
import java.util.*;
import kz.zakaz.domain.types.*;
import kz.zakaz.publicqr.PublicListingLinkRepository;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * React Workspace R1: seller-owned draft/listing read model.
 * Reuses canonical products and existing SKU/public-price/publishing services.
 * Seller identity is verified by the HTTP boundary, never supplied in the JSON.
 */
@Service
public class SellerCatalogWorkspaceService {
    public record Draft(UUID productId,String skuCode,MeasurementUnit unit,
                        StockTrackingMode trackingMode,BigDecimal publicPriceKzt) {}
    public record SkuView(UUID skuId,String code,String unit,String trackingMode,
                          String priceKzt) {}
    public record ListingView(UUID listingId,UUID productId,String title,
                              String categoryCode,String status,String qrToken,
                              List<SkuView> variants) {}
    public record PageView(int page,int size,long total,List<ListingView> items) {}

    private final SellerListingRepository listings;
    private final SellerSkuRepository skus;
    private final CanonicalProductRepository products;
    private final PublicListingLinkRepository links;

    public SellerCatalogWorkspaceService(SellerListingRepository listings,
        SellerSkuRepository skus,CanonicalProductRepository products,
        PublicListingLinkRepository links) {
        this.listings=listings;this.skus=skus;this.products=products;this.links=links;
    }

    @Transactional
    public ListingView createDraft(UUID verifiedSeller,Draft input) {
        Objects.requireNonNull(verifiedSeller);
        Objects.requireNonNull(input);
        if(input.productId()==null || input.unit()==null || input.trackingMode()==null)
            throw new IllegalArgumentException("Product, unit and stock tracking are required");
        // Initial R1 covers physical catalog offers. Services/digital SKUs need
        // type-specific fulfillment and inventory acceptance in a separate slice.
        var product=products.findById(input.productId())
            .orElseThrow(()->new NoSuchElementException("Canonical product not found"));
        if(!product.getCategory().isActive() ||
           product.getCategory().getType()!=ProductType.PHYSICAL ||
           input.unit()==MeasurementUnit.SERVICE || input.unit()==MeasurementUnit.LICENSE ||
           input.trackingMode()!=StockTrackingMode.TRACKED)
            throw new IllegalArgumentException("R1 supports tracked physical SKU drafts only");

        var listing=listings.saveAndFlush(SellerListing.draft(verifiedSeller,product));
        var sku=SellerSku.create(listing,input.skuCode(),input.trackingMode(),input.unit());
        if(input.publicPriceKzt()!=null)
            sku.setDraftPublicPriceKzt(input.publicPriceKzt());
        skus.saveAndFlush(sku);
        return view(listing);
    }

    @Transactional(readOnly=true)
    public PageView list(UUID verifiedSeller,int page,int size) {
        Objects.requireNonNull(verifiedSeller);
        if(page<0||page>10000||size<1||size>25)
            throw new IllegalArgumentException("Invalid listing page");
        var result=listings.findBySellerId(verifiedSeller,
            PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"id")));
        return new PageView(page,size,result.getTotalElements(),
            result.getContent().stream().map(this::view).toList());
    }

    @Transactional(readOnly=true)
    public ListingView read(UUID verifiedSeller,UUID listingId) {
        Objects.requireNonNull(verifiedSeller);
        Objects.requireNonNull(listingId);
        var listing=listings.findById(listingId)
            .orElseThrow(()->new NoSuchElementException("Listing not found"));
        // Do not reveal whether foreign listing exists.
        if(!verifiedSeller.equals(listing.getSellerId()))
            throw new NoSuchElementException("Listing not found");
        return view(listing);
    }

    private ListingView view(SellerListing listing) {
        var product=listing.getProduct();
        var variants=skus.findByListingId(listing.getId()).stream()
            .map(s->new SkuView(s.getId(),s.getSellerSkuCode(),
                s.getSalesUnit()==null?null:s.getSalesUnit().name(),
                s.getTrackingMode().name(),
                s.getPublicUnitPriceKzt()==null?null:
                    s.getPublicUnitPriceKzt().toPlainString()))
            .toList();
        String qr=listing.getStatus()==ListingStatus.PUBLIC ?
            links.findByListing_Id(listing.getId()).map(link->link.getToken())
                .orElse(null) : null;
        return new ListingView(listing.getId(),product.getId(),
            product.getCanonicalName(),product.getCategory().getHccCode(),
            listing.getStatus().name(),qr,variants);
    }
}
