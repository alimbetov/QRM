package kz.zakaz.intelligence;

import java.math.BigDecimal;
import java.util.*;

/** Source facts have passed matching gates; this projection never declares a deal eligible. */
public final class DealFeatureProjector {
    private DealFeatureProjector() {}

    public static final FeatureRegistry V1 = new FeatureRegistry("buyer_choice", "v1", List.of(
        "MAT.hard_eligible", "MAT.soft_match_ratio", "GEO.same_request_city",
        "LNG.communication_match", "OFR.price_to_budget_ratio",
        "OFR.price_to_budget_ratio_known", "SEL.response_rate_30d",
        "SEL.response_rate_30d_known"
    ));

    public static FeatureVectorEncoder.Encoded encode(
        ClassifiedOfferMatcher.MatchEvidence match,
        boolean sameRequestCity, boolean communicationLanguageMatch,
        BigDecimal allInPrice, BigDecimal buyerBudget, Double sellerResponseRate) {
        Objects.requireNonNull(match);
        if (!match.eligible()) throw new IllegalArgumentException("Ineligible seller may not be ML-ranked");
        var f = new HashMap<String, Double>();
        f.put("MAT.hard_eligible", 1.0);
        f.put("MAT.soft_match_ratio", match.softCompared() == 0 ? 0.0
                : (double) match.softMatched()/match.softCompared());
        f.put("GEO.same_request_city", sameRequestCity ? 1.0 : 0.0);
        f.put("LNG.communication_match", communicationLanguageMatch ? 1.0 : 0.0);
        if (buyerBudget != null && allInPrice != null && buyerBudget.signum() > 0) {
            if (allInPrice.signum() < 0) throw new IllegalArgumentException("Negative price");
            f.put("OFR.price_to_budget_ratio", allInPrice.doubleValue()/buyerBudget.doubleValue());
            f.put("OFR.price_to_budget_ratio_known", 1.0);
        }
        if (sellerResponseRate != null) {
            if (!Double.isFinite(sellerResponseRate) || sellerResponseRate < 0 || sellerResponseRate > 1)
                throw new IllegalArgumentException("Invalid response rate");
            f.put("SEL.response_rate_30d", sellerResponseRate);
            f.put("SEL.response_rate_30d_known", 1.0);
        }
        return FeatureVectorEncoder.encode(V1, f);
    }
}
