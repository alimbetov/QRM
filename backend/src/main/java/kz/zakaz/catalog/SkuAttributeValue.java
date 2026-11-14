package kz.zakaz.catalog;

import jakarta.persistence.*;
import java.util.*;
import kz.zakaz.domain.types.ListingStatus;

/**
 * SKU-scoped fact pinned to the approved attribute schema revision at write time.
 * A published SKU cannot be mutated. Historic facts are not silently re-versioned.
 */
@Entity @Table(name="catalog_sku_attribute_value",uniqueConstraints=
    @UniqueConstraint(name="uk_sku_attribute",columnNames={"sku_id","attribute_id"}))
public class SkuAttributeValue {
    @Id @Column(nullable=false) private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="sku_id",nullable=false) private SellerSku sku;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="attribute_id",nullable=false) private AttributeDefinition attribute;
    @Column(name="canonical_value",nullable=false,length=256) private String canonicalValue;
    @Column(name="definition_revision",nullable=false) private long definitionRevision;
    protected SkuAttributeValue(){}

    public static SkuAttributeValue of(SellerSku sku,CategoryAttributeRule rule,String value){
        Objects.requireNonNull(sku);Objects.requireNonNull(rule);
        if(sku.getListing().getStatus()!=ListingStatus.DRAFT)
            throw new IllegalStateException("Published SKU facts are immutable");
        if(!sku.getListing().getProduct().getCategory().getId().equals(rule.getCategory().getId()))
            throw new IllegalArgumentException("Attribute not applicable to SKU category");
        if(!"SKU".equals(rule.getAttribute().getFactScope()))
            throw new IllegalArgumentException("Attribute must be SKU-scoped");
        var a=new SkuAttributeValue();
        a.id=UUID.randomUUID();a.sku=sku;a.attribute=rule.getAttribute();
        a.canonicalValue=a.attribute.canonicalize(value);
        a.definitionRevision=a.attribute.getSchemaRevision();
        return a;
    }
    void replaceCanonicalValue(String value) {
        if(sku.getListing().getStatus()!=ListingStatus.DRAFT)
            throw new IllegalStateException("Published SKU facts are immutable");
        canonicalValue=attribute.canonicalize(value);
        definitionRevision=attribute.getSchemaRevision();
    }
    public String getCanonicalValue(){return canonicalValue;}
    public long getDefinitionRevision(){return definitionRevision;}
    public UUID getId(){return id;}
    public SellerSku getSku(){return sku;}
    public AttributeDefinition getAttribute(){return attribute;}
}
