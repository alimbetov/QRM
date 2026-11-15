package kz.zakaz.intelligence.reliability;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AdaptiveSufficiencyCalibratorTest {
    static final LocalDate AS_OF=LocalDate.of(2026,11,12);
    static final AdaptiveSufficiencyCalibrator.Guardrails RULES=
        new AdaptiveSufficiencyCalibrator.Guardrails(12,52,.90,20,5,5,.10,20);

    static List<AdaptiveSufficiencyCalibrator.Evidence> cohort(int weeks, double coverage,
                                                                double mae, double baseline) {
        var rows=new ArrayList<AdaptiveSufficiencyCalibrator.Evidence>();
        for(int i=0;i<5;i++) rows.add(new AdaptiveSufficiencyCalibrator.Evidence(
            "CITY_"+i,AS_OF.minusWeeks(10-i),weeks,coverage,50,5,mae,baseline,12));
        return rows;
    }

    @Test void adaptiveSelectionPrefersBetterOutOfSampleCandidateWithoutWeakeningFloor() {
        var rows=new ArrayList<AdaptiveSufficiencyCalibrator.Evidence>();
        rows.addAll(cohort(12,.95,8,10));
        rows.addAll(cohort(26,.95,6,10));
        var s=AdaptiveSufficiencyCalibrator.select(rows,RULES,AS_OF,10);
        assertThat(s.calibrated()).isTrue();
        assertThat(s.policy().minimumWeeks()).isEqualTo(26);
        assertThat(s.policy().minimumCoverage()).isEqualTo(.95);
        assertThat(s.evaluatedCohorts()).isEqualTo(5);
        assertThat(s.pooledImprovement()).isCloseTo(.4,within(.0001));
    }

    @Test void missingEvidenceUsesStrictSafeFallback() {
        var s=AdaptiveSufficiencyCalibrator.select(List.of(),RULES,AS_OF,10);
        assertThat(s.calibrated()).isFalse();
        assertThat(s.policy().minimumWeeks()).isEqualTo(52);
        assertThat(s.reasons()).contains("NO_CALIBRATION_EVIDENCE");
    }

    @Test void unsafeCandidatesBelowFloorAndTinyCohortsCannotSelfApprove() {
        var rows=new ArrayList<AdaptiveSufficiencyCalibrator.Evidence>();
        rows.addAll(cohort(4,.95,1,10)); // below floor
        rows.addAll(cohort(26,.95,30,10)); // worse than baseline
        var s=AdaptiveSufficiencyCalibrator.select(rows,RULES,AS_OF,1);
        assertThat(s.calibrated()).isFalse();
        assertThat(s.reasons()).contains("NO_QUALIFIED_CANDIDATE");
    }

    @Test void futureEvidenceAndDuplicateCohortAreRejectedOrExcluded() {
        var rows=cohort(26,.95,6,10);
        rows.set(0,new AdaptiveSufficiencyCalibrator.Evidence("FUTURE",AS_OF.plusWeeks(1),
            26,.95,50,5,6,10,12));
        assertThatThrownBy(()->AdaptiveSufficiencyCalibrator.select(rows,RULES,AS_OF,10))
            .isInstanceOf(IllegalArgumentException.class);
        var duplicates=new ArrayList<>(cohort(26,.95,6,10));
        duplicates.set(4,duplicates.get(0));
        assertThat(AdaptiveSufficiencyCalibrator.select(duplicates,RULES,AS_OF,10).calibrated())
            .isFalse();
    }

    @Test void worstFoldErrorsAndInsufficientIndependentSeriesBlockCandidate() {
        var rows=cohort(26,.95,6,10);
        rows.set(2,new AdaptiveSufficiencyCalibrator.Evidence("CITY_2",AS_OF.minusWeeks(8),
            26,.95,1,5,6,10,12));
        assertThat(AdaptiveSufficiencyCalibrator.select(rows,RULES,AS_OF,10).calibrated())
            .isFalse();
    }
    @Test void differentEvaluationCohortsCannotSelectMisleadingWinningThreshold() {
        var easy=cohort(12,.95,1,10);
        var hard=new ArrayList<AdaptiveSufficiencyCalibrator.Evidence>();
        for(int i=0;i<5;i++) hard.add(new AdaptiveSufficiencyCalibrator.Evidence(
            "OTHER_CITY_"+i,AS_OF.minusWeeks(10-i),26,.95,50,5,6,10,12));
        var evidence=new ArrayList<AdaptiveSufficiencyCalibrator.Evidence>();
        evidence.addAll(easy);evidence.addAll(hard);
        var selection=AdaptiveSufficiencyCalibrator.select(evidence,RULES,AS_OF,10);
        assertThat(selection.calibrated()).isFalse();
        assertThat(selection.reasons()).contains("INCOMPARABLE_COHORTS");
        assertThat(selection.policy().minimumWeeks()).isEqualTo(52);
    }
}
