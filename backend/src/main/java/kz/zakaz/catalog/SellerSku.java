package kz.zakaz.catalog;

import jakarta.persistence.*;
import java.util.*;
import kz.zakaz.domain.types.*;

/** Seller's variant identity. Tracking mode is not an inventory balance. */
@Entity @Table(name="catalog_sku",uniqueConstraints=
    @UniqueConstraint(name="uk_sku_listing_code",columnNames={"listing_id","seller_sku_code"}))
public class SellerSku {
    @Id @Column(nullable=false) private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="listing_id",nullable=false) private SellerListing listing;
    @Column(name="seller_sku_code",nullable=false,length=80) private String sellerSkuCode;
    @Enumerated(EnumType.STRING) @Column(name="tracking_mode",nullable=false,length=16) private StockTrackingMode trackingMode;
    protected SellerSku(){}
    public static SellerSku create(SellerListing listing,String skuCode,StockTrackingMode mode){
        Objects.requireNonNull(listing);Objects.requireNonNull(mode);
        if(listing.getStatus()!=ListingStatus.DRAFT)
            throw new IllegalStateException("Published listing SKU set is immutable");
        if(skuCode==null||skuCode.isBlank()||skuCode.length()>80)
            throw new IllegalArgumentException("Invalid seller SKU code");
        var s=new SellerSku();s.id=UUID.randomUUID();s.listing=listing;s.sellerSkuCode=skuCode;s.trackingMode=mode;return s;
    }
    public UUID getId(){return id;}
    public SellerListing getListing(){return listing;}
}
