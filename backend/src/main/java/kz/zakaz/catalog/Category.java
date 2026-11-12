package kz.zakaz.catalog;

import jakarta.persistence.*;
import java.util.*;
import kz.zakaz.domain.types.ProductType;

/** One HCC tree, using positional 2/2/3/5/8 groups and string code identity. */
@Entity
@Table(name="catalog_category",uniqueConstraints=
    @UniqueConstraint(name="uk_catalog_category_code",columnNames="hcc_code"))
public class Category {
    @Id @Column(nullable=false) private UUID id;
    @Column(name="hcc_code",nullable=false,length=20,updatable=false) private String hccCode;
    @Enumerated(EnumType.STRING) @Column(name="product_type",nullable=false,length=16) private ProductType type;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="parent_id") private Category parent;
    @Column(nullable=false,length=160) private String label;
    @Column(nullable=false) private boolean active;
    @Version private Long version;
    protected Category() {}
    public static Category create(String hccCode,ProductType type,String label,Category parent) {
        int depth=HccPath.depth(hccCode);
        Objects.requireNonNull(type);
        if(label==null||label.isBlank()||label.length()>160)
            throw new IllegalArgumentException("Category label required");
        if(parent!=null && (parent.type!=type
            || !HccPath.isDescendant(hccCode,parent.hccCode)
            || depth!=HccPath.depth(parent.hccCode)+1))
            throw new IllegalArgumentException("Invalid category parent/type");
        var c=new Category();c.id=UUID.randomUUID();c.hccCode=hccCode;c.type=type;
        c.label=label;c.parent=parent;c.active=true;return c;
    }
    public UUID getId(){return id;}
    public String getHccCode(){return hccCode;}
    public ProductType getType(){return type;}
    public boolean isActive(){return active;}
}
