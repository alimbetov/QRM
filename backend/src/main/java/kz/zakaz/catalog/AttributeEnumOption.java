package kz.zakaz.catalog;

import jakarta.persistence.*;
import java.util.*;

/**
 * Governed, global ENUM vocabulary. An option is a symbolic value, never
 * merchant-supplied free text or a machine-learning ordinal feature.
 */
@Entity
@Table(name="catalog_attribute_enum_option", uniqueConstraints=
    @UniqueConstraint(name="uk_attribute_enum_option", columnNames={"attribute_id","code"}))
public class AttributeEnumOption {
    @Id @Column(nullable=false) private UUID id;
    @ManyToOne(optional=false,fetch=FetchType.LAZY)
    @JoinColumn(name="attribute_id",nullable=false,updatable=false)
    private AttributeDefinition attribute;
    @Column(nullable=false,length=64,updatable=false) private String code;
    @Column(nullable=false) private boolean active;
    protected AttributeEnumOption(){}
    public static AttributeEnumOption create(AttributeDefinition attribute,String code) {
        Objects.requireNonNull(attribute);
        if(attribute.getKind()!=AttributeDefinition.Kind.ENUM)
            throw new IllegalArgumentException("Enum options require ENUM attribute");
        if(code==null||!code.matches("[A-Z][A-Z0-9_]{0,63}"))
            throw new IllegalArgumentException("Invalid enum code");
        var option=new AttributeEnumOption();
        option.id=UUID.randomUUID();option.attribute=attribute;option.code=code;option.active=true;
        return option;
    }
    public UUID getId(){return id;}
    public String getCode(){return code;}
    public boolean isActive(){return active;}
}
