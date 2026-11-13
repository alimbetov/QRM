package kz.zakaz.intelligence.reliability;

import java.time.LocalDate;
import java.util.*;

/**
 * Offline calibration of sufficiency policy from strictly historical, OUT-OF-SAMPLE
 * qualification cohorts. This is not model training, nor automatic live promotion.
 * Calibration/selection observations must be separate from untouched final holdout.
 */
public final class AdaptiveSufficiencyCalibrator {
    private AdaptiveSufficiencyCalibrator() {}

    /** Metrics recorded for one candidate threshold on one independent evaluation cohort. */
    public record Evidence(String cohort, LocalDate evaluatedWeek,
                           int minimumWeeks, double minimumDayCoverage,
                           int independentSeries, int temporalFolds,
                           double candidateMae, double baselineMae, double worstError) {
        public Evidence {
            if (cohort == null || cohort.isBlank() || evaluatedWeek == null
                || minimumWeeks < 1 || !Double.isFinite(minimumDayCoverage)
                || minimumDayCoverage <= 0 || minimumDayCoverage > 1
                || independentSeries < 0 || temporalFolds < 0
                || !Double.isFinite(candidateMae) || !Double.isFinite(baselineMae)
                || !Double.isFinite(worstError) || candidateMae < 0 || baselineMae < 0
                || worstError < 0) throw new IllegalArgumentException("Invalid calibration evidence");
        }
    }

    public record Guardrails(int floorWeeks, int ceilingWeeks, double floorCoverage,
                             int minIndependentSeries, int minFolds,
                             int minimumCohorts, double minRelativeImprovement,
                             double maxWorstError) {
        public Guardrails {
            if (floorWeeks < 4 || ceilingWeeks < floorWeeks || !Double.isFinite(floorCoverage)
                || floorCoverage <= 0 || floorCoverage > 1 || minIndependentSeries < 1
                || minFolds < 5 || minimumCohorts < 2
                || !Double.isFinite(minRelativeImprovement) || minRelativeImprovement < 0
                || !Double.isFinite(maxWorstError) || maxWorstError < 0)
                throw new IllegalArgumentException("Invalid calibration guardrails");
        }
    }

    public record Selection(DataSufficiencyEvaluator.Policy policy, boolean calibrated,
                            int evaluatedCohorts, double pooledImprovement,
                            List<String> reasons) {
        public Selection { reasons = List.copyOf(reasons); }
    }

    /**
     * Every candidate must have evidence on the SAME cohort set, each row with 5+
     * chronological folds and sufficient independent series. Candidates are compared
     * via pooled baseline/candidate absolute errors, not averaged percentage gains.
     * For equal quality prefer stricter weeks/coverage; no evidence => fixed safe floor.
     */
    public static Selection select(List<Evidence> evidence, Guardrails guardrails,
                                   LocalDate asOf, int minAvailableDays) {
        Objects.requireNonNull(evidence); Objects.requireNonNull(guardrails);
        Objects.requireNonNull(asOf);
        if (minAvailableDays < 0) throw new IllegalArgumentException("Invalid stock exposure floor");
        var fallback = new DataSufficiencyEvaluator.Policy(
            guardrails.ceilingWeeks(), guardrails.floorCoverage(), minAvailableDays);
        if (evidence.isEmpty())
            return new Selection(fallback,false,0,0,List.of("NO_CALIBRATION_EVIDENCE"));
        Map<Threshold,List<Evidence>> byCandidate = new HashMap<>();
        for (var row : evidence) {
            if (row.evaluatedWeek().isAfter(asOf) || row.evaluatedWeek().equals(asOf))
                throw new IllegalArgumentException("Future or same-period calibration evidence");
            if (row.minimumWeeks()<guardrails.floorWeeks() || row.minimumWeeks()>guardrails.ceilingWeeks()
                || row.minimumDayCoverage()<guardrails.floorCoverage()) continue;
            byCandidate.computeIfAbsent(new Threshold(row.minimumWeeks(),row.minimumDayCoverage()),
                k->new ArrayList<>()).add(row);
        }
        var valid = new ArrayList<Candidate>();
        for (var entry : byCandidate.entrySet()) {
            var rows=entry.getValue();
            if (rows.size()<guardrails.minimumCohorts()) continue;
            var cohorts=new HashSet<String>();
            boolean ok=true;
            for (var row:rows) {
                String identity=row.cohort()+"/"+row.evaluatedWeek();
                if (!cohorts.add(identity) || row.independentSeries()<guardrails.minIndependentSeries()
                    || row.temporalFolds()<guardrails.minFolds()
                    || row.worstError()>guardrails.maxWorstError()) { ok=false; break; }
            }
            if (!ok) continue;
            double candidate=rows.stream().mapToDouble(Evidence::candidateMae).average().orElseThrow();
            double baseline=rows.stream().mapToDouble(Evidence::baselineMae).average().orElseThrow();
            if (baseline==0) continue; // no measurable improvement over a perfect baseline
            double improvement=(baseline-candidate)/baseline;
            if (improvement<guardrails.minRelativeImprovement()) continue;
            valid.add(new Candidate(entry.getKey(),rows.size(),improvement,candidate,Set.copyOf(cohorts)));
        }
        // Compare candidate thresholds ONLY on identical cohort/period evidence.
        // Otherwise a low threshold can win simply because it saw easier sellers.
        if(valid.size()>1 && valid.stream().map(Candidate::cohortIds).distinct().count()>1)
            return new Selection(fallback,false,0,0,List.of("INCOMPARABLE_COHORTS"));
        return valid.stream()
            .sorted(Comparator.comparingDouble(Candidate::candidateMae)
                .thenComparing(Comparator.comparingInt((Candidate c)->c.threshold().weeks()).reversed())
                .thenComparing(Comparator.comparingDouble((Candidate c)->c.threshold().coverage()).reversed()))
            .findFirst().map(c->new Selection(
                new DataSufficiencyEvaluator.Policy(c.threshold().weeks(),c.threshold().coverage(),minAvailableDays),
                true,c.cohorts(),c.improvement(),List.of()))
            .orElseGet(()->new Selection(fallback,false,0,0,List.of("NO_QUALIFIED_CANDIDATE")));
    }
    private record Threshold(int weeks,double coverage) {}
    private record Candidate(Threshold threshold,int cohorts,double improvement,
                             double candidateMae,Set<String> cohortIds) {}
}
