package kz.zakaz.catalog;

import jakarta.persistence.*;
import java.math.*;
import java.util.*;

/** Global reusable attribute definition; localized display values are separate S2 work. */
@Entity
@Table(name="catalog_attribute_definition",uniqueConstraints=@UniqueConstraint(name="uk_attribute_code",columnNames="code"))
public class AttributeDefinition {
    public enum Kind { ENUM, DECIMAL, BOOLEAN, TEXT }
    @Id @Column(nullable=false) private UUID id;
    @Column(nullable=false,length=64,updatable=false) private String code;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private Kind kind;
    @Column(name="canonical_unit",length=16) private String canonicalUnit;
    @Column(nullable=false) private boolean active;
    protected AttributeDefinition(){}
    public static AttributeDefinition create(String code,Kind kind,String unit) {
        if(code==null||!code.matches("[A-Z][A-Z0-9_]{1,63}"))throw new IllegalArgumentException("Invalid attribute code");
        Objects.requireNonNull(kind);
        if(unit!=null && !unit.matches("[A-Z0-9_]{1,16}"))throw new IllegalArgumentException("Invalid canonical unit");
        if(kind!=Kind.DECIMAL && unit!=null)throw new IllegalArgumentException("Units only for decimals");
        var a=new AttributeDefinition();a.id=UUID.randomUUID();a.code=code;a.kind=kind;a.canonicalUnit=unit;a.active=true;return a;
    }
    public void validateValue(String value){
        if(value==null || value.isBlank() || value.length()>256)throw new IllegalArgumentException("Attribute value required");
        switch(kind){
            case DECIMAL -> {
                try { if(new BigDecimal(value).signum()<0)throw new IllegalArgumentException("Negative value"); }
                catch(NumberFormatException e){throw new IllegalArgumentException("Invalid decimal",e);}
            }
            case BOOLEAN -> {
                if(!value.equals("true")&&!value.equals("false"))throw new IllegalArgumentException("Invalid boolean");
            }
            case ENUM -> {
                if(!value.matches("[A-Za-z0-9_\\-]{1,256}"))throw new IllegalArgumentException("Invalid canonical enum");
            }
            case TEXT -> { }
        }
    }
    public UUID getId(){return id;}
    public String getCode(){return code;}
    public Kind getKind(){return kind;}
    public String canonicalize(String value){
        validateValue(value);
        if(kind==Kind.DECIMAL)return new BigDecimal(value).stripTrailingZeros().toPlainString();
        return value;
    }
}
