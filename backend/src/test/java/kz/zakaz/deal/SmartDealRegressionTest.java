package kz.zakaz.deal;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SmartDealRegressionTest {
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID SELLER = UUID.randomUUID();
    private static SmartDealFinder.ComparableOffer offer(UUID listing, UUID product,
        String unitPrice, String delivery, boolean published, boolean eligible) {
        return new SmartDealFinder.ComparableOffer(SELLER,listing,product,
            new BigDecimal(unitPrice),delivery==null?null:new BigDecimal(delivery),
            published,eligible);
    }

    @Test void comparesFinalBasketCostNotHeadlineUnitPriceForMultipleUnits() {
        UUID good=UUID.randomUUID(), costly=UUID.randomUUID();
        var deals=SmartDealFinder.betterOffers(PRODUCT,new BigDecimal("2"),
            new BigDecimal("250.00"),List.of(
                offer(good,PRODUCT,"100.00","20.00",true,true),
                offer(costly,PRODUCT,"95.00","70.00",true,true)),
            new BigDecimal("30.00"));
        assertThat(deals).hasSize(1);
        assertThat(deals.getFirst().listingId()).isEqualTo(good);
        assertThat(deals.getFirst().allInPrice()).isEqualByComparingTo("220.00");
        assertThat(deals.getFirst().saving()).isEqualByComparingTo("30.00");
    }

    @Test void neverSuggestsUnpublishedUnapprovedOtherProductOrUnquotedShipping() {
        var excluded=List.of(
            offer(UUID.randomUUID(),PRODUCT,"1.00","0.00",false,true),
            offer(UUID.randomUUID(),PRODUCT,"1.00","0.00",true,false),
            offer(UUID.randomUUID(),UUID.randomUUID(),"1.00","0.00",true,true),
            offer(UUID.randomUUID(),PRODUCT,"1.00",null,true,true));
        assertThat(SmartDealFinder.betterOffers(PRODUCT,BigDecimal.ONE,
            new BigDecimal("100.00"),excluded,BigDecimal.ZERO)).isEmpty();
    }

    @Test void equalPricesAreSortedByStableListingIdentifier() {
        UUID first=UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second=UUID.fromString("00000000-0000-0000-0000-000000000002");
        var deals=SmartDealFinder.betterOffers(PRODUCT,BigDecimal.ONE,
            new BigDecimal("200.00"),List.of(
                offer(second,PRODUCT,"100.00","10.00",true,true),
                offer(first,PRODUCT,"100.00","10.00",true,true)),BigDecimal.ZERO);
        assertThat(deals).extracting(SmartDealFinder.Deal::listingId)
            .containsExactly(first,second);
    }

    @Test void rejectsInvalidPricesAndComparisonInputs() {
        assertThatThrownBy(() -> offer(UUID.randomUUID(),PRODUCT,"-1.00","0.00",true,true))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> offer(UUID.randomUUID(),PRODUCT,"1.001","0.00",true,true))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> offer(UUID.randomUUID(),PRODUCT,"1.00","-0.01",true,true))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SmartDealFinder.betterOffers(PRODUCT,BigDecimal.ZERO,
            BigDecimal.ONE,List.of(),BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SmartDealFinder.betterOffers(PRODUCT,BigDecimal.ONE,
            BigDecimal.ONE,List.of(),new BigDecimal("-0.01"))).isInstanceOf(IllegalArgumentException.class);
    }
}
