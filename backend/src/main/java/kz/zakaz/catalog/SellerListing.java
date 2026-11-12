package kz.zakaz.catalog;

import jakarta.persistence.*;
import java.util.*;
import kz.zakaz.domain.types.ListingStatus;

/** Seller-owned publication; status never proves SKU stock availability. */
@Entity @Table(name="catalog_listing")
public class SellerListing {
    @Id @Column(nullable=false) private UUID id;
    @Column(name="seller_id",nullable=false,updatable=false) private UUID sellerId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="product_id",nullable=false) private CanonicalProduct product;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private ListingStatus status;
    @Version private Long version;
    protected SellerListing(){}
    public static SellerListing draft(UUID sellerId,CanonicalProduct product){
        Objects.requireNonNull(sellerId);Objects.requireNonNull(product);
        var l=new SellerListing();l.id=UUID.randomUUID();l.sellerId=sellerId;l.product=product;l.status=ListingStatus.DRAFT;return l;
    }
    public UUID getId(){return id;}
    public UUID getSellerId(){return sellerId;}
    public CanonicalProduct getProduct(){return product;}
    public ListingStatus getStatus(){return status;}
}
