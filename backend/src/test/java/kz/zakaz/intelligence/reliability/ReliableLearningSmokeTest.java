package kz.zakaz.intelligence.reliability;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import kz.zakaz.intelligence.WeeklyIntelligenceAggregator;
import static org.assertj.core.api.Assertions.*;

class ReliableLearningSmokeTest {
    static final UUID SELLER=UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID SKU=UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final varHolder KEY=new varHolder();
    static class varHolder {
        WeeklyIntelligenceAggregator.Key value() {
            return new WeeklyIntelligenceAggregator.Key(SELLER,SKU,"GEO.ALMATY");
        }
    }
    private List<WeeklyIntelligenceAggregator.Week> series(int n) {
        var out=new ArrayList<WeeklyIntelligenceAggregator.Week>();
        var monday=LocalDate.of(2026,1,5);
        for(int i=0;i<n;i++){
            out.add(new WeeklyIntelligenceAggregator.Week(
                new WeeklyIntelligenceAggregator.WeekKey(KEY.value(),monday.plusWeeks(i)),
                20+i, 20+i,100,7,0,0,7));
        }
        return out;
    }
    @Test void fiveChronologicalFoldsUsePastOnlyAndProduceStableMetrics() {
        var weeks=series(35);
        var result=TemporalBacktester.evaluateFive(weeks,KEY.value(),12,
            history -> history.getLast().demandUnits()+1);
        assertThat(result.folds()).hasSize(5);
        assertThat(result.folds()).extracting(TemporalBacktester.Fold::trainingWeeks)
            .containsExactly(30,31,32,33,34);
        assertThat(result.challengerMae()).isZero();
        assertThat(result.baselineMae()).isEqualTo(2.5);
        assertThat(result.improvementFraction()).isEqualTo(1);
        assertThat(result.folds().getFirst().targetMonday())
            .isAfter(weeks.get(29).id().monday());
        var suff=DataSufficiencyEvaluator.evaluate(weeks,KEY.value(),
            weeks.getLast().id().monday(), new DataSufficiencyEvaluator.Policy(26,0.95,100));
        assertThat(suff.qualified()).isTrue();
        assertThat(PredictionPolicyEngine.decide(suff,result,
            new PredictionPolicyEngine.Policy(0.10,5),true,true).choice())
            .isEqualTo(PredictionPolicyEngine.Choice.TRIBUO_CANDIDATE);
    }
    @Test void sparseAndStockoutHistoryTriggerFallback() {
        var weeks=series(10);
        weeks.set(9,new WeeklyIntelligenceAggregator.Week(weeks.get(9).id(),
            0,0,0,0,0,7,7));
        var quality=DataSufficiencyEvaluator.evaluate(weeks,KEY.value(),
            weeks.getLast().id().monday(),new DataSufficiencyEvaluator.Policy(26,0.95,100));
        assertThat(quality.qualified()).isFalse();
        assertThat(quality.reasons()).contains("MISSING_OR_UNKNOWN_EXPOSURE");
        assertThat(PredictionPolicyEngine.decide(quality,null,
            new PredictionPolicyEngine.Policy(.1,5),true,true).choice())
            .isEqualTo(PredictionPolicyEngine.Choice.MOVING_AVERAGE);
    }
    @Test void schemaMismatchDriftAndUnstableErrorNeverPromote() {
        var weeks=series(35);
        var quality=DataSufficiencyEvaluator.evaluate(weeks,KEY.value(),
            weeks.getLast().id().monday(),new DataSufficiencyEvaluator.Policy(26,.95,100));
        var bad=TemporalBacktester.evaluateFive(weeks,KEY.value(),12,h -> 10000);
        var decision=PredictionPolicyEngine.decide(quality,bad,
            new PredictionPolicyEngine.Policy(.10,10),false,false);
        assertThat(decision.choice()).isEqualTo(PredictionPolicyEngine.Choice.MOVING_AVERAGE);
        assertThat(decision.reasons()).contains("NO_BASELINE_UPLIFT","UNSTABLE_WORST_FOLD",
            "CRITICAL_DRIFT","SCHEMA_MISMATCH");
    }
    @Test void temporalBacktestRejectsGapsAndIncompleteWeeks() {
        var weeks=series(35);
        weeks.remove(14);
        assertThatThrownBy(()->TemporalBacktester.evaluateFive(weeks,KEY.value(),12,
            TemporalBacktester::movingAverage4)).isInstanceOf(IllegalArgumentException.class);
        var partial=series(35);
        var w=partial.get(10);
        partial.set(10,new WeeklyIntelligenceAggregator.Week(w.id(),1,2,3,4,0,0,4));
        assertThatThrownBy(()->TemporalBacktester.evaluateFive(partial,KEY.value(),12,
            TemporalBacktester::movingAverage4)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void challengerCannotSeeFutureWeeks() {
        var weeks=series(35);
        var predictions=new ArrayList<Long>();
        TemporalBacktester.evaluateFive(weeks,KEY.value(),12,h -> {
            predictions.add(h.getLast().demandUnits());
            return TemporalBacktester.movingAverage4(h);
        });
        assertThat(predictions).containsExactly(49L,50L,51L,52L,53L);
    }
}
