package kz.zakaz.catalog;

import jakarta.persistence.*;
import java.util.*;

/** Authoritative applicability contract. Hard/soft is request-specific, not a global ML decision. */
@Entity
@Table(name="catalog_category_attribute_rule",uniqueConstraints=@UniqueConstraint(name="uk_category_attribute",columnNames={"category_id","attribute_id"}))
public class CategoryAttributeRule {
    @Id @Column(nullable=false) private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="category_id",nullable=false) private Category category;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="attribute_id",nullable=false) private AttributeDefinition attribute;
    @Column(nullable=false) private boolean required;
    @Column(nullable=false) private boolean filterable;
    protected CategoryAttributeRule(){}
    public static CategoryAttributeRule define(Category category,AttributeDefinition attribute,boolean required,boolean filterable){
        Objects.requireNonNull(category);Objects.requireNonNull(attribute);
        var r=new CategoryAttributeRule();r.id=UUID.randomUUID();r.category=category;r.attribute=attribute;r.required=required;r.filterable=filterable;return r;
    }
    public Category getCategory(){return category;}
    public AttributeDefinition getAttribute(){return attribute;}
    public boolean isRequired(){return required;}
}
