package kz.zakaz.intelligence;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import kz.zakaz.catalog.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ML-R1: authoritative Catalog -> deterministic compatibility -> frozen
 * FeatureRegistry. Does not build a second HCC taxonomy or trust merchant-
 * supplied feature vectors, and never promotes an ineligible seller to ML.
 *
 * Caller MUST obtain verified seller status and service-area eligibility from
 * trusted server-side services, never from a client/LLM/feature request.
 */
@Service
public final class CanonicalFeatureAdapter {
    private final CanonicalProductRepository products;
    private final CatalogSkuFactsResolver skuFacts;
    private final CategoryAttributeRuleRepository categoryRules;
    private final AttributeEnumOptionRepository enumOptions;

    public CanonicalFeatureAdapter(CanonicalProductRepository products,
                                   CatalogSkuFactsResolver skuFacts,
                                   CategoryAttributeRuleRepository categoryRules,
                                   AttributeEnumOptionRepository enumOptions) {
        this.products=products;
        this.skuFacts=skuFacts;
        this.categoryRules=categoryRules;
        this.enumOptions=enumOptions;
    }

    /** These are not end-user supplied booleans. Integration must verify them. */
    public record EligibilityEvidence(boolean sellerVerified,
                                      boolean serviceAreaEligible) {}

    public record Projection(
        ClassifiedOfferMatcher.RequiredItem request,
        ClassifiedOfferMatcher.SellerItem seller,
        ClassifiedOfferMatcher.MatchEvidence match
    ) {
        public FeatureVectorEncoder.Encoded encodeEligible(
            boolean sameRequestCity,boolean communicationLanguageMatch,
            BigDecimal verifiedAllInPrice,BigDecimal buyerBudget,
            Double observedSellerResponseRate) {
            return DealFeatureProjector.encode(match,sameRequestCity,
                communicationLanguageMatch,verifiedAllInPrice,buyerBudget,
                observedSellerResponseRate);
        }
    }

    /**
     * Resolves *both* buyer-request schema and seller SKU facts from existing
     * authoritative Catalog. The seller ID must already be authenticated.
     * Price, stock and fulfillment are intentionally not calculated here.
     */
    @Transactional(readOnly=true)
    public Projection project(UUID authenticatedSellerId,UUID skuId,
                              ClassifiedOfferMatcher.RequiredItem requested,
                              EligibilityEvidence eligibility) {
        Objects.requireNonNull(authenticatedSellerId);
        Objects.requireNonNull(skuId);
        Objects.requireNonNull(requested);
        Objects.requireNonNull(eligibility);

        var product=products.findById(requested.productId())
            .orElseThrow(()->new IllegalArgumentException("Unknown canonical product"));
        var category=product.getCategory();
        if(!category.isActive())
            throw new IllegalArgumentException("Inactive HCC category");

        // Existing HCC 2/2/3/5/8 positional validation, no synthetic codes.
        HccPath.depth(requested.classCode());
        if(!category.getHccCode().equals(requested.classCode()))
            throw new IllegalArgumentException("Request HCC does not match canonical product");

        Map<String,AttributeDefinition> allowed=new HashMap<>();
        for(var rule:categoryRules.findByCategoryId(category.getId())) {
            if(!rule.getCategory().getId().equals(category.getId()) ||
                !rule.getAttribute().isActive() ||
                allowed.putIfAbsent(rule.getAttribute().getCode(),rule.getAttribute())!=null)
                throw new IllegalArgumentException("Invalid HCC attribute rules");
        }

        var hard=canonicalize(requested.hardAttributes(),allowed);
        var soft=canonicalize(requested.softAttributes(),allowed);
        for(String name:hard.keySet())
            if(soft.containsKey(name))
                throw new IllegalArgumentException("Attribute duplicated across hard/soft: "+name);

        var trustedRequest=new ClassifiedOfferMatcher.RequiredItem(
            product.getId(),category.getHccCode(),hard,soft);

        // This already checks the seller's ownership, PUBLIC status, active
        // Category, required SKU attributes and curated ENUM options.
        CatalogSkuFactsResolver.Facts verified=skuFacts.resolveForSeller(
            authenticatedSellerId,skuId);

        var seller=new ClassifiedOfferMatcher.SellerItem(
            verified.productId(),verified.skuId(),verified.hccCode(),
            verified.attributes(),eligibility.sellerVerified(),true,
            eligibility.serviceAreaEligible());

        return new Projection(trustedRequest,seller,
            ClassifiedOfferMatcher.match(trustedRequest,seller));
    }

    private Map<String,String> canonicalize(
        Map<String,String> requested,Map<String,AttributeDefinition> allowed) {
        var normalized=new TreeMap<String,String>();
        for(var entry:requested.entrySet()) {
            String code=entry.getKey();
            AttributeDefinition definition=allowed.get(code);
            if(definition==null)
                throw new IllegalArgumentException("Unknown HCC attribute: "+code);
            String canonical=definition.canonicalize(entry.getValue());
            CatalogEnumPolicy.validate(definition,canonical,enumOptions);
            normalized.put(code,canonical);
        }
        return Map.copyOf(normalized);
    }
}
