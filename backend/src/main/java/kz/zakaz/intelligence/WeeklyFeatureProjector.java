package kz.zakaz.intelligence;

import java.util.*;

/** Frozen weekly inventory/demand features. No model inference or purchase advice here. */
public final class WeeklyFeatureProjector {
    private WeeklyFeatureProjector() {}
    public static final FeatureRegistry V1 = new FeatureRegistry("inventory_advisor", "v1", List.of(
        "SALES.units_1w", "SALES.units_4w", "DEMAND.units_4w",
        "STOCK.available_days_4w", "STOCK.stockout_days_4w",
        "STOCK.velocity_4w", "STOCK.velocity_known_4w",
        "DATA.complete_weeks_4w", "DATA.stock_exposure_complete_4w"
    ));

    public static FeatureVectorEncoder.Encoded project(
        WeeklyIntelligenceAggregator.Window one,
        WeeklyIntelligenceAggregator.Window four) {
        Objects.requireNonNull(one); Objects.requireNonNull(four);
        if (!one.key().equals(four.key()) || !one.lastClosedMonday().equals(four.lastClosedMonday())
                || one.requestedWeeks() != 1 || four.requestedWeeks() != 4)
            throw new IllegalArgumentException("Incompatible windows");
        Map<String, Double> features = new HashMap<>();
        features.put("SALES.units_1w", (double) one.soldUnits());
        features.put("SALES.units_4w", (double) four.soldUnits());
        features.put("DEMAND.units_4w", (double) four.demandUnits());
        features.put("STOCK.available_days_4w", (double) four.availableDays());
        features.put("STOCK.stockout_days_4w", (double) four.stockoutDays());
        features.put("DATA.complete_weeks_4w", (double) four.observedWeeks());
        features.put("DATA.stock_exposure_complete_4w", four.complete() ? 1.0 : 0.0);
        four.soldPerAvailableDay().ifPresent(v -> {
            features.put("STOCK.velocity_4w", v);
            features.put("STOCK.velocity_known_4w", 1.0);
        });
        return FeatureVectorEncoder.encode(V1, features);
    }
}
