package kz.zakaz.intelligence.reliability;

import java.util.*;

/**
 * No inferred procurement quantity or real Tribuo inference. Promotion requires independent
 * operational/privacy review in addition to these deterministic pilot gates.
 */
public final class PredictionPolicyEngine {
    private PredictionPolicyEngine() {}
    public enum Choice { TRIBUO_CANDIDATE, MOVING_AVERAGE, HCC_CATEGORY_BASELINE }
    public record Policy(double minimumImprovementFraction, double maximumWorstFoldError) {
        public Policy {
            if (!Double.isFinite(minimumImprovementFraction) || minimumImprovementFraction<0
                || !Double.isFinite(maximumWorstFoldError) || maximumWorstFoldError<0)
                throw new IllegalArgumentException("Invalid policy");
        }
    }
    public record Decision(Choice choice, List<String> reasons) {
        public Decision { reasons=List.copyOf(reasons); }
    }

    public static Decision decide(DataSufficiencyEvaluator.Report sufficiency,
                                  TemporalBacktester.Report backtest,
                                  Policy policy, boolean noCriticalDrift,
                                  boolean modelSchemaCompatible) {
        Objects.requireNonNull(sufficiency);
        Objects.requireNonNull(policy);
        var reasons=new ArrayList<String>();
        if (!sufficiency.qualified()) reasons.addAll(sufficiency.reasons());
        if (backtest==null || backtest.folds().size()!=5) reasons.add("NO_FIVE_FOLD_EVIDENCE");
        else {
            if (backtest.improvementFraction()<policy.minimumImprovementFraction())
                reasons.add("NO_BASELINE_UPLIFT");
            if (backtest.worstChallengerError()>policy.maximumWorstFoldError())
                reasons.add("UNSTABLE_WORST_FOLD");
        }
        if (!noCriticalDrift) reasons.add("CRITICAL_DRIFT");
        if (!modelSchemaCompatible) reasons.add("SCHEMA_MISMATCH");
        if (reasons.isEmpty()) return new Decision(Choice.TRIBUO_CANDIDATE,List.of());
        return new Decision(sufficiency.fullWeeks()>=4 ? Choice.MOVING_AVERAGE
            : Choice.HCC_CATEGORY_BASELINE,reasons);
    }
}
