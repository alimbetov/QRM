package kz.zakaz.catalog;

import java.util.*;

/**
 * Publication and catalog read guard: no unreviewed ENUM value may escape
 * into buyer matching or training features.
 */
public final class CatalogEnumPolicy {
    private CatalogEnumPolicy(){}
    public static void validate(AttributeDefinition attribute,String canonical,
                                AttributeEnumOptionRepository options) {
        Objects.requireNonNull(attribute);Objects.requireNonNull(options);
        if(!attribute.isActive()) throw new IllegalArgumentException("Inactive attribute");
        if(attribute.getKind()==AttributeDefinition.Kind.ENUM
                && !options.existsByAttributeIdAndCodeAndActiveTrue(attribute.getId(),canonical))
            throw new IllegalArgumentException("Unknown enum option: "+attribute.getCode()+"/"+canonical);
    }
    public static void verifyFacts(List<SkuAttributeValue> facts, AttributeEnumOptionRepository options) {
        Objects.requireNonNull(facts);Objects.requireNonNull(options);
        for(var fact:facts) validate(fact.getAttribute(),fact.getCanonicalValue(),options);
    }
}
