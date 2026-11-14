package kz.zakaz.deal;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class DealQualificationTest {
  final UUID phone=UUID.randomUUID(), caseProduct=UUID.randomUUID();
  final UUID demandId=UUID.randomUUID(), buyer=UUID.randomUUID();
  final Instant now=Instant.parse("2026-11-12T10:00:00Z"), expiry=now.plusSeconds(3600);
  BuyerDemand demand(){
    return new BuyerDemand(demandId,buyer,"Алматы",expiry,List.of(
      new BuyerDemand.DemandLine(phone,BigDecimal.ONE,new BigDecimal("160000.00")),
      new BuyerDemand.DemandLine(caseProduct,new BigDecimal("2"),null)));
  }
  SellerBid bid(UUID seller, String phonePrice){
    return new SellerBid(UUID.randomUUID(),demandId,seller,1,expiry,List.of(
      new SellerBid.BidLine(phone,UUID.randomUUID(),BigDecimal.ONE,new BigDecimal(phonePrice)),
      new SellerBid.BidLine(caseProduct,UUID.randomUUID(),new BigDecimal("2"),new BigDecimal("4000.00"))));
  }
  @Test void competingSellersCanOfferFullBasket(){
    var offers=DealQualification.rankedOffers(demand(),List.of(bid(UUID.randomUUID(),"159000.00"),bid(UUID.randomUUID(),"155000.00")),now);
    assertThat(offers).hasSize(2);
    assertThat(offers.getFirst().total()).isEqualByComparingTo("163000.00");
  }
  @Test void rejectsPartialBasket(){
    var b=new SellerBid(UUID.randomUUID(),demandId,UUID.randomUUID(),1,expiry,List.of(
      new SellerBid.BidLine(phone,UUID.randomUUID(),BigDecimal.ONE,new BigDecimal("155000.00"))));
    assertThatThrownBy(()->DealQualification.validate(demand(),b,now)).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void rejectsStaleBids(){assertThatThrownBy(()->DealQualification.validate(demand(),bid(UUID.randomUUID(),"155000.00"),expiry))
      .isInstanceOf(IllegalStateException.class);}
  @Test void rejectsAlteredQuantity(){
    var b=new SellerBid(UUID.randomUUID(),demandId,UUID.randomUUID(),1,expiry,List.of(
      new SellerBid.BidLine(phone,UUID.randomUUID(),new BigDecimal("3"),new BigDecimal("155000.00")),
      new SellerBid.BidLine(caseProduct,UUID.randomUUID(),new BigDecimal("2"),new BigDecimal("4000.00"))));
    assertThatThrownBy(()->DealQualification.validate(demand(),b,now)).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void duplicateProductsRejected(){
    assertThatThrownBy(()->new BuyerDemand(demandId,buyer,"Алматы",expiry,
      List.of(new BuyerDemand.DemandLine(phone,BigDecimal.ONE,null),
              new BuyerDemand.DemandLine(phone,BigDecimal.ONE,null)))).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void immutableLines(){assertThatThrownBy(()->demand().lines().clear()).isInstanceOf(UnsupportedOperationException.class);}
}
