package kz.zakaz.intelligence.reliability;

import java.time.LocalDate;
import java.util.*;
import kz.zakaz.intelligence.WeeklyIntelligenceAggregator;

/**
 * Pilot quality gate based on full, closed, consecutive ISO weeks.
 * Numeric thresholds are configurable risk policy, not universal statistical guarantees.
 */
public final class DataSufficiencyEvaluator {
    private DataSufficiencyEvaluator() {}

    public record Policy(int minimumWeeks, double minimumCoverage, int minimumAvailableDays) {
        public Policy {
            if (minimumWeeks < 1 || minimumCoverage <= 0 || minimumCoverage > 1
                    || minimumAvailableDays < 0)
                throw new IllegalArgumentException("Invalid sufficiency policy");
        }
    }
    public record Report(boolean qualified, int fullWeeks, int expectedWeeks,
                         double observedDayCoverage, int availableDays,
                         List<String> reasons) {
        public Report { reasons = List.copyOf(reasons); }
    }

    public static Report evaluate(List<WeeklyIntelligenceAggregator.Week> history,
                                  WeeklyIntelligenceAggregator.Key key,
                                  LocalDate lastClosedMonday, Policy policy) {
        Objects.requireNonNull(history);
        Objects.requireNonNull(key);
        Objects.requireNonNull(lastClosedMonday);
        Objects.requireNonNull(policy);
        if (!lastClosedMonday.getDayOfWeek().equals(java.time.DayOfWeek.MONDAY))
            throw new IllegalArgumentException("Not an ISO week start");
        Map<LocalDate, WeeklyIntelligenceAggregator.Week> rows = new HashMap<>();
        for (var row : history) {
            if (!row.id().key().equals(key) || row.id().monday().isAfter(lastClosedMonday)) continue;
            if (rows.putIfAbsent(row.id().monday(), row) != null)
                throw new IllegalArgumentException("Duplicate weekly snapshot");
        }
        int full=0, days=0, available=0;
        var reasons = new ArrayList<String>();
        for (int i=policy.minimumWeeks()-1; i>=0; i--) {
            var row = rows.get(lastClosedMonday.minusWeeks(i));
            if (row == null) continue;
            if (row.observedDays() < 0 || row.observedDays() > 7
                    || row.availableDays() < 0 || row.stockoutDays() < 0 || row.unknownStockDays() < 0
                    || row.availableDays()+row.stockoutDays()+row.unknownStockDays()!=row.observedDays())
                throw new IllegalArgumentException("Invalid stock exposure counts");
            days+=row.observedDays();
            available+=row.availableDays();
            if (row.observedDays()==7 && row.unknownStockDays()==0) full++;
        }
        double coverage=(double)days/(policy.minimumWeeks()*7L);
        if (full<policy.minimumWeeks()) reasons.add("MISSING_OR_UNKNOWN_EXPOSURE");
        if (coverage<policy.minimumCoverage()) reasons.add("INSUFFICIENT_DAY_COVERAGE");
        if (available<policy.minimumAvailableDays()) reasons.add("LOW_STOCK_EXPOSURE");
        return new Report(reasons.isEmpty(),full,policy.minimumWeeks(),coverage,available,reasons);
    }
}
