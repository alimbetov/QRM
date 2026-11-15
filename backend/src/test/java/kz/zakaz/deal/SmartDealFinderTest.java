package kz.zakaz.deal;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class SmartDealFinderTest {
  @Test void findsOnlyRealSavingsIncludingShipping(){
    var product=UUID.randomUUID();var seller=UUID.randomUUID();var other=UUID.randomUUID();
    var eligible=new SmartDealFinder.ComparableOffer(seller,UUID.randomUUID(),product,
          new BigDecimal("150000"),new BigDecimal("3000"),true,true);
    var expensiveShipping=new SmartDealFinder.ComparableOffer(other,UUID.randomUUID(),product,
          new BigDecimal("145000"),new BigDecimal("30000"),true,true);
    var unpublished=new SmartDealFinder.ComparableOffer(other,UUID.randomUUID(),product,
          new BigDecimal("100000"),BigDecimal.ZERO,false,true);
    var unquoted=new SmartDealFinder.ComparableOffer(other,UUID.randomUUID(),product,
          new BigDecimal("100000"),null,true,true);
    var found=SmartDealFinder.betterOffers(product,BigDecimal.ONE,new BigDecimal("165000"),
          List.of(eligible,expensiveShipping,unpublished,unquoted),new BigDecimal("10000"));
    assertThat(found).hasSize(1);
    assertThat(found.getFirst().saving()).isEqualByComparingTo("12000.00");
  }
  @Test void rejectsDifferentProduct(){
    var product=UUID.randomUUID();
    assertThat(SmartDealFinder.betterOffers(product,BigDecimal.ONE,new BigDecimal("100"),
      List.of(new SmartDealFinder.ComparableOffer(UUID.randomUUID(),UUID.randomUUID(),
        UUID.randomUUID(),BigDecimal.ONE,BigDecimal.ZERO,true,true)),BigDecimal.ZERO)).isEmpty();
  }
}
