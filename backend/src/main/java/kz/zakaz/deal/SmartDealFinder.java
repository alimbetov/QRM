package kz.zakaz.deal;

import java.math.*;
import java.util.*;

/**
 * Explainable fallback recommender. This is NOT trained ML and does NOT authorize push.
 * Compare equal products/quantities; shipping costs must be verified rather than guessed.
 */
public final class SmartDealFinder {
    private SmartDealFinder() {}
    public record ComparableOffer(UUID sellerId, UUID listingId, UUID productId,
                                  BigDecimal unitPrice, BigDecimal deliveryFee,
                                  boolean published, boolean sellerEligible) {
        public ComparableOffer {
            Objects.requireNonNull(sellerId); Objects.requireNonNull(listingId);
            Objects.requireNonNull(productId); Objects.requireNonNull(unitPrice);
            if (unitPrice.signum() < 0 || unitPrice.scale() > 2) throw new IllegalArgumentException("Price invalid");
            if (deliveryFee != null && (deliveryFee.signum() < 0 || deliveryFee.scale() > 2))
                throw new IllegalArgumentException("Delivery fee invalid");
        }
        public BigDecimal allInPrice(BigDecimal quantity) {
            if (deliveryFee == null) throw new IllegalStateException("Shipping not quoted");
            return unitPrice.multiply(quantity).add(deliveryFee).setScale(2, RoundingMode.HALF_UP);
        }
    }
    public record Deal(UUID listingId, UUID sellerId, BigDecimal allInPrice, BigDecimal saving) {}

    public static List<Deal> betterOffers(UUID productId, BigDecimal quantity,
            BigDecimal referenceAllInPrice, Collection<ComparableOffer> candidates, BigDecimal minimumSaving) {
        Objects.requireNonNull(productId); Objects.requireNonNull(quantity);
        Objects.requireNonNull(referenceAllInPrice); Objects.requireNonNull(candidates);
        Objects.requireNonNull(minimumSaving);
        if (quantity.signum() <= 0 || referenceAllInPrice.signum() < 0 || minimumSaving.signum() < 0)
            throw new IllegalArgumentException("Invalid comparison");
        return candidates.stream()
            .filter(c -> c.published() && c.sellerEligible() && c.productId().equals(productId)
                && c.deliveryFee() != null)
            .map(c -> new Deal(c.listingId(), c.sellerId(), c.allInPrice(quantity),
                    referenceAllInPrice.subtract(c.allInPrice(quantity))))
            .filter(d -> d.saving().compareTo(minimumSaving) >= 0 && d.saving().signum() > 0)
            .sorted(Comparator.comparing(Deal::allInPrice).thenComparing(Deal::listingId))
            .toList();
    }
}
