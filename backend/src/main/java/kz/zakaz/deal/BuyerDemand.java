package kz.zakaz.deal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import kz.zakaz.domain.types.MeasurementUnit;

/** Buyer-led request with canonical product and an explicit, non-SKU-specific quantity unit. */
public record BuyerDemand(
        UUID id, UUID buyerId, String city, Instant expiresAt, List<DemandLine> lines) {
    public BuyerDemand {
        Objects.requireNonNull(id);Objects.requireNonNull(buyerId);
        Objects.requireNonNull(expiresAt);Objects.requireNonNull(city);
        if(city.isBlank())throw new IllegalArgumentException("City is required");
        if(lines==null||lines.isEmpty())throw new IllegalArgumentException("Empty demand");
        lines=List.copyOf(lines);
        if(lines.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("Null line");
        if(lines.stream().map(DemandLine::productId).distinct().count()!=lines.size())
            throw new IllegalArgumentException("Duplicate product");
    }
    public boolean isOpenAt(Instant now){return now.isBefore(expiresAt);}
    /** NULL unit is a legacy read-only shape, explicitly ineligible for checkout. */
    public record DemandLine(UUID productId,BigDecimal quantity,BigDecimal maxUnitPrice,
                             MeasurementUnit unit) {
        public DemandLine(UUID productId,BigDecimal quantity,BigDecimal maxUnitPrice){
            this(productId,quantity,maxUnitPrice,null);
        }
        public DemandLine {
            Objects.requireNonNull(productId);Objects.requireNonNull(quantity);
            if(quantity.signum()<=0 || quantity.scale()>3 || quantity.precision()>19)
                throw new IllegalArgumentException("Quantity must be positive");
            if(maxUnitPrice!=null && (maxUnitPrice.signum()<0 || maxUnitPrice.scale()>2
                    || maxUnitPrice.precision()>19))
                throw new IllegalArgumentException("Invalid maximum unit price");
            if((unit==MeasurementUnit.PCS || unit==MeasurementUnit.LICENSE)
                    && quantity.stripTrailingZeros().scale()>0)
                throw new IllegalArgumentException("Fractional discrete demand");
        }
    }
}
