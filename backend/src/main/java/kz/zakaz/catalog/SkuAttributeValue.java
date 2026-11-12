package kz.zakaz.catalog;

import jakarta.persistence.*;
import java.util.*;
import kz.zakaz.domain.types.ListingStatus;

/** Typed canonical SKU attribute, bound by category rule. */
@Entity @Table(name="catalog_sku_attribute_value",uniqueConstraints=
    @UniqueConstraint(name="uk_sku_attribute",columnNames={"sku_id","attribute_id"}))
public class SkuAttributeValue {
    @Id @Column(nullable=false) private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="sku_id",nullable=false) private SellerSku sku;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="attribute_id",nullable=false) private AttributeDefinition attribute;
    @Column(name="canonical_value",nullable=false,length=256) private String canonicalValue;
    protected SkuAttributeValue(){}
    public static SkuAttributeValue of(SellerSku sku,CategoryAttributeRule rule,String value){
        Objects.requireNonNull(sku);Objects.requireNonNull(rule);
        if(sku.getListing().getStatus()!=ListingStatus.DRAFT)
            throw new IllegalStateException("Published SKU facts are immutable");
        if(!sku.getListing().getProduct().getCategory().getId().equals(rule.getCategory().getId()))
            throw new IllegalArgumentException("Attribute not applicable to SKU category");
        var a=new SkuAttributeValue();a.id=UUID.randomUUID();a.sku=sku;a.attribute=rule.getAttribute();
        a.canonicalValue=a.attribute.canonicalize(value);return a;
    }
    void replaceCanonicalValue(String value) {
        if(sku.getListing().getStatus()!=ListingStatus.DRAFT)
            throw new IllegalStateException("Published SKU facts are immutable");
        canonicalValue=attribute.canonicalize(value);
    }
    public String getCanonicalValue(){return canonicalValue;}
    public UUID getId(){return id;}
    public SellerSku getSku(){return sku;}
    public AttributeDefinition getAttribute(){return attribute;}
}
