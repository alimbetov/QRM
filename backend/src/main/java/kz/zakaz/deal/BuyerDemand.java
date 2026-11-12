package kz.zakaz.deal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Buyer-led request for comparable products. Seller-specific SKU is intentionally NOT used:
 * independent merchants may fulfill the same canonical product with different SKUs.
 * Domain-only S1 contract: authentication, seller eligibility and persistence are outside this class.
 */
public record BuyerDemand(
        UUID id, UUID buyerId, String city, Instant expiresAt, List<DemandLine> lines) {
    public BuyerDemand {
        Objects.requireNonNull(id); Objects.requireNonNull(buyerId);
        Objects.requireNonNull(expiresAt); Objects.requireNonNull(city);
        if (city.isBlank()) throw new IllegalArgumentException("City is required");
        if (lines == null || lines.isEmpty()) throw new IllegalArgumentException("Empty demand");
        lines = List.copyOf(lines);
        if (lines.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Null line");
        if (lines.stream().map(DemandLine::productId).distinct().count() != lines.size())
            throw new IllegalArgumentException("Duplicate product");
    }

    public boolean isOpenAt(Instant now) { return now.isBefore(expiresAt); }

    public record DemandLine(UUID productId, BigDecimal quantity, BigDecimal maxUnitPrice) {
        public DemandLine {
            Objects.requireNonNull(productId); Objects.requireNonNull(quantity);
            if (quantity.signum() <= 0 || quantity.scale() > 3) throw new IllegalArgumentException("Quantity must be positive");
            if (maxUnitPrice != null && (maxUnitPrice.signum() < 0 || maxUnitPrice.scale() > 2))
                throw new IllegalArgumentException("Invalid maximum unit price");
        }
    }
}
