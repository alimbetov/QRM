package kz.zakaz.catalog;

import jakarta.persistence.*;
import java.util.*;
import kz.zakaz.domain.types.*;

/**
 * Seller's variant identity and immutable sale unit. NULL sales_unit is allowed
 * only for legacy R1/R2.0A drafts; it is never sufficient for R2.0B checkout.
 */
@Entity @Table(name="catalog_sku",uniqueConstraints=
    @UniqueConstraint(name="uk_sku_listing_code",columnNames={"listing_id","seller_sku_code"}))
public class SellerSku {
    @Id @Column(nullable=false) private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="listing_id",nullable=false) private SellerListing listing;
    @Column(name="seller_sku_code",nullable=false,length=80) private String sellerSkuCode;
    @Enumerated(EnumType.STRING) @Column(name="tracking_mode",nullable=false,length=16) private StockTrackingMode trackingMode;
    @Enumerated(EnumType.STRING) @Column(name="sales_unit",length=20,updatable=false) private MeasurementUnit salesUnit;
    protected SellerSku(){}
    /** Legacy R1 factory; untyped SKUs cannot be accepted into an order. */
    public static SellerSku create(SellerListing listing,String skuCode,StockTrackingMode mode){
        return create(listing,skuCode,mode,null);
    }
    public static SellerSku create(SellerListing listing,String skuCode,StockTrackingMode mode,
                                   MeasurementUnit salesUnit){
        Objects.requireNonNull(listing);Objects.requireNonNull(mode);
        if(listing.getStatus()!=ListingStatus.DRAFT)
            throw new IllegalStateException("Published listing SKU set is immutable");
        if(skuCode==null||skuCode.isBlank()||skuCode.length()>80)
            throw new IllegalArgumentException("Invalid seller SKU code");
        var s=new SellerSku();s.id=UUID.randomUUID();s.listing=listing;s.sellerSkuCode=skuCode;
        s.trackingMode=mode;s.salesUnit=salesUnit;return s;
    }
    public UUID getId(){return id;}
    public SellerListing getListing(){return listing;}
    public StockTrackingMode getTrackingMode(){return trackingMode;}
    public MeasurementUnit getSalesUnit(){return salesUnit;}
}
