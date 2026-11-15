package kz.zakaz.deal;

import java.math.*;
import java.time.Instant;
import java.util.*;

/** Immutable single-seller counteroffer snapshot for a buyer demand. */
public record SellerBid(
        UUID id, UUID demandId, UUID sellerId, int revision, Instant expiresAt, List<BidLine> lines) {
    public SellerBid {
        Objects.requireNonNull(id); Objects.requireNonNull(demandId);
        Objects.requireNonNull(sellerId); Objects.requireNonNull(expiresAt);
        if (revision < 1) throw new IllegalArgumentException("Revision must be positive");
        if (lines == null || lines.isEmpty()) throw new IllegalArgumentException("Empty offer");
        lines = List.copyOf(lines);
        if (lines.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Null offer line");
        if (lines.stream().map(BidLine::productId).distinct().count() != lines.size())
            throw new IllegalArgumentException("Duplicate offered product");
    }

    public record BidLine(UUID productId, UUID skuId, BigDecimal quantity, BigDecimal unitPrice) {
        public BidLine {
            Objects.requireNonNull(productId); Objects.requireNonNull(skuId);
            Objects.requireNonNull(quantity); Objects.requireNonNull(unitPrice);
            if (quantity.signum() <= 0 || quantity.scale() > 3) throw new IllegalArgumentException("Quantity invalid");
            if (unitPrice.signum() < 0 || unitPrice.scale() > 2) throw new IllegalArgumentException("Price invalid");
        }
        public BigDecimal total() { return unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP); }
    }

    public BigDecimal total() {
        return lines.stream().map(BidLine::total).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
    }
}
