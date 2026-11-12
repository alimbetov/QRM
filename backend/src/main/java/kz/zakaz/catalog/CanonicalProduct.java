package kz.zakaz.catalog;

import jakarta.persistence.*;
import java.util.*;

/** Canonical identity is not seller-specific price, stock, or listing publication. */
@Entity @Table(name="catalog_product")
public class CanonicalProduct {
    @Id @Column(nullable=false) private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="category_id",nullable=false) private Category category;
    @Column(nullable=false,length=200) private String canonicalName;
    protected CanonicalProduct(){}
    public static CanonicalProduct create(Category category,String name){
        Objects.requireNonNull(category);if(!category.isActive())throw new IllegalArgumentException("Inactive category");
        if(name==null||name.isBlank()||name.length()>200)throw new IllegalArgumentException("Invalid product");
        var p=new CanonicalProduct();p.id=UUID.randomUUID();p.category=category;p.canonicalName=name;return p;
    }
    public UUID getId(){return id;}
    public Category getCategory(){return category;}
}
