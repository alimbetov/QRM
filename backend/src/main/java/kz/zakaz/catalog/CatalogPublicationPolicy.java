package kz.zakaz.catalog;

import java.util.*;

/** Full SKU attribute gate; checked per SKU, not across a seller's combined variants. */
public final class CatalogPublicationPolicy {
    private CatalogPublicationPolicy(){}
    public static void verify(Category category, List<CategoryAttributeRule> rules,
                              List<SkuAttributeValue> supplied) {
        Objects.requireNonNull(category);Objects.requireNonNull(rules);Objects.requireNonNull(supplied);
        Map<UUID,CategoryAttributeRule> allowed=new HashMap<>();
        for(var r:rules) {
            if(!category.getId().equals(r.getCategory().getId())
                    || allowed.putIfAbsent(r.getAttribute().getId(),r)!=null)
                throw new IllegalArgumentException("Invalid class attribute rule");
        }
        Set<UUID> seen=new HashSet<>();
        UUID actualSku=null;
        for(var fact:supplied) {
            Objects.requireNonNull(fact);
            UUID id=fact.getAttribute().getId();
            if(!allowed.containsKey(id)||!seen.add(id))
                throw new IllegalArgumentException("Duplicate or disallowed SKU attribute");
            UUID sku=fact.getSku().getId();
            if(actualSku==null) actualSku=sku;
            if(!sku.equals(actualSku) || !category.getId().equals(
                    fact.getSku().getListing().getProduct().getCategory().getId()))
                throw new IllegalArgumentException("Mixed SKU facts");
            if(!fact.getCanonicalValue().equals(fact.getAttribute().canonicalize(fact.getCanonicalValue())))
                throw new IllegalArgumentException("Not canonicalized");
        }
        for(var r:rules)
            if(r.isRequired()&&!seen.contains(r.getAttribute().getId()))
                throw new IllegalArgumentException("Required attribute missing: "+r.getAttribute().getCode());
    }
}
