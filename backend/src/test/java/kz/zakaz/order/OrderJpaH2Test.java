package kz.zakaz.order;
import static org.assertj.core.api.Assertions.*;
import java.math.*;import java.util.*;
import kz.zakaz.domain.types.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
@DataJpaTest @ActiveProfiles("test")
class OrderJpaH2Test{
 @Autowired CustomerOrderRepository repository;
 @Test void persistsMultiLineOrderWithoutReservations(){
  var o=CustomerOrder.placed(UUID.randomUUID(),UUID.randomUUID(),new OrderNumberGenerator().generate(),List.of(
   new OrderLine.Draft(UUID.randomUUID(),BigDecimal.ONE,MeasurementUnit.PCS,new BigDecimal("170000.00"),"Алматы","KZ-ALA"),
   new OrderLine.Draft(UUID.randomUUID(),new BigDecimal("2"),MeasurementUnit.PCS,new BigDecimal("5000.00"),"Астана","KZ-AST")));
  repository.saveAndFlush(o);
  var actual=repository.findByPublicNumber(o.getPublicNumber()).orElseThrow();
  assertThat(actual.getTotalAmount()).isEqualByComparingTo("180000.00");
  assertThat(actual.getStatus()).isEqualTo(OrderStatus.PLACED);
  assertThat(actual.getLines()).hasSize(2);
 }
}