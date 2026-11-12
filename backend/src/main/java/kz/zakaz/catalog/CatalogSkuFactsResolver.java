package kz.zakaz.catalog;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import kz.zakaz.domain.types.ListingStatus;

/**
 * Server-side merchant SKU facts for future demand/bid matching. Never take class,
 * product, seller, publication or attribute facts from a client-supplied bid.
 * Verification of seller identity, territory and live stock belongs to separate
 * authoritative gateways before a bid can become eligible.
 */
@Service
public class CatalogSkuFactsResolver {
    private final SellerSkuRepository skus;
    private final CategoryAttributeRuleRepository rules;
    private final SkuAttributeValueRepository attributes;
    public CatalogSkuFactsResolver(SellerSkuRepository skus, CategoryAttributeRuleRepository rules,
                                   SkuAttributeValueRepository attributes) {
        this.skus=skus;this.rules=rules;this.attributes=attributes;
    }
    public record Facts(UUID sellerId, UUID skuId, UUID productId, String hccCode,
                        Map<String,String> attributes) {
        public Facts { attributes=Map.copyOf(attributes); }
    }
    @Transactional(readOnly=true)
    public Facts resolveForSeller(UUID authenticatedSellerId, UUID skuId) {
        Objects.requireNonNull(authenticatedSellerId);Objects.requireNonNull(skuId);
        var sku=skus.findById(skuId).orElseThrow(()->new NoSuchElementException("SKU not found"));
        var listing=sku.getListing();
        if(!authenticatedSellerId.equals(listing.getSellerId()))
            throw new SecurityException("Seller does not own SKU");
        if(listing.getStatus()!=ListingStatus.PUBLIC)
            throw new IllegalStateException("SKU listing is not public");
        var product=listing.getProduct();
        var category=product.getCategory();
        if(!category.isActive()) throw new IllegalStateException("Inactive SKU category");
        var ruleSet=rules.findByCategoryId(category.getId());
        var supplied=attributes.findBySkuId(skuId);
        CatalogPublicationPolicy.verify(category,ruleSet,supplied);
        Map<String,String> result=new TreeMap<>();
        for(var value:supplied) result.put(value.getAttribute().getCode(),value.getCanonicalValue());
        return new Facts(listing.getSellerId(),skuId,product.getId(),category.getHccCode(),result);
    }
}
