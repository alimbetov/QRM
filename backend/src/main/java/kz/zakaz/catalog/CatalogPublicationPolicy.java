package kz.zakaz.catalog;

import java.util.*;

/**
 * SKU attribute gate checked per variant. A fact is pinned to its write-time
 * attribute schema revision; never validate historic values against NEW bounds.
 * DB FK + append-only revision log enforce the actual revision identity.
 */
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
            if(actualSku==null)actualSku=sku;
            if(!sku.equals(actualSku)||!category.getId().equals(
                    fact.getSku().getListing().getProduct().getCategory().getId()))
                throw new IllegalArgumentException("Mixed SKU facts");
            if(!"SKU".equals(fact.getAttribute().getFactScope())
                    ||fact.getDefinitionRevision()<1
                    ||fact.getDefinitionRevision()>fact.getAttribute().getSchemaRevision())
                throw new IllegalArgumentException("Invalid SKU attribute schema revision");
            // A stale DRAFT cannot be newly published under an obsolete policy.
            // Published facts remain readable under their pinned historical revision.
            if(fact.getSku().getListing().getStatus()==kz.zakaz.domain.types.ListingStatus.DRAFT
                    && fact.getDefinitionRevision()!=fact.getAttribute().getSchemaRevision())
                throw new IllegalArgumentException("Draft fact uses stale attribute schema revision");
            if(!fact.getAttribute().isCanonicalForm(fact.getCanonicalValue()))
                throw new IllegalArgumentException("Not canonicalized");
        }
        for(var r:rules)
            if(r.isRequired()&&!seen.contains(r.getAttribute().getId()))
                throw new IllegalArgumentException("Required attribute missing: "+r.getAttribute().getCode());
    }
}
