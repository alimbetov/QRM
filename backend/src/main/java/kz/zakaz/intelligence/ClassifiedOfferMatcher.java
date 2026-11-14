package kz.zakaz.intelligence;

import java.time.Instant;
import java.util.*;
import kz.zakaz.deal.*;

/**
 * Explicit, server-verified typed facts for a requested product and its seller SKU.
 * HARD values are equality constraints on canonical dictionary codes; SOFT are only scoring.
 * This is a pure domain preview, not an authorization or live seller inventory check.
 */
public final class ClassifiedOfferMatcher {
    private ClassifiedOfferMatcher() {}

    public record RequiredItem(UUID productId, String classCode, Map<String, String> hardAttributes,
                               Map<String, String> softAttributes) {
        public RequiredItem {
            Objects.requireNonNull(productId);
            if (classCode == null || classCode.isBlank()) throw new IllegalArgumentException("Class required");
            hardAttributes = Map.copyOf(hardAttributes);
            softAttributes = Map.copyOf(softAttributes);
        }
    }
    public record SellerItem(UUID productId, UUID skuId, String classCode,
                             Map<String, String> attributes, boolean sellerVerified,
                             boolean listingPublished, boolean serviceAreaEligible) {
        public SellerItem {
            Objects.requireNonNull(productId); Objects.requireNonNull(skuId);
            if (classCode == null || classCode.isBlank()) throw new IllegalArgumentException("Class required");
            attributes = Map.copyOf(attributes);
        }
    }
    public record MatchEvidence(boolean eligible, List<String> reasons,
                                int hardMatched, int softMatched, int softCompared) {
        public MatchEvidence { reasons = List.copyOf(reasons); }
    }

    /**
     * Exact canonical product identity required in this prototype: compatible substitutes
     * require a separate explicit, moderated buyer opt-in decision.
     */
    public static MatchEvidence match(RequiredItem request, SellerItem seller) {
        Objects.requireNonNull(request); Objects.requireNonNull(seller);
        var problems = new ArrayList<String>();
        int hard = 0, soft = 0, compared = 0;
        if (!seller.sellerVerified()) problems.add("SELLER_NOT_VERIFIED");
        if (!seller.listingPublished()) problems.add("LISTING_NOT_PUBLIC");
        if (!seller.serviceAreaEligible()) problems.add("SERVICE_AREA_NOT_COVERED");
        if (!request.productId().equals(seller.productId())) problems.add("PRODUCT_MISMATCH");
        if (!request.classCode().equals(seller.classCode())) problems.add("CLASS_MISMATCH");
        for (var e : new TreeMap<>(request.hardAttributes()).entrySet()) {
            if (e.getValue().equals(seller.attributes().get(e.getKey()))) hard++;
            else problems.add("HARD_ATTRIBUTE_MISMATCH:" + e.getKey());
        }
        for (var e : new TreeMap<>(request.softAttributes()).entrySet()) {
            compared++;
            if (e.getValue().equals(seller.attributes().get(e.getKey()))) soft++;
        }
        return new MatchEvidence(problems.isEmpty(), problems, hard, soft, compared);
    }

    /**
     * Full-basket matching after basic demand/bid consistency. sku/merchant ownership and
     * stock MUST be checked through server-side catalog/repositories by the application.
     */
    public static List<MatchEvidence> matchBasket(
        BuyerDemand demand, SellerBid bid, Instant asOf,
        Map<UUID, RequiredItem> requirements, Map<UUID, SellerItem> sellerSkus) {
        DealQualification.validate(demand, bid, asOf);
        var results = new ArrayList<MatchEvidence>();
        for (var line : bid.lines()) {
            var req = requirements.get(line.productId());
            var candidate = sellerSkus.get(line.skuId());
            if (req == null || candidate == null)
                throw new IllegalArgumentException("Missing verified class/sku facts");
            results.add(match(req, candidate));
        }
        return List.copyOf(results);
    }
}
