package kz.zakaz.intelligence;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WeeklyIntelligenceSmokeTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Almaty");
    private static final Instant AS_OF = Instant.parse("2026-11-12T12:00:00Z");
    private static final UUID SELLER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID HOT = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID SLOW = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final UUID STOCKOUT = UUID.fromString("00000000-0000-0000-0000-000000000013");

    /** Deterministic 12 closed weeks, three products, 252 observed SKU-days. */
    private static List<WeeklyIntelligenceAggregator.Day> seed() {
        List<WeeklyIntelligenceAggregator.Day> items = new ArrayList<>();
        LocalDate start = LocalDate.of(2026,8,17);
        for (int day = 0; day < 84; day++) {
            LocalDate date = start.plusDays(day);
            items.add(new WeeklyIntelligenceAggregator.Day(SELLER, HOT, "GEO.ALMATY", date,7,9,18,true));
            items.add(new WeeklyIntelligenceAggregator.Day(SELLER, SLOW, "GEO.ALMATY", date,0,1,4,true));
            boolean available = day % 7 < 4;
            items.add(new WeeklyIntelligenceAggregator.Day(SELLER, STOCKOUT, "GEO.ALMATY",
                date,available ? 2 : 0,9,15,available));
        }
        // Open week leakage trap: enormous sale must not enter closed-week features.
        items.add(new WeeklyIntelligenceAggregator.Day(SELLER, HOT, "GEO.ALMATY",
            LocalDate.of(2026,11,10),99999,99999,99999,true));
        return items;
    }

    private static WeeklyIntelligenceAggregator.Key key(UUID sku) {
        return new WeeklyIntelligenceAggregator.Key(SELLER,sku,"GEO.ALMATY");
    }

    @Test void twelveWeeksExcludeCurrentPartialWeekAndRespectIsoMonday() {
        var weeks=WeeklyIntelligenceAggregator.aggregateClosedWeeks(seed(),AS_OF,ZONE);
        assertThat(weeks).hasSize(36);
        var hot4=WeeklyIntelligenceAggregator.rollingWindow(weeks,key(HOT),AS_OF,ZONE,4);
        var hot1=WeeklyIntelligenceAggregator.rollingWindow(weeks,key(HOT),AS_OF,ZONE,1);
        var hot12=WeeklyIntelligenceAggregator.rollingWindow(weeks,key(HOT),AS_OF,ZONE,12);
        assertThat(hot4.lastClosedMonday()).isEqualTo(LocalDate.of(2026,11,2));
        assertThat(hot4.soldUnits()).isEqualTo(196);
        assertThat(hot4.demandUnits()).isEqualTo(252);
        assertThat(hot4.observedWeeks()).isEqualTo(4);
        assertThat(hot4.complete()).isTrue();
        assertThat(hot1.soldUnits()).isEqualTo(49);
        assertThat(hot12.soldUnits()).isEqualTo(588);
    }

    @Test void smokeDistinguishesHighDemandSlowStockAndOutOfStockFromLowInterest() {
        var weeks=WeeklyIntelligenceAggregator.aggregateClosedWeeks(seed(),AS_OF,ZONE);
        var hot=WeeklyIntelligenceAggregator.rollingWindow(weeks,key(HOT),AS_OF,ZONE,4);
        var slow=WeeklyIntelligenceAggregator.rollingWindow(weeks,key(SLOW),AS_OF,ZONE,4);
        var outage=WeeklyIntelligenceAggregator.rollingWindow(weeks,key(STOCKOUT),AS_OF,ZONE,4);
        assertThat(hot.soldUnits()).isEqualTo(196);
        assertThat(slow.soldUnits()).isZero();
        assertThat(slow.demandUnits()).isEqualTo(28);
        assertThat(slow.availableDays()).isEqualTo(28);
        assertThat(outage.soldUnits()).isEqualTo(32);
        assertThat(outage.demandUnits()).isEqualTo(252);
        assertThat(outage.stockoutDays()).isEqualTo(12);
        assertThat(outage.availableDays()).isEqualTo(16);
        assertThat(outage.soldPerAvailableDay()).hasValue(2.0);
    }

    @Test void stableFeatureArrayUsesClosedWeekAndAvailabilityAdjustedVelocity() {
        var weeks=WeeklyIntelligenceAggregator.aggregateClosedWeeks(seed(),AS_OF,ZONE);
        var f=WeeklyFeatureProjector.project(
            WeeklyIntelligenceAggregator.rollingWindow(weeks,key(STOCKOUT),AS_OF,ZONE,1),
            WeeklyIntelligenceAggregator.rollingWindow(weeks,key(STOCKOUT),AS_OF,ZONE,4));
        assertThat(f.dense()).containsExactly(8.0,32.0,252.0,16.0,12.0,2.0,1.0,4.0,1.0);
        assertThat(f.features()).hasSize(9);
    }

    @Test void missingStockExposureIsUnknownNotARealZero() {
        var input=List.of(new WeeklyIntelligenceAggregator.Day(SELLER,SLOW,"GEO.ALMATY",
            LocalDate.of(2026,11,2),0,1,5,null));
        var weeks=WeeklyIntelligenceAggregator.aggregateClosedWeeks(input,AS_OF,ZONE);
        var four=WeeklyIntelligenceAggregator.rollingWindow(weeks,key(SLOW),AS_OF,ZONE,4);
        assertThat(four.complete()).isFalse();
        assertThat(four.soldPerAvailableDay()).isEmpty();
        var one=WeeklyIntelligenceAggregator.rollingWindow(weeks,key(SLOW),AS_OF,ZONE,1);
        var f=WeeklyFeatureProjector.project(one,four);
        assertThat(f.dense()[5]).isZero();
        assertThat(f.dense()[6]).isZero();
        assertThat(f.dense()[8]).isZero();
    }

    @Test void duplicateDailyObservationRejected() {
        var item=seed().getFirst();
        assertThatThrownBy(() -> WeeklyIntelligenceAggregator.aggregateClosedWeeks(
            List.of(item,item), AS_OF, ZONE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void cannotMixDifferentSkuWindows() {
        var weeks=WeeklyIntelligenceAggregator.aggregateClosedWeeks(seed(),AS_OF,ZONE);
        assertThatThrownBy(() -> WeeklyFeatureProjector.project(
            WeeklyIntelligenceAggregator.rollingWindow(weeks,key(HOT),AS_OF,ZONE,1),
            WeeklyIntelligenceAggregator.rollingWindow(weeks,key(SLOW),AS_OF,ZONE,4)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
