package kz.zakaz.catalog;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import kz.zakaz.domain.types.ListingStatus;

/**
 * Server-owned SKU facts; excludes unpublished, foreign and invalid enum facts.
 * Current inventory, merchant approval, territory and quote remain separate gates.
 */
@Service
public class CatalogSkuFactsResolver {
    private final SellerSkuRepository skus;
    private final CategoryAttributeRuleRepository rules;
    private final SkuAttributeValueRepository attributes;
    private final AttributeEnumOptionRepository enumOptions;
    public CatalogSkuFactsResolver(SellerSkuRepository skus,CategoryAttributeRuleRepository rules,
                                   SkuAttributeValueRepository attributes,AttributeEnumOptionRepository enumOptions) {
        this.skus=skus;this.rules=rules;this.attributes=attributes;this.enumOptions=enumOptions;
    }
    public record Facts(UUID sellerId,UUID skuId,UUID productId,String hccCode,
                        Map<String,String> attributes) {
        public Facts { attributes=Map.copyOf(attributes); }
    }
    @Transactional(readOnly=true)
    public Facts resolveForSeller(UUID authenticatedSellerId,UUID skuId) {
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
        var applicable=rules.findByCategoryId(category.getId());
        var supplied=attributes.findBySkuId(skuId);
        CatalogPublicationPolicy.verify(category,applicable,supplied);
        CatalogEnumPolicy.verifyFacts(supplied,enumOptions);
        Map<String,String> results=new TreeMap<>();
        for(var value:supplied) results.put(value.getAttribute().getCode(),value.getCanonicalValue());
        return new Facts(listing.getSellerId(),skuId,product.getId(),category.getHccCode(),results);
    }
}
