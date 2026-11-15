package kz.zakaz.intelligence.reliability;

import java.time.LocalDate;
import java.util.*;
import java.util.function.ToDoubleFunction;
import kz.zakaz.intelligence.WeeklyIntelligenceAggregator;

/**
 * Five expanding-origin tests. Each candidate sees only history strictly before the
 * target week. All methods are evaluated against the SAME chronological folds.
 * Current prototype evaluates observed demand counts; later models must use as-of features.
 */
public final class TemporalBacktester {
    private TemporalBacktester() {}
    public interface ForecastModel {
        double predict(List<WeeklyIntelligenceAggregator.Week> history);
    }
    public record Fold(LocalDate targetMonday, int trainingWeeks, double actual,
                       double baseline, double challenger,
                       double baselineAbsoluteError, double challengerAbsoluteError) {}
    public record Report(List<Fold> folds, double baselineMae, double challengerMae,
                         double improvementFraction, double worstChallengerError,
                         double challengerErrorStdDev) {
        public Report { folds=List.copyOf(folds); }
    }

    /** Four most recent observed weeks, no synthetic zero padding. */
    public static double movingAverage4(List<WeeklyIntelligenceAggregator.Week> train) {
        int start=Math.max(0,train.size()-4);
        return train.subList(start,train.size()).stream()
            .mapToLong(WeeklyIntelligenceAggregator.Week::demandUnits).average()
            .orElseThrow(()->new IllegalArgumentException("No training weeks"));
    }

    public static Report evaluateFive(List<WeeklyIntelligenceAggregator.Week> closed,
                                      WeeklyIntelligenceAggregator.Key key,
                                      int minimumTrainingWeeks, ForecastModel candidate) {
        Objects.requireNonNull(closed);
        Objects.requireNonNull(key);
        Objects.requireNonNull(candidate);
        if (minimumTrainingWeeks<4) throw new IllegalArgumentException("Need 4+ training weeks");
        var series=closed.stream().filter(w->w.id().key().equals(key))
            .sorted(Comparator.comparing(w->w.id().monday())).toList();
        if (series.size()<minimumTrainingWeeks+5)
            throw new IllegalArgumentException("Insufficient history for five temporal tests");
        for (int i=0;i<series.size();i++) {
            var w=series.get(i);
            if (w.observedDays()!=7 || w.unknownStockDays()!=0)
                throw new IllegalArgumentException("Partial or unknown-exposure week");
            if (i>0 && !series.get(i-1).id().monday().plusWeeks(1).equals(w.id().monday()))
                throw new IllegalArgumentException("Gap/duplicate week");
        }
        var folds=new ArrayList<Fold>();
        for (int t=series.size()-5;t<series.size();t++) {
            var train=List.copyOf(series.subList(0,t));
            if (train.size()<minimumTrainingWeeks)
                throw new IllegalArgumentException("Too few past weeks");
            double baseline=movingAverage4(train);
            double challenger=candidate.predict(train);
            if (!Double.isFinite(challenger) || challenger<0)
                throw new IllegalArgumentException("Invalid candidate forecast");
            double actual=series.get(t).demandUnits();
            folds.add(new Fold(series.get(t).id().monday(),train.size(),actual,baseline,challenger,
                Math.abs(actual-baseline),Math.abs(actual-challenger)));
        }
        double baselineMae=folds.stream().mapToDouble(Fold::baselineAbsoluteError).average().orElseThrow();
        double challengerMae=folds.stream().mapToDouble(Fold::challengerAbsoluteError).average().orElseThrow();
        // Undefined baseline-relative improvement must never be treated as a successful gain.
        double improvement=baselineMae==0 ? (challengerMae==0 ? 0 : -1)
            : (baselineMae-challengerMae)/baselineMae;
        double variance=folds.stream().mapToDouble(f ->
            Math.pow(f.challengerAbsoluteError()-challengerMae,2)).sum()/folds.size();
        return new Report(folds,baselineMae,challengerMae,improvement,
            folds.stream().mapToDouble(Fold::challengerAbsoluteError).max().orElseThrow(),
            Math.sqrt(variance));
    }
}
