package kz.zakaz.catalog;

import jakarta.persistence.*;
import java.math.*;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Governed global attribute identity. Numeric validation bounds may evolve
 * only through a new schemaRevision (V28 records an immutable policy snapshot).
 * Existing DECIMAL definitions remain non-negative unless explicitly revised.
 */
@Entity
@Table(name="catalog_attribute_definition",uniqueConstraints=
    @UniqueConstraint(name="uk_attribute_code",columnNames="code"))
public class AttributeDefinition {
    public enum Kind { ENUM, DECIMAL, BOOLEAN, TEXT, DATE }
    @Id @Column(nullable=false) private UUID id;
    @Column(nullable=false,length=64,updatable=false) private String code;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=16,updatable=false) private Kind kind;
    @Column(name="canonical_unit",length=16,updatable=false) private String canonicalUnit;
    @Column(name="min_decimal",precision=19,scale=6) private BigDecimal minDecimal;
    @Column(name="max_decimal",precision=19,scale=6) private BigDecimal maxDecimal;
    @Column(name="schema_revision",nullable=false) private long schemaRevision;
    @Column(name="fact_scope",nullable=false,length=20,updatable=false) private String factScope;
    @Column(nullable=false) private boolean active;

    protected AttributeDefinition() {}

    public static AttributeDefinition create(String code,Kind kind,String unit) {
        if(code==null||!code.matches("[A-Z][A-Z0-9_]{1,63}"))
            throw new IllegalArgumentException("Invalid attribute code");
        Objects.requireNonNull(kind);
        if(unit!=null&&!unit.matches("[A-Z0-9_]{1,16}"))
            throw new IllegalArgumentException("Invalid canonical unit");
        if(kind!=Kind.DECIMAL&&unit!=null)
            throw new IllegalArgumentException("Units only for decimal attributes");
        var a=new AttributeDefinition();
        a.id=UUID.randomUUID();a.code=code;a.kind=kind;
        a.canonicalUnit=unit;a.active=true;a.schemaRevision=1L;a.factScope="SKU";
        if(kind==Kind.DECIMAL)a.minDecimal=BigDecimal.ZERO;
        return a;
    }

    /** Explicit signed range for a new SKU-scoped numeric attribute. */
    public static AttributeDefinition createDecimal(String code,String unit,
                                                    BigDecimal min,BigDecimal max) {
        var a=create(code,Kind.DECIMAL,unit);
        a.setValidatedBounds(min,max);
        return a;
    }

    /** In-place policy changes are forbidden: V28 records the previous revision. */
    void reviseDecimalBounds(BigDecimal min,BigDecimal max) {
        if(kind!=Kind.DECIMAL)throw new IllegalStateException("Only numeric policy is revisioned");
        checkBounds(min,max);
        if(Objects.equals(minDecimal,min)&&Objects.equals(maxDecimal,max))
            throw new IllegalArgumentException("No policy change");
        minDecimal=min;maxDecimal=max;
        schemaRevision=Math.addExact(schemaRevision,1L);
    }

    private void setValidatedBounds(BigDecimal min,BigDecimal max) {
        checkBounds(min,max);minDecimal=min;maxDecimal=max;
    }
    private static void checkBounds(BigDecimal min,BigDecimal max) {
        if(min==null)throw new IllegalArgumentException("Numeric policy requires lower bound");
        for(BigDecimal value: new BigDecimal[]{min,max}) {
            if(value!=null&&(value.precision()>19||Math.max(0,value.scale())>6))
                throw new IllegalArgumentException("Policy bounds exceed NUMERIC(19,6)");
        }
        if(max!=null&&min.compareTo(max)>0)
            throw new IllegalArgumentException("Numeric minimum exceeds maximum");
    }

    public void validateValue(String value) {
        if(value==null||value.isBlank()||value.length()>256)
            throw new IllegalArgumentException("Attribute value required");
        switch(kind) {
            case DECIMAL -> {
                BigDecimal number;
                try { number=new BigDecimal(value); }
                catch(NumberFormatException e) {throw new IllegalArgumentException("Invalid decimal",e);}
                if(number.precision()>19||Math.max(0,number.scale())>6||
                   (minDecimal!=null&&number.compareTo(minDecimal)<0)||
                   (minDecimal==null&&number.signum()<0)||
                   (maxDecimal!=null&&number.compareTo(maxDecimal)>0))
                    throw new IllegalArgumentException("Decimal outside approved range");
            }
            case BOOLEAN -> {
                if(!value.equals("true")&&!value.equals("false"))
                    throw new IllegalArgumentException("Invalid boolean");
            }
            case ENUM -> {
                if(!value.matches("[A-Z][A-Z0-9_]{0,63}"))
                    throw new IllegalArgumentException("Invalid canonical enum");
            }
            case DATE -> {
                if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
                    throw new IllegalArgumentException("Invalid ISO date");
                try { LocalDate.parse(value); }
                catch(DateTimeParseException e) {throw new IllegalArgumentException("Invalid calendar date",e);}
            }
            case TEXT -> { }
        }
    }

    public String canonicalize(String value) {
        validateValue(value);
        return kind==Kind.DECIMAL ?
            new BigDecimal(value).stripTrailingZeros().toPlainString() : value;
    }

    /**
     * Historic SKU values were validated against their pinned revision.
     * Check canonical syntax here without retroactively applying NEW bounds.
     */
    public boolean isCanonicalForm(String value) {
        if(value==null||value.isBlank()||value.length()>256)return false;
        if(kind==Kind.DECIMAL) {
            try {
                var n=new BigDecimal(value);
                return n.precision()<=19 && Math.max(0,n.scale())<=6
                    && n.stripTrailingZeros().toPlainString().equals(value);
            } catch(NumberFormatException e) { return false; }
        }
        try {
            if(kind==Kind.DATE) {
                if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))return false;
                LocalDate.parse(value);
            } else if(kind==Kind.ENUM) {
                return value.matches("[A-Z][A-Z0-9_]{0,63}");
            } else if(kind==Kind.BOOLEAN) {
                return value.equals("true")||value.equals("false");
            }
            return true;
        } catch(DateTimeParseException e) {return false;}
    }
    public UUID getId(){return id;}
    public String getCode(){return code;}
    public Kind getKind(){return kind;}
    public boolean isActive(){return active;}
    public String getFactScope(){return factScope;}
    public long getSchemaRevision(){return schemaRevision;}
    public BigDecimal getMinDecimal(){return minDecimal;}
    public BigDecimal getMaxDecimal(){return maxDecimal;}
}
